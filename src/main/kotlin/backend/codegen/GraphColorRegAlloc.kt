package backend.codegen

import backend.codegen.riscv.*

// ============================================================================
//  Graph-Coloring Register Allocator — Chaitin-Briggs with Iterated Coalescing
// ============================================================================
//
//  Implements the classic Chaitin-Briggs register allocation algorithm with
//  iterated register coalescing (George & Appel, 1996).
//
//  Phases (repeated until no spills):
//    1. BUILD      — liveness analysis + interference graph construction
//    2. SIMPLIFY   — push non-move-related, low-degree (<K) nodes onto stack
//    3. COALESCE   — conservative coalescing (Briggs criterion)
//    4. FREEZE     — give up coalescing for a low-degree move-related node
//    5. POTENTIAL SPILL — optimistically push a high-degree node
//    6. SELECT     — pop stack and assign colors (physical registers)
//    7. SPILL & RETRY — if actual spills found, insert load/store, restart
//
//  K = 27 (number of allocatable physical registers on RV32I: t0–t6, a0–a7, s0–s11)
//
//  After successful allocation the allocator rewrites every instruction in the
//  machine function, replacing virtual register operands with physical registers
//  via `RvInst.mapRegs`.  Coalesced moves (`mv rX, rX`) are deleted.
// ============================================================================

/**
 * Result of a successful register allocation.
 *
 * @property coloring   Maps every virtual register id to its assigned physical register.
 * @property usedCallee The set of callee-saved registers that were actually used
 *                      (and therefore must be saved/restored in the prologue/epilogue).
 */
data class RegAllocResult(
    val coloring: Map<Int, RvOperand.PhysReg>,
    val usedCallee: Set<RvPhysReg>,
)

/**
 * Chaitin-Briggs graph-coloring register allocator with iterated register coalescing.
 *
 * Usage:
 * ```
 *   val allocator = GraphColorRegAlloc()
 *   allocator.allocate(mf)  // mutates mf in-place
 * ```
 *
 * After [allocate] returns, all virtual registers in [mf] have been replaced
 * with physical registers, redundant moves have been deleted, and
 * [RvMachineFunction.usedCalleeSaved] has been populated.
 */
class GraphColorRegAlloc {

    companion object {
        /** Number of allocatable physical registers (the chromatic budget K). */
        private const val K = NUM_ALLOCATABLE

        /**
         * Maximum number of build-allocate-spill iterations before we give up.
         * In practice this is reached only for pathological inputs; normal programs
         * converge in 1–3 rounds.
         */
        private const val MAX_ROUNDS = 50
    }

    // ======================================================================
    //  Public API
    // ======================================================================

    /**
     * Run register allocation on [mf], mutating it in-place.
     *
     * When this method returns:
     * - Every `RvOperand.Reg` has been replaced with `RvOperand.PhysReg`.
     * - Redundant moves (`mv rX, rX`) have been removed.
     * - `mf.usedCalleeSaved` contains the callee-saved registers that were used.
     */
    fun allocate(mf: RvMachineFunction) {
        var round = 0
        while (round < MAX_ROUNDS) {
            round++

            // Ensure CFG edges are up-to-date.
            mf.rebuildCfgEdges()

            // BUILD: liveness + interference graph.
            val liveness = LivenessAnalysis.analyze(mf)
            val ig = InterferenceGraph.build(mf, liveness)

            // Run the iterative simplify/coalesce/freeze/spill-select loop.
            val ctx = AllocContext(mf, ig)
            ctx.makeWorklists()
            ctx.iterateUntilEmpty()

            // SELECT: assign colors.
            val spilledKeys = ctx.select()

            if (spilledKeys.isEmpty()) {
                // Success — rewrite and finish.
                val result = ctx.buildResult()
                rewriteFunction(mf, result)
                return
            }

            // SPILL: insert load/store for every spilled vreg, then retry.
            insertSpillCode(mf, spilledKeys)
        }

        error("Register allocator failed to converge after $MAX_ROUNDS rounds")
    }

    // ======================================================================
    //  Allocation context — per-round mutable state
    // ======================================================================

