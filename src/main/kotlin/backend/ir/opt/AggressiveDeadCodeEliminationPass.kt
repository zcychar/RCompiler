package backend.ir.opt

import backend.ir.*

class AggressiveDeadCodeEliminationPass : FunctionPass {
  override val name: String = "adce"

  override fun run(module: IrModule, function: IrFunction) {
    if (function.blocks.isEmpty()) return
    removeUnreachableBlocks(function)

    val definitions = linkedMapOf<String, IrInstruction>()
    function.blocks.forEach { block ->
      block.instructions.forEach { instruction ->
        instruction.definedName()?.let { definitions[it] = instruction }
      }
    }

    val live = linkedSetOf<IrInstruction>()
    val worklist = ArrayDeque<IrInstruction>()

    fun mark(instruction: IrInstruction) {
      if (live.add(instruction)) {
        worklist.addLast(instruction)
      }
    }

    function.blocks.forEach { block ->
      block.instructions.forEach { instruction ->
        if (isSideEffectingInstruction(instruction)) {
          mark(instruction)
        }
      }
      block.terminator?.let(::mark)
    }

    while (worklist.isNotEmpty()) {
      val instruction = worklist.removeFirst()
      instructionUses(instruction).forEach { value ->
        val def = localName(value)?.let { definitions[it] }
        if (def != null) mark(def)
      }
    }

    function.blocks.forEach { block ->
      block.instructions.removeAll { instruction ->
        isRemovableInstruction(instruction) && instruction !in live
      }
    }
  }
}
