package backend.ir.opt

import backend.ir.IrAlloca
import backend.ir.IrBasicBlock
import backend.ir.IrBranch
import backend.ir.IrFunction
import backend.ir.IrJump
import backend.ir.IrLoad
import backend.ir.IrLocal
import backend.ir.IrModule
import backend.ir.IrPhi
import backend.ir.IrPointer
import backend.ir.IrPrimitive
import backend.ir.IrStore
import backend.ir.IrValue
import backend.ir.PrimitiveKind

class PhiLoweringPass : FunctionPass {
  override val name: String = "phi-lowering"

  override fun run(module: IrModule, function: IrFunction) {
    if (function.blocks.isEmpty()) return

    val phiByBlock = linkedMapOf<String, List<IrPhi>>()
    function.blocks.forEach { block ->
      val phis = block.instructions.filterIsInstance<IrPhi>()
      if (phis.isNotEmpty()) {
        phiByBlock[block.label] = phis
      }
    }
    if (phiByBlock.isEmpty()) return

    val nameAllocator = PhiNameAllocator(function)
    val phiSlots = allocatePhiSlots(function, phiByBlock, nameAllocator)

    val edgeStores = linkedMapOf<EdgeKey, MutableList<EdgeStore>>()
    phiByBlock.forEach { (blockLabel, phis) ->
      phis.forEach { phi ->
        val slot = phiSlots.getValue(phi.name)
        phi.incoming.forEach { incoming ->
          val key = EdgeKey(incoming.predecessor, blockLabel)
          edgeStores.getOrPut(key) { mutableListOf() }
            .add(EdgeStore(slot, incoming.value))
        }
      }
    }

    lowerPhiInstructions(phiByBlock, phiSlots, function)
    materializeEdgeStores(function, edgeStores, nameAllocator)
  }

  private fun allocatePhiSlots(
    function: IrFunction,
    phiByBlock: Map<String, List<IrPhi>>,
    nameAllocator: PhiNameAllocator,
  ): Map<String, IrLocal> {
    val entry = function.blocks.first()
    var insertAt = entry.instructions.indexOfLast { it is IrAlloca }
    insertAt = if (insertAt >= 0) insertAt + 1 else 0

    val slots = linkedMapOf<String, IrLocal>()
    phiByBlock.values.flatten().forEach { phi ->
      if (slots.containsKey(phi.name)) return@forEach
      val slotName = nameAllocator.fresh("phi.slot.${phi.name}")
      entry.instructions.add(
        insertAt,
        IrAlloca(
          name = slotName,
          type = IrPointer(phi.type),
          allocatedType = phi.type,
        )
      )
      insertAt += 1
      slots[phi.name] = IrLocal(slotName, IrPointer(phi.type))
    }
    return slots
  }

  private fun lowerPhiInstructions(
    phiByBlock: Map<String, List<IrPhi>>,
    phiSlots: Map<String, IrLocal>,
    function: IrFunction,
  ) {
    val blockByLabel = function.blocks.associateBy { it.label }
    phiByBlock.forEach { (label, phis) ->
      val block = blockByLabel[label] ?: return@forEach
      val nonPhiInstructions = block.instructions.filterNot { it is IrPhi }
      val replacementLoads = phis.map { phi ->
        IrLoad(
          name = phi.name,
          type = phi.type,
          address = phiSlots.getValue(phi.name),
        )
      }
      block.instructions.clear()
      block.instructions.addAll(replacementLoads)
      block.instructions.addAll(nonPhiInstructions)
    }
  }