    /**
     * Encapsulates the mutable working state for one round of the allocator.
     *
     * The design follows the Appel/George "iterated register coalescing" paper.
     * Node categorisation:
     *   - **precolored**: physical register nodes (never simplified or spilled)
     *   - **simplifyWorklist**: low-degree, non-move-related virtual regs
     *   - **freezeWorklist**: low-degree, move-related virtual regs
     *   - **spillWorklist**: high-degree virtual regs
     *   - **spilledNodes**: nodes that couldn't be colored during SELECT
     *   - **coalescedNodes**: nodes merged away by the coalescer
     *   - **coloredNodes**: successfully colored nodes (populated during SELECT)
     *   - **selectStack**: nodes pushed during SIMPLIFY / POTENTIAL SPILL
     */
    private inner class AllocContext(
        val mf: RvMachineFunction,
        val ig: InterferenceGraph,
    ) {
        // -- Node partitions --------------------------------------------------
        val precolored = mutableSetOf<RegKey>()
        val simplifyWorklist = mutableListOf<RegKey>()
        val freezeWorklist = mutableListOf<RegKey>()
        val spillWorklist = mutableListOf<RegKey>()
        val spilledNodes = mutableSetOf<RegKey>()
        val coalescedNodes = mutableSetOf<RegKey>()
        val coloredNodes = mutableSetOf<RegKey>()
        val selectStack = ArrayDeque<RegKey>()

        // -- Move partitions --------------------------------------------------
        val coalescedMoves = mutableSetOf<MoveEdge>()
        val constrainedMoves = mutableSetOf<MoveEdge>()
        val frozenMoves = mutableSetOf<MoveEdge>()
        val worklistMoves = mutableSetOf<MoveEdge>()
        val activeMoves = mutableSetOf<MoveEdge>()

        // Track which nodes have been removed from the graph (pushed onto stack
        // or coalesced) so we skip them in adjacency queries.
        val removedFromGraph = mutableSetOf<RegKey>()

        // -- Helpers ----------------------------------------------------------

        /** Active (not yet processed or frozen) moves associated with node [n]. */
        fun nodeMoves(n: RegKey): Set<MoveEdge> {
            val moves = ig.moveList[n] ?: return emptySet()
            return moves.filter { it in activeMoves || it in worklistMoves }.toSet()
        }

        /** Whether node [n] is related to any active or worklist move. */
        fun moveRelated(n: RegKey): Boolean = nodeMoves(n).isNotEmpty()

        /** Adjacent nodes of [n] that are still in the graph (not removed). */
        fun adjacent(n: RegKey): Set<RegKey> {
            return ig.neighbors(n).filterTo(mutableSetOf()) {
                it !in removedFromGraph && it !in coalescedNodes
            }
        }

        /** Current degree of a node (accounting for removed neighbors). */
        fun activeDegree(n: RegKey): Int {
            return adjacent(n).size
        }

        // =====================================================================
        //  Phase: MAKE WORKLISTS
        // =====================================================================

        fun makeWorklists() {
            // Populate move worklist from all moves in the interference graph.
            for ((_, moves) in ig.moveList) {
                for (m in moves) {
                    worklistMoves.add(m)
                }
            }

            for (n in ig.nodes) {
                if (n is RegKey.Phys) {
                    precolored.add(n)
                    continue
                }
                // n is a Vreg.
                if (ig.degree(n) >= K) {
                    spillWorklist.add(n)
                } else if (moveRelated(n)) {
                    freezeWorklist.add(n)
                } else {
                    simplifyWorklist.add(n)
                }
            }
        }

        // =====================================================================
        //  Phase: Main loop — Simplify / Coalesce / Freeze / SelectSpills
        // =====================================================================

        fun iterateUntilEmpty() {
            while (true) {
                when {
                    simplifyWorklist.isNotEmpty() -> simplify()
                    worklistMoves.isNotEmpty() -> coalesce()
                    freezeWorklist.isNotEmpty() -> freeze()
                    spillWorklist.isNotEmpty() -> selectSpill()
                    else -> break
                }
            }
        }

        // =====================================================================
        //  SIMPLIFY
        // =====================================================================

        private fun simplify() {
            val n = simplifyWorklist.removeLastOrNull() ?: return
            selectStack.addLast(n)
            removedFromGraph.add(n)

            // Decrement the effective degree of each remaining neighbor.
            for (adj in ig.neighbors(n)) {
                if (adj in removedFromGraph || adj in coalescedNodes) continue
                decrementDegree(adj)
            }
        }

        /**
         * Logically decrement the degree of [m] (because a neighbor was removed).
         * If degree drops from K to K−1, enable moves for m and its neighbors,
         * then move m to the appropriate worklist.
         */
        private fun decrementDegree(m: RegKey) {
            if (m is RegKey.Phys) return  // phys regs have infinite degree

            val oldDeg = ig.degree[m] ?: return
            ig.degree[m] = oldDeg - 1
            val newDeg = oldDeg - 1

            if (oldDeg == K) {
                // Was high-degree, now low-degree → enable moves.
                enableMoves(m)
                for (adj in adjacent(m)) {
                    enableMoves(adj)
                }
                spillWorklist.remove(m)
                if (moveRelated(m)) {
                    freezeWorklist.add(m)
                } else {
                    simplifyWorklist.add(m)
                }
            }
        }

        /** Move any active moves associated with [n] back to the worklist. */
        private fun enableMoves(n: RegKey) {
            for (m in nodeMoves(n)) {
                if (m in activeMoves) {
                    activeMoves.remove(m)
                    worklistMoves.add(m)
                }
            }
        }

        // =====================================================================
        //  COALESCE (conservative — Briggs criterion)
        // =====================================================================

        private fun coalesce() {
            val m = worklistMoves.firstOrNull() ?: return
            worklistMoves.remove(m)

            val x = ig.getAlias(m.dst)
            val y = ig.getAlias(m.src)

            // Ensure the pre-colored node (if any) is `u`.
            val (u, v) = if (y is RegKey.Phys) Pair(y, x) else Pair(x, y)

            if (u == v) {
                // Trivially coalesced (self-move after alias resolution).
                coalescedMoves.add(m)
                addWorklistIfReady(u)
            } else if (v is RegKey.Phys || LivenessResult.orderedPair(u, v) in ig.adjSet) {
                // Constrained — the two interfere.
                constrainedMoves.add(m)
                addWorklistIfReady(u)
                addWorklistIfReady(v)
            } else if (canCoalesce(u, v)) {
                // Safe to coalesce using the Briggs or George criterion.
                coalescedMoves.add(m)
                combine(u, v)
                addWorklistIfReady(u)
            } else {
                // Not safe yet — defer.
                activeMoves.add(m)
            }
        }

        /**
         * Determine whether it is safe to coalesce [u] and [v].
         *
         * Uses the **George** criterion when [u] is pre-colored (every neighbor
         * of [v] either already interferes with [u] or has degree < K), and the
         * **Briggs** criterion otherwise (the merged node would have fewer than
         * K high-degree neighbors).
         */
        private fun canCoalesce(u: RegKey, v: RegKey): Boolean {
            if (u is RegKey.Phys) {
                // George criterion: for every neighbor t of v,
                // either t already interferes with u, or degree(t) < K.
                return adjacent(v).all { t ->
                    ig.degree(t) < K ||
                    t is RegKey.Phys ||
                    LivenessResult.orderedPair(t, u) in ig.adjSet
                }
            } else {
                // Briggs criterion: the combined node would have < K
                // neighbors with degree >= K.
                val combinedAdj = (adjacent(u) + adjacent(v)) - setOf(u, v)
                val highDegreeCount = combinedAdj.count { t ->
                    ig.degree(t) >= K
                }
                return highDegreeCount < K
            }
        }

        /**
         * Merge [v] into [u]: make [u] the representative, transfer
         * [v]'s adjacency and move lists, remove [v] from worklists.
         */
        private fun combine(u: RegKey, v: RegKey) {
            if (v in freezeWorklist) {
                freezeWorklist.remove(v)
            } else {
                spillWorklist.remove(v)
            }
            coalescedNodes.add(v)
            ig.alias[v] = u

            // Merge move lists.
            val vMoves = ig.moveList[v] ?: mutableSetOf()
            ig.moveList.getOrPut(u) { mutableSetOf() }.addAll(vMoves)

            // Transfer adjacency: for each neighbor t of v, add edge (t, u).
            for (t in adjacent(v)) {
                ig.addEdge(t, u)
                // addEdge incremented u's degree and t's degree via ig.addEdge.
                // But we also need to logically decrement t's degree because
                // v is being removed.
                decrementDegree(t)
            }

            // If u's degree is now >= K and it was in the freeze worklist,
            // move it to the spill worklist.
            if (ig.degree(u) >= K && u in freezeWorklist) {
                freezeWorklist.remove(u)
                spillWorklist.add(u)
            }
        }

        /**
         * If [u] is a non-precolored, non-move-related, low-degree node,
         * move it from the freeze worklist to the simplify worklist.
         */
        private fun addWorklistIfReady(u: RegKey) {
            if (u is RegKey.Phys) return
            if (moveRelated(u)) return
            if (ig.degree(u) >= K) return
            freezeWorklist.remove(u)
            if (u !in simplifyWorklist) {
                simplifyWorklist.add(u)
            }
        }

        // =====================================================================
        //  FREEZE
        // =====================================================================

        private fun freeze() {
            // Pick any low-degree, move-related node and give up on coalescing it.
            val u = freezeWorklist.removeLastOrNull() ?: return
            simplifyWorklist.add(u)
            freezeMoves(u)
        }

        /**
         * For each move associated with [u], mark it as frozen and potentially
         * unblock the other endpoint.
         */
        private fun freezeMoves(u: RegKey) {
            for (m in nodeMoves(u)) {
                val x = ig.getAlias(m.dst)
                val y = ig.getAlias(m.src)
                val v = if (y == ig.getAlias(u)) x else y

                activeMoves.remove(m)
                worklistMoves.remove(m)
                frozenMoves.add(m)

                // If v is no longer move-related and is low-degree, it can
                // move from freeze to simplify.
                if (v is RegKey.Vreg && !moveRelated(v) && ig.degree(v) < K) {
                    freezeWorklist.remove(v)
                    if (v !in simplifyWorklist) {
                        simplifyWorklist.add(v)
                    }
                }
            }
        }

        // =====================================================================
        //  POTENTIAL SPILL
        // =====================================================================

        private fun selectSpill() {
            // Heuristic: pick the node with the highest degree (most interference).
            // This is a simple but effective heuristic. More advanced ones consider
            // use frequency and live-range length.
            val candidate = spillWorklist.maxByOrNull { ig.degree(it) } ?: return
            spillWorklist.remove(candidate)
            simplifyWorklist.add(candidate)
            freezeMoves(candidate)
        }

        // =====================================================================
        //  SELECT — pop stack and assign colors
        // =====================================================================

        /**
         * Pop nodes from the select stack and try to assign a color to each.
         *
         * @return The set of [RegKey.Vreg]s that could not be colored (actual spills).
         */
        fun select(): Set<RegKey> {
            val actualSpills = mutableSetOf<RegKey>()

            while (selectStack.isNotEmpty()) {
                val n = selectStack.removeLast()

                // Compute the set of colors used by already-colored neighbors.
                val usedColors = mutableSetOf<RvPhysReg>()
                for (adj in ig.neighbors(n)) {
                    val rep = ig.getAlias(adj)
                    val c = ig.color[rep]
                    if (c != null) {
                        usedColors.add(c)
                    }
                }

                // Try to find an available color.
                val available = pickColor(usedColors, n)
                if (available != null) {
                    ig.color[n] = available
                    coloredNodes.add(n)
                } else {
                    actualSpills.add(n)
                    spilledNodes.add(n)
                }
            }

            // Propagate colors to coalesced nodes.
            for (n in coalescedNodes) {
                val rep = ig.getAlias(n)
                val c = ig.color[rep]
                if (c != null) {
                    ig.color[n] = c
                }
            }

            return actualSpills
        }

        /**
         * Pick a physical register not in [usedColors].
         *
         * Preference order (for REIMU performance):
         *   1. **Caller-saved** (t0–t6, a0–a7) for nodes NOT live across a call.
         *   2. **Callee-saved** (s0–s11) for nodes live across a call.
         *
         * For simplicity we use a static preference order:
         *   t0–t6, a0–a7, s0–s11.
         * This prefers caller-saved registers, avoiding unnecessary
         * callee-saved save/restore overhead.
         */
        private fun pickColor(usedColors: Set<RvPhysReg>, node: RegKey): RvPhysReg? {
            // Preference: temporaries first, then args, then saved.
            for (reg in ALLOCATABLE_REGS) {
                if (reg !in usedColors) {
                    return reg
                }
            }
            return null
        }

        // =====================================================================
        //  Build final result
        // =====================================================================

        /**
         * Build the [RegAllocResult] from the completed coloring.
         */
        fun buildResult(): RegAllocResult {
            val coloring = mutableMapOf<Int, RvOperand.PhysReg>()
            val usedCallee = mutableSetOf<RvPhysReg>()

            for ((key, phys) in ig.color) {
                if (key is RegKey.Vreg) {
                    coloring[key.id] = RvOperand.PhysReg(phys)
                }
                if (phys in CALLEE_SAVED_REGS) {
                    usedCallee.add(phys)
                }
            }

            return RegAllocResult(coloring, usedCallee)
        }
    }

