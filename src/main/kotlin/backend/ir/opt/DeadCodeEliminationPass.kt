package backend.ir.opt

// Removes unused side-effect-free IR instructions.

import backend.ir.IrFunction
import backend.ir.IrModule

class DeadCodeEliminationPass : FunctionPass {
  override val name: String = "dce"

  override fun run(module: IrModule, function: IrFunction) {
    var changed = true
    while (changed) {
      changed = false
      val used = collectUsedLocalNames(function)
      function.blocks.forEach { block ->
        val before = block.instructions.size
        block.instructions.removeAll { instruction ->
          val definedName = instruction.definedName()
          isRemovableInstruction(instruction) && (definedName == null || definedName !in used)
        }
        if (block.instructions.size != before) changed = true
      }
      changed = removeUnreachableBlocks(function) || changed
    }
  }
}
