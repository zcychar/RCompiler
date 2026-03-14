package backend.codegen

import backend.codegen.riscv.*

// ============================================================================
//  Liveness Analysis — backward dataflow on machine-level CFG
// ============================================================================
//
//  Standard iterative backward dataflow analysis computing USE/DEF per block,
//  live-in / live-out sets, and per-instruction live sets.
//
//  The analysis operates on virtual registers (RvOperand.Reg) and physical
//  registers (RvOperand.PhysReg) that belong to the ALLOCATABLE set.
//  Reserved physical registers (zero, ra, sp, gp, tp) are excluded — they
//  are managed by the ABI and never participate in graph-coloring allocation.
//
//  Terminology
//  -----------
//  - "RegKey" — a canonical, hashable identifier for a register operand.
//    Virtual registers are keyed by their integer id; physical registers
//    by the RvPhysReg enum value.  Using a sealed class avoids boxing and
//    gives us correct equality.
//
//  - USE[B] — registers read before being written in block B.
//  - DEF[B] — registers written before being read in block B.
//  - liveIn[B]  = USE[B] ∪ (liveOut[B] − DEF[B])
//  - liveOut[B] = ∪ { liveIn[S] | S ∈ successors(B) }
//
//  The analysis also provides:
//  - `forEachInstructionLiveSet(block, callback)` — walks a block backward,
//    maintaining an accurate live set at each program point, and invokes the
//    callback with (instruction, liveSetAfterInstruction).  This is the
//    primary interface used by the interference-graph builder.
//
//  - `interferenceEdges(mf)` — convenience that returns every (vreg, vreg)
//    or (vreg, physreg) interference pair, suitable for directly populating
//    an InterferenceGraph.
// ============================================================================

/**
 * Canonical key identifying a register operand for liveness bookkeeping.
 *
 * We deliberately do NOT use [RvOperand] directly as a hash-map key because
 * [RvOperand.Reg] carries a `width` field that is irrelevant for identity
 * (two references to `v5` with different widths denote the same register).
 */
sealed class RegKey {
    /** Virtual register, identified solely by its integer id. */
    data class Vreg(val id: Int) : RegKey() {
        override fun toString(): String = "v$id"
    }

    /** Allocatable physical register. */
    data class Phys(val reg: RvPhysReg) : RegKey() {
        override fun toString(): String = reg.abiName
    }
}

/** Convert an [RvOperand] to a [RegKey], or `null` if the operand is not a register
 *  that participates in liveness (e.g. immediates, labels, relocations, reserved regs). */
fun RvOperand.toRegKey(): RegKey? = when (this) {
    is RvOperand.Reg -> RegKey.Vreg(id)
    is RvOperand.PhysReg -> if (reg in RESERVED_REGS) null else RegKey.Phys(reg)
    else -> null
}

// ---------------------------------------------------------------------------
//  Block-level dataflow summaries
// ---------------------------------------------------------------------------

/**
 * USE/DEF summary for a single [RvMachineBlock].
 *
 * - [use] = registers read before any write in this block.
 * - [def] = registers written before any read in this block.
 */
data class BlockSummary(
    val use: MutableSet<RegKey> = mutableSetOf(),
    val def: MutableSet<RegKey> = mutableSetOf(),
)

// ---------------------------------------------------------------------------
//  LivenessResult — the main result object
// ---------------------------------------------------------------------------

/**
 * Result of a liveness analysis run on one [RvMachineFunction].
 *
 * Contains per-block live-in and live-out sets and exposes helpers for
 * iterating per-instruction live sets (needed by the interference-graph
 * builder and spill-code inserter).
 */