    // ======================================================================
    //  Rewrite — replace virtual registers with physical registers
    // ======================================================================

    /**
     * Apply the allocation result to [mf]: replace every virtual register operand
     * with its assigned physical register, delete coalesced moves, and record
     * which callee-saved registers are used.
     */
    private fun rewriteFunction(mf: RvMachineFunction, result: RegAllocResult) {
        val mapping = result.coloring
        mf.usedCalleeSaved.addAll(result.usedCallee)

        for (block in mf.blocks) {
            val newInsts = mutableListOf<RvInst>()
            for (inst in block.instructions) {
                val rewritten = inst.mapRegs(mapping)

                // Delete trivial moves (mv rX, rX) — result of coalescing.
                if (rewritten is RvInst.Mv && rewritten.rd == rewritten.rs) {
                    continue
                }
                newInsts.add(rewritten)
            }
            block.instructions.clear()
            block.instructions.addAll(newInsts)
        }
    }

    // ======================================================================
    //  Spill code insertion
    // ======================================================================

    /**
     * For each spilled virtual register, allocate a stack slot and insert
     * `sw` after every definition and `lw` before every use.
     *
     * Each spill point gets a **fresh** tiny-lived virtual register so that
     * the next round's liveness analysis sees very short live ranges that
     * are easy to color.
     */
    private fun insertSpillCode(mf: RvMachineFunction, spilledKeys: Set<RegKey>) {
        // Map each spilled vreg id to its stack slot index.
        val spillSlots = mutableMapOf<Int, Int>()

        for (key in spilledKeys) {
            if (key !is RegKey.Vreg) continue
            val slotIdx = mf.allocateStackSlot("spill.v${key.id}", 4, 4)
            spillSlots[key.id] = slotIdx
        }

        if (spillSlots.isEmpty()) return

        for (block in mf.blocks) {
            val newInsts = mutableListOf<RvInst>()

            for (inst in block.instructions) {
                // Identify which spilled vregs are used and defined by this instruction.
                val usedSpills = mutableListOf<Pair<Int, Int>>() // (vregId, slotIdx)
                val defSpills = mutableListOf<Pair<Int, Int>>()  // (vregId, slotIdx)

                for (op in inst.uses()) {
                    if (op is RvOperand.Reg && op.id in spillSlots) {
                        usedSpills.add(op.id to spillSlots[op.id]!!)
                    }
                }
                for (op in inst.defs()) {
                    if (op is RvOperand.Reg && op.id in spillSlots) {
                        defSpills.add(op.id to spillSlots[op.id]!!)
                    }
                }

                // Build a rewriting map: spilled vreg → fresh tiny vreg.
                val rewriteMap = mutableMapOf<Int, RvOperand.PhysReg>()

                // For each used spill: allocate a fresh vreg and insert lw before.
                // We misuse PhysReg as the rewrite target type is
                // Map<Int, RvOperand.PhysReg> in mapRegs. Instead, we need to allocate
                // fresh Reg and insert loads. mapRegs only maps vreg id → PhysReg,
                // which doesn't help here. So we do manual rewriting.

                // Since mapRegs only supports vreg→PhysReg, we'll instead replace
                // the spilled vreg with a fresh vreg in all operands manually.
                val freshMap = mutableMapOf<Int, RvOperand.Reg>()

                // Allocate fresh vregs for each spilled operand.
                for ((vregId, _) in usedSpills + defSpills) {
                    if (vregId !in freshMap) {
                        freshMap[vregId] = mf.newVreg(4)
                    }
                }

                // Insert loads before the instruction for each used spill.
                for ((vregId, slotIdx) in usedSpills) {
                    if (vregId !in freshMap) continue
                    val freshReg = freshMap[vregId]!!
                    // Load from stack slot. Offset is 0 for now; frame layout patches it.
                    // We emit: lw freshReg, 0(sp) — with the slot index recorded.
                    // Actually, we emit an addi to get the slot address, then lw.
                    // For simplicity, emit a direct lw with placeholder offset.
                    // The frame finalizer will patch StackSlotInfo.offset, and we use
                    // it here. But since offset isn't known yet, we use slotIdx as a
                    // marker. We'll compute actual offset by reading the slot info.
                    //
                    // For now: emit `lw freshReg, <slotOffset>(sp)` where slotOffset
                    // is currently 0 (to be patched by FrameLayout).
                    newInsts.add(
                        RvInst.Load(MemWidth.WORD, freshReg, phys(RvPhysReg.SP), spillSlotMarkerOffset(slotIdx))
                    )
                }

                // Rewrite the instruction: replace spilled vregs with fresh vregs.
                val rewrittenInst = rewriteSpilledInst(inst, freshMap)
                newInsts.add(rewrittenInst)

                // Insert stores after the instruction for each defined spill.
                for ((vregId, slotIdx) in defSpills) {
                    if (vregId !in freshMap) continue
                    val freshReg = freshMap[vregId]!!
                    newInsts.add(
                        RvInst.Store(MemWidth.WORD, freshReg, phys(RvPhysReg.SP), spillSlotMarkerOffset(slotIdx))
                    )
                }
            }

            block.instructions.clear()
            block.instructions.addAll(newInsts)
        }
    }

