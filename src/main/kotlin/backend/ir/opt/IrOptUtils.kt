package backend.ir.opt

// Provides shared IR optimization analysis and rewriting utilities.

import backend.ir.*
import backend.ir.analysis.buildCfg

internal fun isVoidType(type: IrType): Boolean =
  (type as? IrPrimitive)?.kind == PrimitiveKind.UNIT ||
    (type as? IrPrimitive)?.kind == PrimitiveKind.NEVER

internal fun IrInstruction.definedName(): String? {
  if (name.isBlank()) return null
  return when (this) {
    is IrStore, is IrReturn, is IrBranch, is IrJump -> null
    is IrCall -> name.takeUnless { isVoidType(type) }
    else -> name
  }
}

internal fun localName(value: IrValue): String? = (value as? IrLocal)?.name

internal fun instructionUses(instruction: IrInstruction): List<IrValue> = when (instruction) {
  is IrAlloca -> emptyList()
  is IrConst -> emptyList()
  is IrLoad -> listOf(instruction.address)
  is IrStore -> listOf(instruction.address, instruction.value)
  is IrBinary -> listOf(instruction.lhs, instruction.rhs)
  is IrUnary -> listOf(instruction.operand)
  is IrCmp -> listOf(instruction.lhs, instruction.rhs)
  is IrCall -> instruction.arguments
  is IrGep -> listOf(instruction.base) + instruction.indices
  is IrPhi -> instruction.incoming.map { it.value }
  is IrCast -> listOf(instruction.value)
  is IrReturn -> instruction.value?.let { listOf(it) } ?: emptyList()
  is IrBranch -> listOf(instruction.condition)
  is IrJump -> emptyList()
}

internal fun terminatorUses(terminator: IrTerminator): List<IrValue> = instructionUses(terminator)

internal fun rewriteValue(value: IrValue, mapper: (IrValue) -> IrValue): IrValue = mapper(value)

internal fun rewriteInstructionValues(
  instruction: IrInstruction,
  mapper: (IrValue) -> IrValue,
): IrInstruction = when (instruction) {
  is IrAlloca -> instruction
  is IrConst -> instruction
  is IrLoad -> instruction.copy(address = rewriteValue(instruction.address, mapper))
  is IrStore -> instruction.copy(
    address = rewriteValue(instruction.address, mapper),
    value = rewriteValue(instruction.value, mapper),
  )
  is IrBinary -> instruction.copy(
    lhs = rewriteValue(instruction.lhs, mapper),
    rhs = rewriteValue(instruction.rhs, mapper),
  )
  is IrUnary -> instruction.copy(operand = rewriteValue(instruction.operand, mapper))
  is IrCmp -> instruction.copy(
    lhs = rewriteValue(instruction.lhs, mapper),
    rhs = rewriteValue(instruction.rhs, mapper),
  )
  is IrCall -> instruction.copy(arguments = instruction.arguments.map { rewriteValue(it, mapper) })
  is IrGep -> instruction.copy(
    base = rewriteValue(instruction.base, mapper),
    indices = instruction.indices.map { rewriteValue(it, mapper) },
  )
  is IrPhi -> instruction.copy(
    incoming = instruction.incoming.map { incoming ->
      incoming.copy(value = rewriteValue(incoming.value, mapper))
    }
  )
  is IrCast -> instruction.copy(value = rewriteValue(instruction.value, mapper))
  is IrReturn -> instruction.copy(value = instruction.value?.let { rewriteValue(it, mapper) })
  is IrBranch -> instruction.copy(condition = rewriteValue(instruction.condition, mapper))
  is IrJump -> instruction
}

internal fun rewriteTerminatorValues(
  terminator: IrTerminator,
  mapper: (IrValue) -> IrValue,
): IrTerminator = rewriteInstructionValues(terminator, mapper) as IrTerminator

internal fun isSideEffectingInstruction(instruction: IrInstruction): Boolean = when (instruction) {
  is IrStore -> true
  is IrCall -> true
  is IrReturn, is IrBranch, is IrJump -> true
  else -> false
}

internal fun isRemovableInstruction(instruction: IrInstruction): Boolean =
  !isSideEffectingInstruction(instruction)

internal fun collectUsedLocalNames(function: IrFunction): Set<String> {
  val used = linkedSetOf<String>()
  function.blocks.forEach { block ->
    block.instructions.forEach { instruction ->
      instructionUses(instruction).mapNotNullTo(used, ::localName)
    }
    block.terminator?.let { terminator ->
      terminatorUses(terminator).mapNotNullTo(used, ::localName)
    }
  }
  return used
}

internal fun removeUnreachableBlocks(function: IrFunction): Boolean {
  if (function.blocks.isEmpty()) return false
  val cfg = buildCfg(function)
  if (cfg.entry.isEmpty()) return false

  val reachable = linkedSetOf<String>()
  fun visit(label: String) {
    if (!reachable.add(label)) return
    cfg.successors[label].orEmpty().forEach(::visit)
  }
  visit(cfg.entry)

  var changed = function.blocks.removeAll { block -> block.label !in reachable }
  val liveCfg = buildCfg(function)
  function.blocks.forEach { block ->
    val preds = liveCfg.predecessors[block.label].orEmpty()
    val rewritten = block.instructions.map { instruction ->
      if (instruction is IrPhi) {
        val incoming = instruction.incoming.filter { it.predecessor in preds }
        if (incoming.size != instruction.incoming.size) {
          changed = true
          instruction.copy(incoming = incoming)
        } else {
          instruction
        }
      } else {
        instruction
      }
    }
    block.instructions.clear()
    block.instructions.addAll(rewritten)
  }
  return changed
}
