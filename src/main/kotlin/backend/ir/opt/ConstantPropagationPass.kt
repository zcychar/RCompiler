package backend.ir.opt

// Folds constants and simplifies branches in IR.

import backend.ir.*

class ConstantPropagationPass : FunctionPass {
  override val name: String = "constprop"

  override fun run(module: IrModule, function: IrFunction) {
    if (function.blocks.isEmpty()) return

    var changed = true
    var rounds = 0
    while (changed && rounds < MAX_ROUNDS) {
      rounds++
      changed = foldAndRewrite(function)
      changed = removeUnreachableBlocks(function) || changed
    }
  }

  private fun foldAndRewrite(function: IrFunction): Boolean {
    var changed = false
    val constants = linkedMapOf<String, IrConstant>()

    fun resolve(value: IrValue): IrValue =
      if (value is IrLocal) constants[value.name] ?: value else value

    fun constantOf(value: IrValue): IrConstant? = when (val resolved = resolve(value)) {
      is IrConstant -> resolved
      else -> null
    }

    function.blocks.forEach { block ->
      val rewritten = mutableListOf<IrInstruction>()

      block.instructions.forEach { instruction ->
        val normalized = rewriteInstructionValues(instruction, ::resolve)
        val replacement = foldInstruction(normalized, ::constantOf)
        val definedName = normalized.definedName()

        if (replacement != null && definedName != null) {
          constants[definedName] = replacement
          changed = true
        } else {
          if (definedName != null) constants.remove(definedName)
          rewritten.add(normalized)
          if (normalized != instruction) changed = true
        }
      }

      block.instructions.clear()
      block.instructions.addAll(rewritten)

      val terminator = block.terminator
      if (terminator != null) {
        val normalized = rewriteTerminatorValues(terminator, ::resolve)
        val simplified = simplifyTerminator(normalized, ::constantOf)
        if (simplified != terminator) {
          block.replaceTerminator(simplified)
          changed = true
        }
      }
    }

    return changed
  }

  private fun foldInstruction(
    instruction: IrInstruction,
    constantOf: (IrValue) -> IrConstant?,
  ): IrConstant? = when (instruction) {
    is IrConst -> instruction.constant
    is IrBinary -> foldBinary(instruction, constantOf)
    is IrUnary -> foldUnary(instruction, constantOf)
    is IrCmp -> foldCmp(instruction, constantOf)
    is IrCast -> foldCast(instruction, constantOf)
    is IrPhi -> foldPhi(instruction, constantOf)
    else -> null
  }

  private fun simplifyTerminator(
    terminator: IrTerminator,
    constantOf: (IrValue) -> IrConstant?,
  ): IrTerminator = when (terminator) {
    is IrBranch -> {
      val condition = constantOf(terminator.condition)
      if (condition != null) {
        val target = if (condition.value != 0L) terminator.trueTarget else terminator.falseTarget
        IrJump(terminator.name, terminator.type, target)
      } else {
        terminator
      }
    }
    is IrReturn, is IrJump -> terminator
  }

  private fun foldBinary(
    instruction: IrBinary,
    constantOf: (IrValue) -> IrConstant?,
  ): IrConstant? {
    val lhs = constantOf(instruction.lhs) ?: return null
    val rhs = constantOf(instruction.rhs) ?: return null
    val l = lhs.value.toInt()
    val r = rhs.value.toInt()
    if ((instruction.operator == BinaryOperator.SDIV ||
        instruction.operator == BinaryOperator.UDIV ||
        instruction.operator == BinaryOperator.SREM ||
        instruction.operator == BinaryOperator.UREM) && r == 0) {
      return null
    }

    val result = when (instruction.operator) {
      BinaryOperator.ADD -> l + r
      BinaryOperator.SUB -> l - r
      BinaryOperator.MUL -> l * r
      BinaryOperator.SDIV -> l / r
      BinaryOperator.UDIV -> Integer.divideUnsigned(l, r)
      BinaryOperator.SREM -> l % r
      BinaryOperator.UREM -> Integer.remainderUnsigned(l, r)
      BinaryOperator.AND -> l and r
      BinaryOperator.OR -> l or r
      BinaryOperator.XOR -> l xor r
      BinaryOperator.SHL -> l shl (r and 31)
      BinaryOperator.ASHR -> l shr (r and 31)
      BinaryOperator.LSHR -> l ushr (r and 31)
    }
    return IrConstant(result.toLong(), instruction.type)
  }

