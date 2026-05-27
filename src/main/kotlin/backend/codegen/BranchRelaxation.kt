package backend.codegen

// Rewrites far conditional branches into short branch plus jump sequences.

import backend.codegen.riscv.*

object BranchRelaxation {

    private const val MAX_BRANCH_OFFSET = 4000

    private var relaxCounter = 0

    private fun freshRelaxLabel(): String = ".Lrelax_${relaxCounter++}"

    private fun invertCondition(cond: RvBranchCond): RvBranchCond = when (cond) {
        RvBranchCond.BEQ  -> RvBranchCond.BNE
        RvBranchCond.BNE  -> RvBranchCond.BEQ
        RvBranchCond.BLT  -> RvBranchCond.BGE
        RvBranchCond.BGE  -> RvBranchCond.BLT
        RvBranchCond.BLTU -> RvBranchCond.BGEU
        RvBranchCond.BGEU -> RvBranchCond.BLTU
    }

    fun relax(mf: RvMachineFunction) {

        var changed = true
        var iterations = 0
        val maxIterations = 20

        while (changed && iterations < maxIterations) {
            changed = relaxOnce(mf)
            iterations++
        }
    }

    private fun relaxOnce(mf: RvMachineFunction): Boolean {

        val blockOffsets = computeBlockOffsets(mf)
        val labelOffsets = mutableMapOf<String, Int>()
        for (block in mf.blocks) {
            labelOffsets[block.label] = blockOffsets[block]!!
        }

        data class RelaxTarget(
            val block: RvMachineBlock,
            val instIndex: Int,
            val branch: RvInst.Branch,
            val instOffset: Int,
        )

        val toRelax = mutableListOf<RelaxTarget>()

        for (block in mf.blocks) {
            var offset = blockOffsets[block]!!
            for ((idx, inst) in block.instructions.withIndex()) {
                if (inst is RvInst.Branch) {
                    val targetOffset = labelOffsets[inst.target]
                    if (targetOffset != null) {
                        val distance = targetOffset - offset
                        if (distance < -MAX_BRANCH_OFFSET || distance > MAX_BRANCH_OFFSET) {
                            toRelax.add(RelaxTarget(block, idx, inst, offset))
                        }
                    }
                }
                offset += instructionSize(inst)
            }
        }

        if (toRelax.isEmpty()) return false

        val byBlock = toRelax.groupBy { it.block }

        for ((block, targets) in byBlock) {

            for (target in targets.sortedByDescending { it.instIndex }) {
                relaxBranch(mf, block, target.instIndex, target.branch)
            }
        }

        return true
    }

    private fun relaxBranch(
        mf: RvMachineFunction,
        block: RvMachineBlock,
        instIndex: Int,
        branch: RvInst.Branch,
    ) {
        val skipLabel = freshRelaxLabel()
        val invertedCond = invertCondition(branch.cond)

        block.instructions.removeAt(instIndex)

        val invertedBranch = RvInst.Branch(invertedCond, branch.rs1, branch.rs2, skipLabel)
        block.instructions.add(instIndex, invertedBranch)

        val jump = RvInst.J(branch.target)
        block.instructions.add(instIndex + 1, jump)

        val remainingInsts = mutableListOf<RvInst>()
        while (block.instructions.size > instIndex + 2) {
            remainingInsts.add(block.instructions.removeAt(instIndex + 2))
        }

        val trampolineBlock = RvMachineBlock(skipLabel)
        trampolineBlock.instructions.addAll(remainingInsts)

        val blockIndex = mf.blocks.indexOf(block)
        mf.blocks.add(blockIndex + 1, trampolineBlock)
    }

    private fun computeBlockOffsets(mf: RvMachineFunction): Map<RvMachineBlock, Int> {
        val offsets = mutableMapOf<RvMachineBlock, Int>()
        var currentOffset = 0

        for (block in mf.blocks) {
            offsets[block] = currentOffset
            for (inst in block.instructions) {
                currentOffset += instructionSize(inst)
            }
        }

        return offsets
    }

    private fun instructionSize(inst: RvInst): Int = when (inst) {
        is RvInst.Li -> 8
        is RvInst.La -> 8
        is RvInst.Comment -> 0
        is RvInst.Call -> 8
        else -> 4
    }
}
