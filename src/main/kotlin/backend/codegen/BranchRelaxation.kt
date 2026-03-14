package backend.codegen

import backend.codegen.riscv.*

/**
 * Branch Relaxation Pass
 * ======================
 *
 * RISC-V conditional branch instructions (`beq`, `bne`, `blt`, `bge`, `bltu`,
 * `bgeu`) use B-type encoding with a 13-bit signed immediate (12 bits + implicit
 * LSB of 0), giving a maximum range of **±4096 bytes** (±1024 instructions at
 * 4 bytes each).
 *
 * When a function is large enough that a conditional branch target exceeds this
 * range, the assembler (or REIMU linker) will reject the program.
 *
 * This pass rewrites out-of-range conditional branches into an inverted-branch +
 * unconditional-jump sequence:
 *
 * **Before:**
 * ```
 *     bne  rs1, rs2, far_label
 * ```
 *
 * **After:**
 * ```
 *     beq  rs1, rs2, .Lrelax_N      # inverted condition, short forward branch
 *     j    far_label                  # J-type: ±1 MiB range
 *   .Lrelax_N:
 * ```
 *
 * The pass operates on finalized [RvMachineFunction]s (after register allocation
 * and frame layout) and must run **before** assembly emission.
 *
 * Algorithm:
 * 1. Flatten all blocks into a linear instruction stream, recording each block's
 *    starting byte offset.
 * 2. For every conditional branch, compute the distance to its target label.
 * 3. If the distance exceeds the safe threshold, replace the branch in-place
 *    with the relaxed sequence (inverted branch + jump), inserting a new
 *    trampoline block immediately after the current block.
 * 4. Since relaxation can shift offsets (each relaxation adds ~1 instruction
 *    plus a label), iterate until no more relaxations are needed (fixpoint).
 *
 * In practice, convergence happens in 1–2 iterations.
 */
object BranchRelaxation {

    /**
     * Maximum branch offset in bytes for B-type instructions.
     * The encoding supports ±4096, but we use a slightly conservative limit
     * to account for alignment and potential off-by-one issues.
     */
    private const val MAX_BRANCH_OFFSET = 4000  // conservative vs theoretical ±4096

    /** Counter for generating unique relaxation trampoline labels. */
    private var relaxCounter = 0

    private fun freshRelaxLabel(): String = ".Lrelax_${relaxCounter++}"

    /**
     * Invert a branch condition.
     *
     * | Original | Inverted |
     * |----------|----------|
     * | BEQ      | BNE      |
     * | BNE      | BEQ      |
     * | BLT      | BGE      |
     * | BGE      | BLT      |
     * | BLTU     | BGEU     |
     * | BGEU     | BLTU     |
     */
    private fun invertCondition(cond: RvBranchCond): RvBranchCond = when (cond) {
        RvBranchCond.BEQ  -> RvBranchCond.BNE
        RvBranchCond.BNE  -> RvBranchCond.BEQ
        RvBranchCond.BLT  -> RvBranchCond.BGE
        RvBranchCond.BGE  -> RvBranchCond.BLT
        RvBranchCond.BLTU -> RvBranchCond.BGEU
        RvBranchCond.BGEU -> RvBranchCond.BLTU
    }

    /**
     * Run branch relaxation on a single machine function.
     *
     * Iterates until no further relaxations are required (fixpoint).
     */
    fun relax(mf: RvMachineFunction) {
        // Iterate to fixpoint — relaxation can shift offsets enough to push
        // previously-in-range branches out of range.
        var changed = true
        var iterations = 0
        val maxIterations = 20  // safety bound

        while (changed && iterations < maxIterations) {
            changed = relaxOnce(mf)
            iterations++
        }
    }

    /**
     * Perform a single relaxation pass over the function.
     *
     * @return `true` if at least one branch was relaxed (offsets changed).
     */
    private fun relaxOnce(mf: RvMachineFunction): Boolean {
        // Step 1: Compute byte offset of each block and each label.
        val blockOffsets = computeBlockOffsets(mf)
        val labelOffsets = mutableMapOf<String, Int>()
        for (block in mf.blocks) {
            labelOffsets[block.label] = blockOffsets[block]!!
        }

        // Step 2: Scan all branches and find those that are out of range.
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

        // Step 3: Apply relaxations.
        // Process in reverse order within each block so that indices remain valid.
        // Group by block first.
        val byBlock = toRelax.groupBy { it.block }

        for ((block, targets) in byBlock) {
            // Process in reverse instruction-index order to keep indices stable.
            for (target in targets.sortedByDescending { it.instIndex }) {
                relaxBranch(mf, block, target.instIndex, target.branch)
            }
        }

        return true
    }

