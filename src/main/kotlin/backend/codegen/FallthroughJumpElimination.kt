package backend.codegen

// Removes unconditional jumps to the immediately following block.

import backend.codegen.riscv.RvInst
import backend.codegen.riscv.RvMachineFunction

object FallthroughJumpElimination {
    fun run(mf: RvMachineFunction): Int {
        var removed = 0
        for (index in 0 until mf.blocks.lastIndex) {
            val block = mf.blocks[index]
            val nextLabel = mf.blocks[index + 1].label
            val last = block.instructions.lastOrNull() as? RvInst.J ?: continue
            if (last.target == nextLabel) {
                block.instructions.removeAt(block.instructions.lastIndex)
                removed++
            }
        }
        return removed
    }
}