class LivenessResult(
    val mf: RvMachineFunction,

    /** live-in set for each block (registers live at the *entry* of the block). */
    val liveIn: Map<RvMachineBlock, Set<RegKey>>,

    /** live-out set for each block (registers live at the *exit* of the block). */
    val liveOut: Map<RvMachineBlock, Set<RegKey>>,
) {

    /**
     * Walk [block] backward, maintaining the accurate live set at each
     * program point, and invoke [action] for every instruction.
     *
     * The callback receives:
     * - `inst`     — the current instruction.
     * - `liveAfter` — the set of registers live *after* this instruction executes
     *                 (i.e., live-out of this program point).  This is a **read-only
     *                 snapshot view**; do NOT mutate it.
     *
     * This is the standard algorithm:
     * ```
     *   live = copy(liveOut[block])
     *   for inst in block.instructions.reversed():
     *       action(inst, live)          // live set is the "live-after" of inst
     *       for d in inst.defs():
     *           live -= d
     *       for u in inst.uses():
     *           live += u
     * ```
     *
     * Note: the callback sees `liveAfter` (the set *before* we remove defs and
     * add uses). This matches the convention used for interference: a def `d`
     * interferes with everything live-out at that point (minus `d` itself, for
     * move coalescing).
     */
    inline fun forEachInstruction(
        block: RvMachineBlock,
        action: (inst: RvInst, liveAfter: Set<RegKey>) -> Unit,
    ) {
        val live: MutableSet<RegKey> = (liveOut[block] ?: emptySet()).toMutableSet()

        for (inst in block.instructions.asReversed()) {
            // Snapshot: caller sees the live-after set for this instruction.
            // We pass the mutable set directly for performance — caller must not mutate.
            @Suppress("UNCHECKED_CAST")
            action(inst, live as Set<RegKey>)

            // Remove defs (register is no longer live before this point).
            for (d in inst.defs()) {
                d.toRegKey()?.let { live.remove(it) }
            }
            // Add uses (register must be live before this point).
            for (u in inst.uses()) {
                u.toRegKey()?.let { live.add(it) }
            }
        }
    }

    /**
     * Compute every interference pair across the entire function.
     *
     * Two registers **interfere** if one is defined at a point where the other
     * is live.  Specifically, for each instruction that defines register `d`:
     *
     * - For non-move instructions: `d` interferes with every register in
     *   `liveAfter` (the live set after the instruction) except `d` itself.
     *
     * - For move instructions (`mv d, s`): `d` interferes with every register
     *   in `liveAfter` except `d` itself **and** `s`.  This is the standard
     *   Chaitin/Briggs trick that enables the coalescer to merge `d` and `s`
     *   when they don't otherwise interfere.
     *
     * Returns a set of **unordered** pairs.  Each pair is stored with the
     * smaller key first (by an arbitrary total order) to avoid duplicates.
     */
    fun interferenceEdges(): Set<Pair<RegKey, RegKey>> {
        val edges = mutableSetOf<Pair<RegKey, RegKey>>()

        for (block in mf.blocks) {
            forEachInstruction(block) { inst, liveAfter ->
                val defs = inst.defs().mapNotNull { it.toRegKey() }
                if (defs.isEmpty()) return@forEachInstruction

                // For a move, the source is excluded from interference with the dest.
                val moveSource: RegKey? = if (inst.isMove()) {
                    inst.uses().firstOrNull()?.toRegKey()
                } else null

                for (d in defs) {
                    for (l in liveAfter) {
                        if (l == d) continue
                        if (l == moveSource && defs.size == 1) continue
                        edges.add(orderedPair(d, l))
                    }
                }
            }
        }

        return edges
    }

    companion object {
        /** Produce a canonical ordered pair so (a,b) and (b,a) hash identically. */
        fun orderedPair(a: RegKey, b: RegKey): Pair<RegKey, RegKey> {
            return if (a.hashCode() <= b.hashCode()) Pair(a, b) else Pair(b, a)
        }
    }
}

// ---------------------------------------------------------------------------
//  LivenessAnalysis — the analysis driver
// ---------------------------------------------------------------------------

/**
 * Entry point: run liveness analysis on a [RvMachineFunction] and return
 * a [LivenessResult].
 *
 * The function's CFG edges ([RvMachineBlock.predecessors] / [successors])
 * must be up-to-date before calling this. If in doubt, call
 * [RvMachineFunction.rebuildCfgEdges] first.
 */