  private fun materializeEdgeStores(
    function: IrFunction,
    edgeStores: Map<EdgeKey, List<EdgeStore>>,
    nameAllocator: PhiNameAllocator,
  ) {
    if (edgeStores.isEmpty()) return

    val blockByLabel = function.blocks.associateBy { it.label }.toMutableMap()
    val blockOrder = function.blocks.withIndex().associate { (index, block) -> block.label to index }
    val sortedEdges = edgeStores.keys.sortedWith(
      compareBy<EdgeKey> { blockOrder[it.predecessor] ?: Int.MAX_VALUE }
        .thenBy { blockOrder[it.successor] ?: Int.MAX_VALUE }
        .thenBy { it.predecessor }
        .thenBy { it.successor }
    )

    sortedEdges.forEach { edge ->
      val stores = edgeStores[edge].orEmpty()
      if (stores.isEmpty()) return@forEach

      val pred = blockByLabel[edge.predecessor]
        ?: error("phi-lowering: predecessor block not found: ${edge.predecessor}")

      when (val term = pred.terminator) {
        is IrJump -> {
          if (term.target != edge.successor) {
            error("phi-lowering: malformed edge ${edge.predecessor} -> ${edge.successor}")
          }
          appendStores(pred, stores)
        }

        is IrBranch -> {
          val edgeOnTrue = term.trueTarget == edge.successor
          val edgeOnFalse = term.falseTarget == edge.successor
          if (!edgeOnTrue && !edgeOnFalse) {
            error("phi-lowering: malformed edge ${edge.predecessor} -> ${edge.successor}")
          }

          val canInsertInPred = edgeOnTrue && edgeOnFalse
          if (canInsertInPred) {
            appendStores(pred, stores)
            return@forEach
          }

          val splitLabel = nameAllocator.freshBlock("phi.edge.${edge.predecessor}.to.${edge.successor}")
          val split = IrBasicBlock(splitLabel)
          appendStores(split, stores)
          split.setTerminator(
            IrJump(
              name = "",
              type = IrPrimitive(PrimitiveKind.UNIT),
              target = edge.successor,
            )
          )
          function.appendBlock(split)
          blockByLabel[splitLabel] = split

          val rewritten = when {
            edgeOnTrue -> term.copy(trueTarget = splitLabel)
            edgeOnFalse -> term.copy(falseTarget = splitLabel)
            else -> term
          }
          pred.replaceTerminator(rewritten)
        }

        else -> {
          error("phi-lowering: predecessor ${edge.predecessor} has no branch/jump terminator")
        }
      }
    }
  }

  private fun appendStores(
    block: IrBasicBlock,
    stores: List<EdgeStore>,
  ) {
    val unit = IrPrimitive(PrimitiveKind.UNIT)
    stores.forEach { edgeStore ->
      block.instructions.add(
        IrStore(
          name = "",
          type = unit,
          address = edgeStore.slot,
          value = edgeStore.value,
        )
      )
    }
  }
}

private data class EdgeKey(
  val predecessor: String,
  val successor: String,
)

private data class EdgeStore(
  val slot: IrLocal,
  val value: IrValue,
)

private class PhiNameAllocator(
  function: IrFunction,
) {
  private val usedValueNames = mutableSetOf<String>()
  private val usedBlockLabels = mutableSetOf<String>()

  init {
    function.signature.parameters.indices.forEach { index ->
      val parameterName = function.parameterNames.getOrNull(index)?.takeIf { it.isNotBlank() } ?: "arg$index"
      usedValueNames.add(parameterName)
    }
    function.blocks.forEach { block ->
      usedBlockLabels.add(block.label)
      block.instructions.forEach { instruction ->
        if (instruction.name.isNotBlank()) {
          usedValueNames.add(instruction.name)
        }
      }
    }
  }

  fun fresh(stem: String): String {
    val base = stem.ifBlank { "tmp" }
    if (usedValueNames.add(base)) return base
    var id = 2
    while (true) {
      val candidate = "$base.$id"
      if (usedValueNames.add(candidate)) return candidate
      id++
    }
  }

  fun freshBlock(stem: String): String {
    val base = stem.ifBlank { "bb" }
    if (usedBlockLabels.add(base)) return base
    var id = 2
    while (true) {
      val candidate = "$base.$id"
      if (usedBlockLabels.add(candidate)) return candidate
      id++
    }
  }
}
