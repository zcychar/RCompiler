package backend.codegen

// Computes machine-register liveness for register allocation.

import backend.codegen.riscv.*

sealed class RegKey {

    data class Vreg(val id: Int) : RegKey() {
        override fun toString(): String = "v$id"
    }

    data class Phys(val reg: RvPhysReg) : RegKey() {
        override fun toString(): String = reg.abiName
    }
}

fun RvOperand.toRegKey(): RegKey? = when (this) {
    is RvOperand.Reg -> RegKey.Vreg(id)
    is RvOperand.PhysReg -> if (reg in RESERVED_REGS) null else RegKey.Phys(reg)
    else -> null
}

data class BlockSummary(
    val use: MutableSet<RegKey> = mutableSetOf(),
    val def: MutableSet<RegKey> = mutableSetOf(),
)

class LivenessResult(
    val mf: RvMachineFunction,

    val liveIn: Map<RvMachineBlock, Set<RegKey>>,

    val liveOut: Map<RvMachineBlock, Set<RegKey>>,
) {

    inline fun forEachInstruction(
        block: RvMachineBlock,
        action: (inst: RvInst, liveAfter: Set<RegKey>) -> Unit,
    ) {
        val live: MutableSet<RegKey> = (liveOut[block] ?: emptySet()).toMutableSet()

        for (inst in block.instructions.asReversed()) {

            @Suppress("UNCHECKED_CAST")
            action(inst, live as Set<RegKey>)

            for (d in inst.defs()) {
                d.toRegKey()?.let { live.remove(it) }
            }

            for (u in inst.uses()) {
                u.toRegKey()?.let { live.add(it) }
            }
        }
    }

    fun interferenceEdges(): Set<Pair<RegKey, RegKey>> {
        val edges = mutableSetOf<Pair<RegKey, RegKey>>()

        for (block in mf.blocks) {
            forEachInstruction(block) { inst, liveAfter ->
                val defs = inst.defs().mapNotNull { it.toRegKey() }
                if (defs.isEmpty()) return@forEachInstruction

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

        fun orderedPair(a: RegKey, b: RegKey): Pair<RegKey, RegKey> {
            return if (a.hashCode() <= b.hashCode()) Pair(a, b) else Pair(b, a)
        }
    }
}

object LivenessAnalysis {

    fun analyze(mf: RvMachineFunction): LivenessResult {

        val summaries = HashMap<RvMachineBlock, BlockSummary>(mf.blocks.size * 2)
        for (block in mf.blocks) {
            summaries[block] = computeBlockSummary(block)
        }

        val rpo = reversePostorder(mf)

        val liveIn = HashMap<RvMachineBlock, MutableSet<RegKey>>(mf.blocks.size * 2)
        val liveOut = HashMap<RvMachineBlock, MutableSet<RegKey>>(mf.blocks.size * 2)
        for (block in mf.blocks) {
            liveIn[block] = mutableSetOf()
            liveOut[block] = mutableSetOf()
        }

        var changed = true
        while (changed) {
            changed = false

            for (block in rpo.asReversed()) {
                val summary = summaries[block]!!

                val out = liveOut[block]!!
                for (succ in block.successors) {
                    val succIn = liveIn[succ]!!
                    for (key in succIn) {
                        if (out.add(key)) changed = true
                    }
                }

                val inSet = liveIn[block]!!

                for (u in summary.use) {
                    if (inSet.add(u)) changed = true
                }

                for (o in out) {
                    if (o !in summary.def) {
                        if (inSet.add(o)) changed = true
                    }
                }
            }
        }

        @Suppress("UNCHECKED_CAST")
        return LivenessResult(
            mf = mf,
            liveIn = liveIn as Map<RvMachineBlock, Set<RegKey>>,
            liveOut = liveOut as Map<RvMachineBlock, Set<RegKey>>,
        )
    }

    private fun computeBlockSummary(block: RvMachineBlock): BlockSummary {
        val use = mutableSetOf<RegKey>()
        val def = mutableSetOf<RegKey>()

        for (inst in block.instructions) {

            for (u in inst.uses()) {
                val key = u.toRegKey() ?: continue
                if (key !in def) {
                    use.add(key)
                }
            }

            for (d in inst.defs()) {
                val key = d.toRegKey() ?: continue
                def.add(key)
            }
        }

        return BlockSummary(use, def)
    }

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

        for (block in mf.blocks) {
            if (block !in visited) {
                postorder.add(block)
            }
        }

        return postorder.reversed()
    }
}