    /**
     * Produce a "marker" offset for a spill slot.
     *
     * During spill code insertion the actual frame offset is not yet known.
     * We encode the slot index as a negative marker that FrameLayout will
     * recognise and patch:  marker = -(slotIdx + 1) * 4.
     *
     * This avoids collisions with legitimate zero or positive offsets.
     * FrameLayout will later walk all instructions, find loads/stores to `sp`
     * with these negative offsets, and replace them with the final offset.
     *
     * Alternatively, since StackSlotInfo.offset is resolved later, we just
     * use 0 as a placeholder and let FrameLayout handle it. For simplicity,
     * we use a negative encoding so we can identify spill loads/stores.
     */
    private fun spillSlotMarkerOffset(slotIdx: Int): Int {
        // Use a distinctive negative offset: -(slotIdx + 1) * 0x100
        // This is well outside the normal offset range and easy to identify.
        return -(slotIdx + 1) * 256
    }

    /**
     * Rewrite an instruction, replacing any virtual register whose id is in
     * [freshMap] with the corresponding fresh virtual register.
     *
     * This is a manual operand rewriting that works with virtual registers
     * (unlike [RvInst.mapRegs] which only maps to [RvOperand.PhysReg]).
     */
    private fun rewriteSpilledInst(
        inst: RvInst,
        freshMap: Map<Int, RvOperand.Reg>,
    ): RvInst {
        if (freshMap.isEmpty()) return inst

        fun rw(op: RvOperand): RvOperand = when (op) {
            is RvOperand.Reg -> freshMap[op.id] ?: op
            else -> op
        }

        return when (inst) {
            is RvInst.RType -> inst.copy(
                rd = rw(inst.rd), rs1 = rw(inst.rs1), rs2 = rw(inst.rs2)
            )
            is RvInst.IType -> inst.copy(
                rd = rw(inst.rd), rs1 = rw(inst.rs1)
            )
            is RvInst.Load -> inst.copy(
                rd = rw(inst.rd), base = rw(inst.base)
            )
            is RvInst.Store -> inst.copy(
                rs = rw(inst.rs), base = rw(inst.base)
            )
            is RvInst.Branch -> inst.copy(
                rs1 = rw(inst.rs1), rs2 = rw(inst.rs2)
            )
            is RvInst.Lui -> inst.copy(rd = rw(inst.rd))
            is RvInst.Li -> inst.copy(rd = rw(inst.rd))
            is RvInst.La -> inst.copy(rd = rw(inst.rd))
            is RvInst.Mv -> inst.copy(rd = rw(inst.rd), rs = rw(inst.rs))
            is RvInst.Neg -> inst.copy(rd = rw(inst.rd), rs = rw(inst.rs))
            is RvInst.Not -> inst.copy(rd = rw(inst.rd), rs = rw(inst.rs))
            is RvInst.Seqz -> inst.copy(rd = rw(inst.rd), rs = rw(inst.rs))
            is RvInst.Snez -> inst.copy(rd = rw(inst.rd), rs = rw(inst.rs))
            is RvInst.J -> inst
            is RvInst.Call -> inst
            is RvInst.Ret -> inst
            is RvInst.Comment -> inst
        }
    }
}