    /**
     * Replace a conditional branch at [instIndex] in [block] with an
     * inverted-branch-over-jump sequence.
     *
     * The original:
     * ```
     *     b<cond>  rs1, rs2, far_label
     *     <next instruction>
     * ```
     *
     * Becomes:
     * ```
     *     b<!cond> rs1, rs2, .Lrelax_N
     *     j        far_label
     *     <next instruction>
     * ```
     *
     * And a new trampoline block `.Lrelax_N:` is inserted immediately after
     * [block] in the function's block list, containing just a fallthrough
     * (i.e., the instructions that were after the branch stay in [block],
     * and the trampoline label marks the skip target).
     *
     * Actually, since we're operating on a flat list of instructions within
     * a block, the simplest approach is:
     * - Replace the branch with the inverted branch targeting a fresh label
     * - Insert a `j far_label` right after it
     * - Split the remaining instructions into a new trampoline block with
     *   the fresh label
     *
     * But that would require splitting blocks which is complex. A simpler
     * approach: just replace the single branch instruction with two instructions
     * (inverted branch to skip label + jump to original target), and insert a
     * trampoline block with the skip label right after this block in the
     * function's block list. The trampoline block is empty and falls through
     * to whatever comes next.
     *
     * Even simpler: since the assembly is emitted linearly, we can just
     * replace the branch with:
     *   1. inverted branch to `.Lrelax_N` (which is 2 instructions ahead)
     *   2. `j far_label`
     * And insert a pseudo-block (or just use inline label handling).
     *
     * For maximum simplicity with the existing block structure, we:
     * - Remove the branch instruction
     * - Insert: inverted branch to a fresh label, then j to original target
     * - Create a new block with the fresh label and insert it right after the
     *   current block. The new block contains any instructions that were after
     *   the branch in the original block.
     */
    private fun relaxBranch(
        mf: RvMachineFunction,
        block: RvMachineBlock,
        instIndex: Int,
        branch: RvInst.Branch,
    ) {
        val skipLabel = freshRelaxLabel()
        val invertedCond = invertCondition(branch.cond)

        // The branch instruction might not be the last instruction in the block
        // (e.g., there could be a following `j` for the fallthrough).
        // We need to handle this carefully.

        // Remove the original branch.
        block.instructions.removeAt(instIndex)

        // Insert: inverted branch to skip label (jumps over the next `j` instruction).
        val invertedBranch = RvInst.Branch(invertedCond, branch.rs1, branch.rs2, skipLabel)
        block.instructions.add(instIndex, invertedBranch)

        // Insert: unconditional jump to the original far target.
        val jump = RvInst.J(branch.target)
        block.instructions.add(instIndex + 1, jump)

        // Now we need the skip label to exist. We split the block: everything
        // after the jump goes into a new trampoline block with the skip label.
        val remainingInsts = mutableListOf<RvInst>()
        while (block.instructions.size > instIndex + 2) {
            remainingInsts.add(block.instructions.removeAt(instIndex + 2))
        }

        // Create the trampoline block and insert it right after the current block.
        val trampolineBlock = RvMachineBlock(skipLabel)
        trampolineBlock.instructions.addAll(remainingInsts)

        val blockIndex = mf.blocks.indexOf(block)
        mf.blocks.add(blockIndex + 1, trampolineBlock)
    }

    /**
     * Compute the byte offset of each block's first instruction, assuming
     * blocks are laid out linearly in order and each instruction is 4 bytes.
     *
     * Block labels themselves occupy 0 bytes (they are just markers).
     * `li` pseudo-instructions may expand to 1 or 2 real instructions
     * (lui + addi), but for offset estimation we conservatively count each
     * [RvInst] as one instruction (4 bytes) except for `Li` which we count
     * as 2 instructions (8 bytes) to be safe.
     */
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

    /**
     * Estimate the size of an instruction in bytes.
     *
     * Most instructions are 4 bytes. `li` may expand to `lui + addi` (8 bytes)
     * for values that don't fit in 12 bits. `la` similarly expands.
     * We use conservative (larger) estimates to avoid under-counting.
     */
    private fun instructionSize(inst: RvInst): Int = when (inst) {
        is RvInst.Li -> 8         // may expand to lui + addi
        is RvInst.La -> 8         // auipc + addi
        is RvInst.Comment -> 0    // no machine code
        is RvInst.Call -> 8       // may expand to auipc + jalr for far calls
        else -> 4
    }
}
