package backend.codegen

// Allocates physical RISC-V registers with graph coloring and spill retry.

import backend.codegen.riscv.*

data class RegAllocResult(
    val coloring: Map<Int, RvOperand.PhysReg>,
    val usedCallee: Set<RvPhysReg>,
)

class GraphColorRegAlloc {

    companion object {

        private const val K = NUM_ALLOCATABLE

        private const val MAX_ROUNDS = 50
    }

    fun allocate(mf: RvMachineFunction) {
        var round = 0
        while (round < MAX_ROUNDS) {
            round++

            mf.rebuildCfgEdges()

            val liveness = LivenessAnalysis.analyze(mf)
            val ig = InterferenceGraph.build(mf, liveness)

            val ctx = AllocContext(mf, ig)
            ctx.makeWorklists()
            ctx.iterateUntilEmpty()

            val spilledKeys = ctx.select()

            if (spilledKeys.isEmpty()) {

                val result = ctx.buildResult()
                rewriteFunction(mf, result)
                return
            }

            insertSpillCode(mf, spilledKeys)
        }

        error("Register allocator failed to converge after $MAX_ROUNDS rounds")
    }

    private inner class AllocContext(
        val mf: RvMachineFunction,
        val ig: InterferenceGraph,
    ) {

        val precolored = mutableSetOf<RegKey>()
        val simplifyWorklist = mutableListOf<RegKey>()
        val freezeWorklist = mutableListOf<RegKey>()
        val spillWorklist = mutableListOf<RegKey>()
        val spilledNodes = mutableSetOf<RegKey>()
        val coalescedNodes = mutableSetOf<RegKey>()
        val coloredNodes = mutableSetOf<RegKey>()
        val selectStack = ArrayDeque<RegKey>()

        val coalescedMoves = mutableSetOf<MoveEdge>()
        val constrainedMoves = mutableSetOf<MoveEdge>()
        val frozenMoves = mutableSetOf<MoveEdge>()
        val worklistMoves = mutableSetOf<MoveEdge>()
        val activeMoves = mutableSetOf<MoveEdge>()

        val removedFromGraph = mutableSetOf<RegKey>()

        fun nodeMoves(n: RegKey): Set<MoveEdge> {
            val moves = ig.moveList[n] ?: return emptySet()
            return moves.filter { it in activeMoves || it in worklistMoves }.toSet()
        }

        fun moveRelated(n: RegKey): Boolean = nodeMoves(n).isNotEmpty()

        fun adjacent(n: RegKey): Set<RegKey> {
            return ig.neighbors(n).filterTo(mutableSetOf()) {
                it !in removedFromGraph && it !in coalescedNodes
            }
        }

        fun activeDegree(n: RegKey): Int {
            return adjacent(n).size
        }

        fun makeWorklists() {

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

                if (ig.degree(n) >= K) {
                    spillWorklist.add(n)
                } else if (moveRelated(n)) {
                    freezeWorklist.add(n)
                } else {
                    simplifyWorklist.add(n)
                }
            }
        }

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

        private fun simplify() {
            val n = simplifyWorklist.removeLastOrNull() ?: return
            selectStack.addLast(n)
            removedFromGraph.add(n)

            for (adj in ig.neighbors(n)) {
                if (adj in removedFromGraph || adj in coalescedNodes) continue
                decrementDegree(adj)
            }
        }