object LivenessAnalysis {

    fun analyze(mf: RvMachineFunction): LivenessResult {
        // 1. Compute USE/DEF summaries for every block.
        val summaries = HashMap<RvMachineBlock, BlockSummary>(mf.blocks.size * 2)
        for (block in mf.blocks) {
            summaries[block] = computeBlockSummary(block)
        }

        // 2. Compute reverse-postorder traversal for efficient iteration.
        val rpo = reversePostorder(mf)

        // 3. Iterative dataflow: compute liveIn / liveOut until convergence.
        val liveIn = HashMap<RvMachineBlock, MutableSet<RegKey>>(mf.blocks.size * 2)
        val liveOut = HashMap<RvMachineBlock, MutableSet<RegKey>>(mf.blocks.size * 2)
        for (block in mf.blocks) {
            liveIn[block] = mutableSetOf()
            liveOut[block] = mutableSetOf()
        }

        var changed = true
        while (changed) {
            changed = false
            // For backward analysis, iterate in reverse of RPO (i.e., postorder).
            for (block in rpo.asReversed()) {
                val summary = summaries[block]!!

                // liveOut[B] = ∪ { liveIn[S] | S ∈ successors(B) }
                val out = liveOut[block]!!
                for (succ in block.successors) {
                    val succIn = liveIn[succ]!!
                    for (key in succIn) {
                        if (out.add(key)) changed = true
                    }
                }

                // liveIn[B] = USE[B] ∪ (liveOut[B] − DEF[B])
                val inSet = liveIn[block]!!
                // Start with USE[B]
                for (u in summary.use) {
                    if (inSet.add(u)) changed = true
                }
                // Add liveOut[B] − DEF[B]
                for (o in out) {
                    if (o !in summary.def) {
                        if (inSet.add(o)) changed = true
                    }
                }
            }
        }

        // 4. Freeze into immutable maps.
        @Suppress("UNCHECKED_CAST")
        return LivenessResult(
            mf = mf,
            liveIn = liveIn as Map<RvMachineBlock, Set<RegKey>>,
            liveOut = liveOut as Map<RvMachineBlock, Set<RegKey>>,
        )
    }

    // ------------------------------------------------------------------
    //  Block-local USE/DEF computation
    // ------------------------------------------------------------------

    /**
     * Compute the USE and DEF sets for a single block by scanning instructions
     * in forward order.
     *
     * USE = registers read before any write in the block.
     * DEF = registers written before any read in the block.
     */
    private fun computeBlockSummary(block: RvMachineBlock): BlockSummary {
        val use = mutableSetOf<RegKey>()
        val def = mutableSetOf<RegKey>()

        for (inst in block.instructions) {
            // Uses: if not already defined locally, this is an upward-exposed use.
            for (u in inst.uses()) {
                val key = u.toRegKey() ?: continue
                if (key !in def) {
                    use.add(key)
                }
            }
            // Defs: add to DEF (first write kills any later upward exposure).
            for (d in inst.defs()) {
                val key = d.toRegKey() ?: continue
                def.add(key)
            }
        }

        return BlockSummary(use, def)
    }

    // ------------------------------------------------------------------
    //  Reverse-postorder computation
    // ------------------------------------------------------------------

    /**
     * Compute a reverse-postorder traversal of the machine CFG starting from
     * the entry block. Unreachable blocks are appended at the end.
     */
    private fun reversePostorder(mf: RvMachineFunction): List<RvMachineBlock> {
        val visited = mutableSetOf<RvMachineBlock>()
        val postorder = mutableListOf<RvMachineBlock>()

        fun dfs(block: RvMachineBlock) {
            if (!visited.add(block)) return
            for (succ in block.successors) {
                dfs(succ)
            }
            postorder.add(block)
        }

        if (mf.blocks.isNotEmpty()) {
            dfs(mf.entryBlock())
        }

        // Append any unreachable blocks so they still get initialized.
        for (block in mf.blocks) {
            if (block !in visited) {
                postorder.add(block)
            }
        }

        return postorder.reversed()
    }
}
