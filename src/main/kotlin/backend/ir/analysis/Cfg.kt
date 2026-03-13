 package backend.ir.analysis

import backend.ir.IrBranch
import backend.ir.IrFunction
import backend.ir.IrJump

data class Cfg(
  val blockOrder: List<String>,
  val entry: String,
  val successors: Map<String, Set<String>>,
  val predecessors: Map<String, Set<String>>,
)

fun buildCfg(function: IrFunction): Cfg {
  val blockOrder = function.blocks.map { it.label }
  val entry = function.blocks.firstOrNull()?.label ?: ""
  if (entry.isEmpty()) {
    return Cfg(
      blockOrder = emptyList(),
      entry = "",
      successors = emptyMap(),
      predecessors = emptyMap(),
    )
  }

  val successors = linkedMapOf<String, Set<String>>()
  val predecessors = linkedMapOf<String, MutableSet<String>>()
  blockOrder.forEach { label ->
    predecessors[label] = linkedSetOf()
  }

  function.blocks.forEach { block ->
    val succ = when (val term = block.terminator) {
      is IrBranch -> linkedSetOf(term.trueTarget, term.falseTarget)
      is IrJump -> linkedSetOf(term.target)
      else -> linkedSetOf()
    }.filterTo(linkedSetOf()) { target -> predecessors.containsKey(target) }

    successors[block.label] = succ
    succ.forEach { target ->
      predecessors.getValue(target).add(block.label)
    }
  }

  return Cfg(
    blockOrder = blockOrder,
    entry = entry,
    successors = successors,
    predecessors = predecessors.mapValues { it.value.toSet() },
  )
}