        private fun decrementDegree(m: RegKey) {
            if (m is RegKey.Phys) return

            val oldDeg = ig.degree[m] ?: return
            ig.degree[m] = oldDeg - 1
            val newDeg = oldDeg - 1

            if (oldDeg == K) {

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

        private fun enableMoves(n: RegKey) {
            for (m in nodeMoves(n)) {
                if (m in activeMoves) {
                    activeMoves.remove(m)
                    worklistMoves.add(m)
                }
            }
        }

        private fun coalesce() {
            val m = worklistMoves.firstOrNull() ?: return
            worklistMoves.remove(m)

            val x = ig.getAlias(m.dst)
            val y = ig.getAlias(m.src)

            val (u, v) = if (y is RegKey.Phys) Pair(y, x) else Pair(x, y)

            if (u == v) {

                coalescedMoves.add(m)
                addWorklistIfReady(u)
            } else if (v is RegKey.Phys || LivenessResult.orderedPair(u, v) in ig.adjSet) {

                constrainedMoves.add(m)
                addWorklistIfReady(u)
                addWorklistIfReady(v)
            } else if (canCoalesce(u, v)) {

                coalescedMoves.add(m)
                combine(u, v)
                addWorklistIfReady(u)
            } else {

                activeMoves.add(m)
            }
        }

        private fun canCoalesce(u: RegKey, v: RegKey): Boolean {
            if (u is RegKey.Phys) {

                return adjacent(v).all { t ->
                    ig.degree(t) < K ||
                    t is RegKey.Phys ||
                    LivenessResult.orderedPair(t, u) in ig.adjSet
                }
            } else {

                val combinedAdj = (adjacent(u) + adjacent(v)) - setOf(u, v)
                val highDegreeCount = combinedAdj.count { t ->
                    ig.degree(t) >= K
                }
                return highDegreeCount < K
            }
        }

        private fun combine(u: RegKey, v: RegKey) {
            if (v in freezeWorklist) {
                freezeWorklist.remove(v)
            } else {
                spillWorklist.remove(v)
            }
            coalescedNodes.add(v)
            ig.alias[v] = u

            val vMoves = ig.moveList[v] ?: mutableSetOf()
            ig.moveList.getOrPut(u) { mutableSetOf() }.addAll(vMoves)

            for (t in adjacent(v)) {
                ig.addEdge(t, u)

                decrementDegree(t)
            }

            if (ig.degree(u) >= K && u in freezeWorklist) {
                freezeWorklist.remove(u)
                spillWorklist.add(u)
            }
        }

        private fun addWorklistIfReady(u: RegKey) {
            if (u is RegKey.Phys) return
            if (moveRelated(u)) return
            if (ig.degree(u) >= K) return
            freezeWorklist.remove(u)
            if (u !in simplifyWorklist) {
                simplifyWorklist.add(u)
            }
        }

        private fun freeze() {

            val u = freezeWorklist.removeLastOrNull() ?: return
            simplifyWorklist.add(u)
            freezeMoves(u)
        }

        private fun freezeMoves(u: RegKey) {
            for (m in nodeMoves(u)) {
                val x = ig.getAlias(m.dst)
                val y = ig.getAlias(m.src)
                val v = if (y == ig.getAlias(u)) x else y

                activeMoves.remove(m)
                worklistMoves.remove(m)
                frozenMoves.add(m)

                if (v is RegKey.Vreg && !moveRelated(v) && ig.degree(v) < K) {
                    freezeWorklist.remove(v)
                    if (v !in simplifyWorklist) {
                        simplifyWorklist.add(v)
                    }
                }
            }
        }

        private fun selectSpill() {

            val candidate = spillWorklist.maxByOrNull { ig.degree(it) } ?: return
            spillWorklist.remove(candidate)
            simplifyWorklist.add(candidate)
            freezeMoves(candidate)
        }

        fun select(): Set<RegKey> {
            val actualSpills = mutableSetOf<RegKey>()

            while (selectStack.isNotEmpty()) {
                val n = selectStack.removeLast()

                val usedColors = mutableSetOf<RvPhysReg>()
                for (adj in ig.neighbors(n)) {
                    val rep = ig.getAlias(adj)
                    val c = ig.color[rep]
                    if (c != null) {
                        usedColors.add(c)
                    }
                }

                val available = pickColor(usedColors, n)
                if (available != null) {
                    ig.color[n] = available
                    coloredNodes.add(n)
                } else {
                    actualSpills.add(n)
                    spilledNodes.add(n)
                }
            }

            for (n in coalescedNodes) {
                val rep = ig.getAlias(n)
                val c = ig.color[rep]
                if (c != null) {
                    ig.color[n] = c
                }
            }

            return actualSpills
        }

        private fun pickColor(usedColors: Set<RvPhysReg>, node: RegKey): RvPhysReg? {

            for (reg in ALLOCATABLE_REGS) {
                if (reg !in usedColors) {
                    return reg
                }
            }
            return null
        }

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

    private fun rewriteFunction(mf: RvMachineFunction, result: RegAllocResult) {
        val mapping = result.coloring
        mf.usedCalleeSaved.addAll(result.usedCallee)

        for (block in mf.blocks) {
            val newInsts = mutableListOf<RvInst>()
            for (inst in block.instructions) {
                val rewritten = inst.mapRegs(mapping)

                if (rewritten is RvInst.Mv && rewritten.rd == rewritten.rs) {
                    continue
                }
                newInsts.add(rewritten)
            }
            block.instructions.clear()
            block.instructions.addAll(newInsts)
        }
    }

    private fun insertSpillCode(mf: RvMachineFunction, spilledKeys: Set<RegKey>) {

        val spillSlots = mutableMapOf<Int, Int>()
        val spillWidths = mutableMapOf<Int, Int>()
        val vregWidths = collectVregWidths(mf)

        for (key in spilledKeys) {
            if (key !is RegKey.Vreg) continue
            val width = vregWidths[key.id] ?: 4
            val slotIdx = mf.allocateStackSlot("spill.v${key.id}", width, width)
            spillSlots[key.id] = slotIdx
            spillWidths[key.id] = width
        }

        if (spillSlots.isEmpty()) return

        for (block in mf.blocks) {
            val newInsts = mutableListOf<RvInst>()

            for (inst in block.instructions) {

                val usedSpills = mutableListOf<Pair<Int, Int>>()
                val defSpills = mutableListOf<Pair<Int, Int>>()

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

                val freshMap = mutableMapOf<Int, RvOperand.Reg>()

                for ((vregId, _) in usedSpills + defSpills) {
                    if (vregId !in freshMap) {
                        freshMap[vregId] = mf.newVreg(spillWidths[vregId] ?: 4)
                    }
                }

                for ((vregId, slotIdx) in usedSpills) {
                    if (vregId !in freshMap) continue
                    val freshReg = freshMap[vregId]!!

                    newInsts.add(
                        RvInst.Load(
                            memWidthForBytes(freshReg.width),
                            freshReg,
                            phys(RvPhysReg.SP),
                            spillSlotMarkerOffset(slotIdx)
                        )
                    )
                }

                val rewrittenInst = rewriteSpilledInst(inst, freshMap)
                newInsts.add(rewrittenInst)

                for ((vregId, slotIdx) in defSpills) {
                    if (vregId !in freshMap) continue
                    val freshReg = freshMap[vregId]!!
                    newInsts.add(
                        RvInst.Store(
                            memWidthForBytes(freshReg.width),
                            freshReg,
                            phys(RvPhysReg.SP),
                            spillSlotMarkerOffset(slotIdx)
                        )
                    )
                }
            }

            block.instructions.clear()
            block.instructions.addAll(newInsts)
        }
    }

    private fun collectVregWidths(mf: RvMachineFunction): Map<Int, Int> {
        val widths = mf.vregWidths.toMutableMap()

        fun record(op: RvOperand) {
            if (op is RvOperand.Reg) {
                val current = widths[op.id]
                if (current == null || op.width > current) {
                    widths[op.id] = op.width
                }
            }
        }

        for (block in mf.blocks) {
            for (inst in block.instructions) {
                inst.defs().forEach(::record)
                inst.uses().forEach(::record)
            }
        }

        return widths
    }

    private fun spillSlotMarkerOffset(slotIdx: Int): Int {

        return -(slotIdx + 1) * 256
    }

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