  private fun foldUnary(
    instruction: IrUnary,
    constantOf: (IrValue) -> IrConstant?,
  ): IrConstant? {
    val operand = constantOf(instruction.operand) ?: return null
    val value = operand.value.toInt()
    val result = when (instruction.operator) {
      UnaryOperator.NEG -> -value
      UnaryOperator.NOT -> {
        val isBool = (instruction.type as? IrPrimitive)?.kind == PrimitiveKind.BOOL
        if (isBool) value xor 1 else value.inv()
      }
    }
    return IrConstant(result.toLong(), instruction.type)
  }

  private fun foldCmp(
    instruction: IrCmp,
    constantOf: (IrValue) -> IrConstant?,
  ): IrConstant? {
    val lhs = constantOf(instruction.lhs) ?: return null
    val rhs = constantOf(instruction.rhs) ?: return null
    val l = lhs.value.toInt()
    val r = rhs.value.toInt()
    val result = when (instruction.predicate) {
      ComparePredicate.EQ -> l == r
      ComparePredicate.NE -> l != r
      ComparePredicate.SLT -> l < r
      ComparePredicate.SLE -> l <= r
      ComparePredicate.SGT -> l > r
      ComparePredicate.SGE -> l >= r
      ComparePredicate.ULT -> Integer.compareUnsigned(l, r) < 0
      ComparePredicate.ULE -> Integer.compareUnsigned(l, r) <= 0
      ComparePredicate.UGT -> Integer.compareUnsigned(l, r) > 0
      ComparePredicate.UGE -> Integer.compareUnsigned(l, r) >= 0
    }
    return IrConstant(if (result) 1 else 0, instruction.type)
  }

  private fun foldCast(
    instruction: IrCast,
    constantOf: (IrValue) -> IrConstant?,
  ): IrConstant? {
    val value = constantOf(instruction.value) ?: return null
    val raw = value.value.toInt()
    val result = when (instruction.kind) {
      CastKind.BITCAST, CastKind.PTRTOINT, CastKind.INTTOPTR -> raw
      CastKind.TRUNC -> truncate(raw, instruction.type)
      CastKind.ZEXT -> zeroExtend(raw, instruction.value.type)
      CastKind.SEXT -> signExtend(raw, instruction.value.type)
    }
    return IrConstant(result.toLong(), instruction.type)
  }

  private fun foldPhi(
    instruction: IrPhi,
    constantOf: (IrValue) -> IrConstant?,
  ): IrConstant? {
    if (instruction.incoming.isEmpty()) return null
    val constants = instruction.incoming.map { incoming -> constantOf(incoming.value) ?: return null }
    val first = constants.first()
    if (constants.all { it.value == first.value && it.type == first.type }) {
      return IrConstant(first.value, instruction.type)
    }
    return null
  }

  private fun truncate(value: Int, targetType: IrType): Int = when ((targetType as? IrPrimitive)?.kind) {
    PrimitiveKind.BOOL -> value and 1
    PrimitiveKind.CHAR -> value and 0xFF
    else -> value
  }

  private fun zeroExtend(value: Int, sourceType: IrType): Int = when ((sourceType as? IrPrimitive)?.kind) {
    PrimitiveKind.BOOL -> value and 1
    PrimitiveKind.CHAR -> value and 0xFF
    else -> value
  }

  private fun signExtend(value: Int, sourceType: IrType): Int = when ((sourceType as? IrPrimitive)?.kind) {
    PrimitiveKind.BOOL -> value and 1
    PrimitiveKind.CHAR -> value.toByte().toInt()
    else -> value
  }

  private companion object {
    const val MAX_ROUNDS = 20
  }
}
