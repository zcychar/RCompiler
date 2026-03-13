package backend.ir

import frontend.Keyword
import frontend.Literal
import frontend.Punctuation
import frontend.assignOp
import frontend.ast.*
import frontend.semantic.*
import utils.CompileError

/**
 * Expression emitter handling the currently supported lowering surface.
 * Unsupported expressions fail fast so missing coverage is obvious while we grow the backend.
 *
 * ## Aggregate Strategy
 *
 * Aggregates (structs, arrays) are **never** produced as SSA values. Instead, every
 * aggregate-typed expression is represented as a pointer to its storage.
 *
 * [emitExpr] accepts an optional [destPtr] parameter. When the caller already has
 * a destination for an aggregate (e.g. a `let` binding's alloca, an sret slot),
 * it passes that pointer so the expression can store directly into it — no temporary,
 * no whole-aggregate load, no whole-aggregate store.
 *
 * When [destPtr] is null and the expression produces an aggregate, a temporary alloca
 * is created and returned as the pointer.
 */
class ExprEmitter(
  private val context: CodegenContext,
  private val builder: IrBuilder = context.builder,
  private val valueEnv: ValueEnv = context.valueEnv,
) {
  private val blockEmitter by lazy { FunctionEmitter(context, builder, this, valueEnv) }

  /**
   * Emit an expression.
   *
   * @param destPtr  If non-null and the expression produces an aggregate, the result
   *                 will be written directly into [destPtr] and [destPtr] is returned.
   *                 Ignored for scalar expressions.
   */
  fun emitExpr(node: ExprNode, expectedType: IrType? = null, destPtr: IrValue? = null): IrValue = when (node) {
    is LiteralExprNode -> emitLiteral(node)
    is PathExprNode -> emitPath(node, destPtr)
    is GroupedExprNode -> emitExpr(node.expr, expectedType, destPtr)
    is ArrayExprNode -> emitArrayExpr(node, destPtr)
    is BlockExprNode -> emitBlockExpr(node, expectedType, destPtr)
    is BinaryExprNode -> emitBinary(node, destPtr)
    is UnaryExprNode -> emitUnary(node)
    is CastExprNode -> emitCast(node)
    is BorrowExprNode -> emitBorrow(node)
    is DerefExprNode -> emitDeref(node, destPtr)
    is IfExprNode -> emitIf(node, destPtr)
    is WhileExprNode -> emitWhile(node)
    is LoopExprNode -> emitLoop(node)
    is BreakExprNode -> emitBreak(node)
    is ContinueExprNode -> emitContinue()
    is CallExprNode -> emitCall(node, destPtr)
    is MethodCallExprNode -> emitMethodCall(node, destPtr)
    is StructExprNode -> emitStructExpr(node, destPtr)
    is ReturnExprNode -> emitReturn(node)
    is FieldAccessExprNode -> emitFieldAccess(node, destPtr)
    is IndexExprNode -> emitIndexAccess(node, destPtr)
    else -> error("Unsupported expression: ${node::class.simpleName}")
  }

  private fun emitLiteral(node: LiteralExprNode): IrValue = when (node.type) {
    Keyword.TRUE -> IrConstant(1, IrPrimitive(PrimitiveKind.BOOL))
    Keyword.FALSE -> IrConstant(0, IrPrimitive(PrimitiveKind.BOOL))
    Literal.INTEGER -> {
      val intConst = getInt(node)
      val irType = toIrType(intConst.actualType)
      IrConstant(intConst.value, irType)
    }

    else -> error("Unsupported literal ${node.type}")
  }

  /**
   * Emit a path expression.
   *
   * For scalars: loads the value and returns it.
   * For aggregates: returns the pointer to the storage. If [destPtr] is provided,
   * copies the aggregate field-wise into [destPtr] and returns [destPtr].
   */
  private fun emitPath(node: PathExprNode, destPtr: IrValue? = null): IrValue {
    if (node.seg2 != null) {
      error("Qualified paths are not supported in expression lowering yet")
    }
    val identifier = node.seg1.name ?: "self"
    return when (val it = valueEnv.resolve(identifier)) {
      is Bind.Value -> it.value
      is Bind.Pointer -> {
        val pointeeType = it.getPointeeType()
        if (isAggregate(pointeeType)) {
          // Aggregate: return pointer, or copy to dest
          if (destPtr != null) {
            builder.emitAggregateCopy(destPtr, it.addr, pointeeType)
            destPtr
          } else {
            it.addr
          }
        } else {
          builder.emit(
            IrLoad("", pointeeType, it.addr)
          )
        }
      }

      else -> {
        return context.module.getGlobalByName(identifier) ?: error("Unbound identifier $identifier")
      }
    }
  }

  private fun emitBinary(node: BinaryExprNode, destPtr: IrValue? = null): IrValue =
    if (node.op in assignOp) emitAssignment(node) else emitBinaryOp(node)

  private fun emitAssignment(node: BinaryExprNode): IrValue {
    val lhs = emitLValue(node.lhs)
    val lhsInner = getLValueInnerType(lhs)

    if (isAggregate(lhsInner)) {
      // Aggregate assignment: emit RHS directly into the LHS destination.
      emitExpr(node.rhs, destPtr = lhs)
      return unitValue()
    }

    val rhs = emitExpr(node.rhs)
    val stored = when (node.op) {
      Punctuation.EQUAL -> {
        builder.emit(
          IrStore("", IrPrimitive(PrimitiveKind.UNIT), lhs, rhs)
        )
      }

      Punctuation.PLUS_EQUAL -> emitAssignArithmetic(BinaryOperator.ADD, lhs, rhs)
      Punctuation.MINUS_EQUAL -> emitAssignArithmetic(BinaryOperator.SUB, lhs, rhs)
      Punctuation.STAR_EQUAL -> emitAssignArithmetic(BinaryOperator.MUL, lhs, rhs)
      Punctuation.SLASH_EQUAL -> emitAssignArithmetic(
        if (isUnsigned(lhs.type)) BinaryOperator.UDIV else BinaryOperator.SDIV,
        lhs,
        rhs,
      )

      Punctuation.PERCENT_EQUAL -> emitAssignArithmetic(
        if (isUnsigned(lhs.type)) BinaryOperator.UREM else BinaryOperator.SREM,
        lhs,
        rhs,
      )

      Punctuation.AND_EQUAL -> emitAssignArithmetic(BinaryOperator.AND, lhs, rhs)
      Punctuation.OR_EQUAL -> emitAssignArithmetic(BinaryOperator.OR, lhs, rhs)
      Punctuation.CARET_EQUAL -> emitAssignArithmetic(BinaryOperator.XOR, lhs, rhs)
      Punctuation.LESS_LESS_EQUAL -> emitAssignArithmetic(BinaryOperator.SHL, lhs, rhs)
      Punctuation.GREATER_GREATER_EQUAL -> emitAssignArithmetic(
        if (isUnsigned(lhs.type)) BinaryOperator.LSHR else BinaryOperator.ASHR,
        lhs,
        rhs,
      )

      else -> error("Unsupported assign operator ${node.op}")
    }
    return unitValue()
  }

  private fun emitBinaryOp(node: BinaryExprNode): IrValue {
    val lhs = emitExpr(node.lhs)
    val rhs = emitExpr(node.rhs)
    return when (node.op) {
      Punctuation.PLUS -> emitArithmetic(BinaryOperator.ADD, lhs, rhs)
      Punctuation.MINUS -> emitArithmetic(BinaryOperator.SUB, lhs, rhs)
      Punctuation.STAR -> emitArithmetic(BinaryOperator.MUL, lhs, rhs)
      Punctuation.SLASH -> emitArithmetic(
        if (isUnsigned(lhs.type)) BinaryOperator.UDIV else BinaryOperator.SDIV,
        lhs,
        rhs,
      )

      Punctuation.PERCENT -> emitArithmetic(
        if (isUnsigned(lhs.type)) BinaryOperator.UREM else BinaryOperator.SREM,
        lhs,
        rhs,
      )

      Punctuation.LESS_LESS -> emitArithmetic(BinaryOperator.SHL, lhs, rhs)
      Punctuation.GREATER_GREATER -> emitArithmetic(
        if (isUnsigned(lhs.type)) BinaryOperator.LSHR else BinaryOperator.ASHR,
        lhs,
        rhs,
      )

      Punctuation.EQUAL_EQUAL -> emitCompare(ComparePredicate.EQ, lhs, rhs)
      Punctuation.NOT_EQUAL -> emitCompare(ComparePredicate.NE, lhs, rhs)
      Punctuation.LESS -> emitCompare(
        if (isUnsigned(lhs.type)) ComparePredicate.ULT else ComparePredicate.SLT,
        lhs,
        rhs
      )

      Punctuation.LESS_EQUAL -> emitCompare(
        if (isUnsigned(lhs.type)) ComparePredicate.ULE else ComparePredicate.SLE,
        lhs,
        rhs
      )

      Punctuation.GREATER -> emitCompare(
        if (isUnsigned(lhs.type)) ComparePredicate.UGT else ComparePredicate.SGT,
        lhs,
        rhs
      )

      Punctuation.GREATER_EQUAL -> emitCompare(
        if (isUnsigned(lhs.type)) ComparePredicate.UGE else ComparePredicate.SGE,
        lhs,
        rhs
      )

      Punctuation.AMPERSAND -> emitLogical(BinaryOperator.AND, lhs, rhs)
      Punctuation.PIPE -> emitLogical(BinaryOperator.OR, lhs, rhs)
      Punctuation.CARET -> emitLogical(BinaryOperator.XOR, lhs, rhs)
      Punctuation.AND_AND -> emitShortCircuitAnd(lhs) { emitExpr(node.rhs) }
      Punctuation.OR_OR -> emitShortCircuitOr(lhs) { emitExpr(node.rhs) }
      else -> error("Unsupported binary operator ${node.op}")
    }
  }

  private fun emitArithmetic(operator: BinaryOperator, lhs: IrValue, rhs: IrValue): IrValue {
    var left = lhs
    var right = rhs
    val lhsUnit = (lhs.type as? IrPrimitive)?.kind == PrimitiveKind.UNIT
    val rhsUnit = (rhs.type as? IrPrimitive)?.kind == PrimitiveKind.UNIT
    if (lhsUnit && !rhsUnit) {
      left = zeroOfType(rhs.type)
    } else if (rhsUnit && !lhsUnit) {
      right = zeroOfType(lhs.type)
    } else if (lhsUnit) {
      val intType = IrPrimitive(PrimitiveKind.I32)
      left = IrConstant(0, intType)
      right = IrConstant(0, intType)
    }
    ensureSameType(left.type, right.type)
    return builder.emit(
      IrBinary(
        name = "",
        type = left.type,
        operator = operator,
        lhs = left,
        rhs = right,
      )
    )
  }

  //lhs -- lvalue
  private fun emitAssignArithmetic(operator: BinaryOperator, lhs: IrValue, rhs: IrValue): IrValue {
    val left = builder.emit(
      IrLoad("", getLValueInnerType(lhs), lhs)
    )
    var right = rhs
    val rhsUnit = (rhs.type as? IrPrimitive)?.kind == PrimitiveKind.UNIT
    if (rhsUnit) {
      right = zeroOfType(lhs.type)
    }
    ensureSameType(left.type, right.type)
    val result = builder.emit(
      IrBinary(
        name = "",
        type = left.type,
        operator = operator,
        lhs = left,
        rhs = right,
      )
    )
    builder.emit(
      IrStore(
        name = "",
        type = result.type,
        address = lhs,
        value = result
      )
    )
    return unitValue()
  }

  private fun emitCompare(predicate: ComparePredicate, lhs: IrValue, rhs: IrValue): IrValue {
    var left = lhs
    var right = rhs
    val lhsUnit = (lhs.type as? IrPrimitive)?.kind == PrimitiveKind.UNIT
    val rhsUnit = (rhs.type as? IrPrimitive)?.kind == PrimitiveKind.UNIT
    if (lhsUnit && !rhsUnit) {
      left = zeroOfType(rhs.type)
    } else if (rhsUnit && !lhsUnit) {
      right = zeroOfType(lhs.type)
    } else if (lhsUnit && rhsUnit) {
      val intType = IrPrimitive(PrimitiveKind.I32)
      left = IrConstant(0, intType)
      right = IrConstant(0, intType)
    }
    ensureSameType(left.type, right.type)
    return builder.emit(
      IrCmp(
        name = "",
        type = IrPrimitive(PrimitiveKind.BOOL),
        predicate = predicate,
        lhs = left,
        rhs = right,
      ),
    )
  }

  private fun zeroOfType(type: IrType): IrValue = when (type) {
    is IrPrimitive -> IrConstant(0, type)
    is IrPointer -> IrConstant(0, IrPrimitive(PrimitiveKind.ISIZE))
    else -> IrConstant(0, IrPrimitive(PrimitiveKind.I32))
  }

  private fun emitLogical(operator: BinaryOperator, lhs: IrValue, rhs: IrValue): IrValue {
    ensureSameType(lhs.type, rhs.type)
    return builder.emit(
      IrBinary(
        name = "",
        type = lhs.type,
        operator = operator,
        lhs = lhs,
        rhs = rhs,
      )
    )
  }

  private fun emitShortCircuitAnd(lhs: IrValue, rhsThunk: () -> IrValue): IrValue {
    return emitShortCircuit(lhs, rhsThunk, shortCircuitOnTrue = false)
  }

  private fun emitShortCircuitOr(lhs: IrValue, rhsThunk: () -> IrValue): IrValue {
    return emitShortCircuit(lhs, rhsThunk, shortCircuitOnTrue = true)
  }

  //lhs,rhs -- ssa value
  private fun emitShortCircuit(lhs: IrValue, rhsThunk: () -> IrValue, shortCircuitOnTrue: Boolean): IrValue {
    val boolType = IrPrimitive(PrimitiveKind.BOOL)
    if (lhs.type != boolType) error("Short-circuit operators require boolean operands")
    val function = context.currentFunction ?: error("No active function for short-circuit emission")

    val lhsLabel = builder.currentBlockLabel()
    val rhsLabel = builder.freshLocalName(if (shortCircuitOnTrue) "or.rhs" else "and.rhs")
    val mergeLabel = builder.freshLocalName("sc.merge")
    var rhsPred: String? = null


    val trueTarget = if (shortCircuitOnTrue) mergeLabel else rhsLabel
    val falseTarget = if (shortCircuitOnTrue) rhsLabel else mergeLabel
    builder.emitTerminator(
      IrBranch(
        name = "",
        type = IrPrimitive(PrimitiveKind.UNIT),
        condition = lhs,
        trueTarget = trueTarget,
        falseTarget = falseTarget,
      )
    )

    val rhsBlock = builder.ensureBlock(rhsLabel)
    builder.positionAt(function, rhsBlock)

    val rhs = rhsThunk()
    if (rhs.type != boolType) error("Short-circuit RHS must be boolean")
    rhsPred = builder.currentBlockLabel()
    builder.emitTerminator(IrJump(name = "", type = IrPrimitive(PrimitiveKind.UNIT), target = mergeLabel))

    val mergeBlock = builder.ensureBlock(mergeLabel)
    builder.positionAt(function, mergeBlock)
    val circuitVal = if (shortCircuitOnTrue) IrConstant(1, boolType) else IrConstant(0, boolType)
    return builder.emit(
      IrPhi(
        name = "",
        type = boolType,
        incoming = listOf(
          PhiBranch(circuitVal, lhsLabel),
          PhiBranch(rhs, rhsPred ?: rhsLabel),
        ),
      ),
    )
  }

  private fun emitUnary(node: UnaryExprNode): IrValue {
    val operand = emitExpr(node.rhs)
    val operator = when (node.op) {
      Punctuation.MINUS -> UnaryOperator.NEG
      Punctuation.BANG -> UnaryOperator.NOT
      else -> error("Unsupported unary operator ${node.op}")
    }
    return builder.emit(
      IrUnary(
        name = "",
        type = operand.type,
        operator = operator,
        operand = operand,
      )
    )
  }

  private fun emitCast(node: CastExprNode): IrValue {
    val value = emitExpr(node.expr)
    val targetType = mapType(node.targetType)
    val irTarget = toIrType(targetType)
    val valueType = value.type
    if (irTarget == valueType) return value

    val kind = when {
      valueType is IrPrimitive && irTarget is IrPrimitive -> {
        val srcW = bitWidth(valueType)
        val dstW = bitWidth(irTarget)
        when {
          srcW == dstW -> return value
          dstW < srcW -> CastKind.TRUNC
          else -> if (isUnsigned(valueType) || valueType.kind == PrimitiveKind.BOOL) CastKind.ZEXT else CastKind.SEXT
        }
      }

      valueType is IrPointer && irTarget is IrPointer -> CastKind.BITCAST
      else -> CastKind.BITCAST
    }

    return builder.emit(
      IrCast(
        name = "",
        type = irTarget,
        value = value,
        kind = kind,
      ),
    )
  }

  private fun emitBorrow(node: BorrowExprNode): IrValue {
    val expectType = toIrType(node.type!!) as? IrPointer ?: error("borrow expr with non-pointer type")
    val baseAddr = tryLValue(node.expr)
    if (baseAddr != null) return retargetPointer(baseAddr, expectType)

    val value = emitExpr(node.expr)
    // If the value is already a pointer to an aggregate (our new convention),
    // we can use it directly for borrowing.
    if (value.type is IrPointer && isAggregate((value.type as IrPointer).pointee)) {
      return retargetPointer(value, expectType)
    }
    val tmpAddr = builder.borrow(null, value)
    return retargetPointer(tmpAddr, expectType)
  }

  private fun emitDeref(node: DerefExprNode, destPtr: IrValue? = null): IrValue {
    val pointer = emitExpr(node.expr)
    val pointerType = pointer.type as? IrPointer
      ?: error("Cannot dereference non-pointer type ${pointer.type}")
    val pointeeType = pointerType.pointee
    if (isAggregate(pointeeType)) {
      // Dereferencing a pointer to an aggregate: return the pointer (or copy to dest).
      if (destPtr != null) {
        builder.emitAggregateCopy(destPtr, pointer, pointeeType)
        return destPtr
      }
      return pointer
    }
    return builder.emit(
      IrLoad(
        name = "",
        type = pointeeType,
        address = pointer,
      )
    )
  }

  private fun emitBlockExpr(block: BlockExprNode, expectedType: IrType?, destPtr: IrValue? = null): IrValue {
    val value = blockEmitter.emitBlock(block, expectValue = block.hasFinal(), expectedType = expectedType, destPtr = destPtr)
    return value ?: expectedType?.let { IrUndef(it) } ?: unitValue()
  }

  /**
   * Emit an if-expression. For aggregate results, uses destination-passing to avoid
   * whole-aggregate phi nodes.
   */
  private fun emitIf(node: IfExprNode, destPtr: IrValue? = null): IrValue {
    val function = context.currentFunction ?: error("No active function")
    val condExpr = node.conds.firstOrNull()?.expr ?: error("if without condition")
    val condition = emitExpr(condExpr)
    val boolType = IrPrimitive(PrimitiveKind.BOOL)
    if (condition.type != boolType) error("if condition must be boolean")

    val resultType = toIrType(node.expectType!!)
    val needsValue =
      resultType !is IrPrimitive || (resultType.kind != PrimitiveKind.UNIT && resultType.kind != PrimitiveKind.NEVER)

    val isAggResult = needsValue && isAggregate(resultType)

    // For aggregate results, ensure we have a destination pointer.
    val aggDest = if (isAggResult) {
      destPtr ?: builder.emit(
        IrAlloca("", IrPointer(resultType), resultType)
      )
    } else null

    val thenLabel = builder.freshLocalName("if.then")
    val elseLabel = builder.freshLocalName("if.else")
    val mergeLabel = builder.freshLocalName("if.merge")
    var thenPred: String? = null
    var elsePred: String? = null

    builder.emitTerminator(
      IrBranch(
        name = "",
        type = IrPrimitive(PrimitiveKind.UNIT),
        condition = condition,
        trueTarget = thenLabel,
        falseTarget = elseLabel,
      )
    )


    val thenBlock = builder.ensureBlock(thenLabel)
    builder.positionAt(function, thenBlock)
    val thenValue = if (isAggResult) {
      blockEmitter.emitBlock(node.expr, expectValue = true, expectedType = resultType, destPtr = aggDest)
        ?: unitValue()
    } else {
      blockEmitter.emitBlock(node.expr, expectValue = needsValue, expectedType = resultType) ?: unitValue()
    }
    val thenActive = builder.hasInsertionPoint()
    if (needsValue && thenActive && !isAggResult) ensureSameType(resultType, thenValue.type)
    if (thenActive) {
      thenPred = builder.currentBlockLabel()
      builder.emitTerminator(IrJump(name = "", type = IrPrimitive(PrimitiveKind.UNIT), target = mergeLabel))
    }

    val elseBlock = builder.ensureBlock(elseLabel)
    builder.positionAt(function, elseBlock)
    val elseValue = when (val elseExpr = node.elseExpr) {
      null -> if (needsValue) IrUndef(resultType) else unitValue()
      is BlockExprNode -> {
        if (isAggResult) {
          blockEmitter.emitBlock(elseExpr, expectValue = true, expectedType = resultType, destPtr = aggDest)
            ?: if (needsValue) IrUndef(resultType) else unitValue()
        } else {
          blockEmitter.emitBlock(elseExpr, expectValue = needsValue, expectedType = resultType)
            ?: if (needsValue) IrUndef(resultType) else unitValue()
        }
      }
      else -> emitExpr(elseExpr, expectedType = resultType, destPtr = aggDest)
    }
    val elseActive = builder.hasInsertionPoint()
    if (needsValue && elseActive && !isAggResult) ensureSameType(resultType, elseValue.type)
    if (elseActive) {
      elsePred = builder.currentBlockLabel()
      builder.emitTerminator(IrJump(name = "", type = IrPrimitive(PrimitiveKind.UNIT), target = mergeLabel))
    }

    val mergeBlock = builder.ensureBlock(mergeLabel)
    builder.positionAt(function, mergeBlock)

    if (isAggResult) {
      // Aggregate result: the destination pointer already has the value written by
      // whichever branch executed. Return the pointer.
      return aggDest!!
    }

    return if (needsValue) {
      if (thenActive && elseActive) {
        builder.emit(
          IrPhi(
            name = "",
            type = resultType,
            incoming = listOf(PhiBranch(thenValue, thenPred ?: thenLabel), PhiBranch(elseValue, elsePred ?: elseLabel))
          )
        )
      } else if (thenActive) {
        thenValue
      } else if (elseActive) {
        elseValue
      } else error("both then and else returning void for If")
    } else {
      unitValue()
    }
  }

  private fun emitWhile(node: WhileExprNode): IrValue {
    val function = context.currentFunction ?: error("No active function")
    val condLabel = builder.freshLocalName("while.cond")
    val bodyLabel = builder.freshLocalName("while.body")
    val linkLabel = builder.freshLocalName("while.linker")
    val exitLabel = builder.freshLocalName("while.end")

    builder.emitTerminator(IrJump(name = "", type = IrPrimitive(PrimitiveKind.UNIT), target = condLabel))


    val condBlock = builder.ensureBlock(condLabel)
    builder.positionAt(function, condBlock)
    val condExpr = node.conds.firstOrNull()?.expr ?: error("while without condition")
    val condition = emitExpr(condExpr)
    builder.emitTerminator(
      IrBranch(
        name = "",
        type = IrPrimitive(PrimitiveKind.UNIT),
        condition = condition,
        trueTarget = bodyLabel,
        falseTarget = linkLabel,
      )
    )

    val linkBlock = builder.ensureBlock(linkLabel)
    builder.positionAt(function, linkBlock)
    builder.emitTerminator(
      IrJump(name = "", type = IrPrimitive(PrimitiveKind.UNIT), target = exitLabel)
    )



    val bodyBlock = builder.ensureBlock(bodyLabel)
    builder.positionAt(function, bodyBlock)
    valueEnv.pushLoop(breakTarget = linkLabel, continueTarget = condLabel)
    blockEmitter.emitBlock(node.expr, expectValue = false)
    valueEnv.popLoop()
    if (builder.hasInsertionPoint()) {
      builder.emitTerminator(IrJump(name = "", type = IrPrimitive(PrimitiveKind.UNIT), target = condLabel))
    }

    val exitBlock = builder.ensureBlock(exitLabel)
    builder.positionAt(function, exitBlock)
    return unitValue()
  }

  //not used!
  private fun emitLoop(node: LoopExprNode): IrValue {
    val function = context.currentFunction ?: error("No active function")
    val bodyLabel = builder.freshLocalName("loop.body")
    val exitLabel = builder.freshLocalName("loop.end")

    builder.emitTerminator(IrJump(name = "", type = IrPrimitive(PrimitiveKind.UNIT), target = bodyLabel))

    val bodyBlock = builder.ensureBlock(bodyLabel)
    builder.positionAt(function, bodyBlock)
    valueEnv.pushLoop(breakTarget = exitLabel, continueTarget = bodyLabel)
    blockEmitter.emitBlock(node.expr, expectValue = false)
    valueEnv.popLoop()
    if (builder.hasInsertionPoint()) {
      builder.emitTerminator(IrJump(name = "", type = IrPrimitive(PrimitiveKind.UNIT), target = bodyLabel))
    }

    val exitBlock = builder.ensureBlock(exitLabel)
    builder.positionAt(function, exitBlock)
    return unitValue()
  }

  //no break with expression
  private fun emitBreak(node: BreakExprNode): IrValue {
    val target = valueEnv.currentBreakTarget() ?: error("break outside loop")
    builder.emitTerminator(IrJump(name = "", type = IrPrimitive(PrimitiveKind.UNIT), target = target))
    return IrUndef(IrPrimitive(PrimitiveKind.NEVER))
  }

  private fun emitContinue(): IrValue {
    val target = valueEnv.currentContinueTarget() ?: error("continue outside loop")
    builder.emitTerminator(IrJump(name = "", type = IrPrimitive(PrimitiveKind.UNIT), target = target))
    return IrUndef(IrPrimitive(PrimitiveKind.NEVER))
  }

  /**
   * Emit a function call.
   *
   * For sret (aggregate return): if [destPtr] is provided, passes it directly as the
   * sret pointer, avoiding a temporary alloca + copy. Returns the pointer.
   */
  private fun emitCall(node: CallExprNode, destPtr: IrValue? = null): IrValue {
    val calleePath = node.expr as? PathExprNode ?: error("function calls require path callee")
    val fnName = calleePath.seg2?.name ?: calleePath.seg1.name ?: error("unsupported callee path")
    val fnSymbol = node.functionSymbol!!
    val signature = irFunctionSignature(fnSymbol)
    val irName = if (calleePath.seg2 != null) {
      val typeName = calleePath.seg1.name ?: error("type path missing name")
      "$typeName.$fnName."
    } else {
      (calleePath.seg1.name + ".")
    }
    val args = mutableListOf<IrValue>()
    var sretSlot: IrValue? = null
    signature.sretType?.let {
      // Use caller-provided destPtr if available, otherwise alloca a temp.
      sretSlot = destPtr ?: builder.emit(
        IrAlloca(
          builder.freshLocalName("sret"),
          IrPointer(it),
          it,
        )
      )
      args.add(sretSlot!!)
    }
    node.params.mapIndexed { _, expr ->
      args.add(emitExpr(expr))
    }

    val callResult = builder.emit(
      IrCall(
        name = "",
        type = signature.actualReturnType,
        callee = IrFunctionRef(irName, signature.toFunctionPointer()),
        arguments = args,
      ),
    )
    return if (signature.sretType != null) {
      // Return the sret pointer directly — no whole-aggregate load.
      sretSlot!!
    } else {
      callResult
    }
  }

  /**
   * Emit a method call.
   *
   * For sret (aggregate return): if [destPtr] is provided, passes it directly as the
   * sret pointer, avoiding a temporary alloca + copy. Returns the pointer.
   */
  private fun emitMethodCall(node: MethodCallExprNode, destPtr: IrValue? = null): IrValue {
    val fnName = node.pathSeg.name ?: error("method name missing")
    val receiverType = toIrType(node.receiverType!!)
    val fnSymbol = node.methodSymbol!!
    val signature = irFunctionSignature(fnSymbol)
    val baseType = (receiverType as? IrPointer)?.pointee ?: receiverType
    val ownerName = (baseType as? IrStruct)?.name ?: error("method without owner")
    val irName = "$ownerName.$fnName."
    val selfParamType = if (signature.sretType != null) {
      signature.parameters.getOrNull(1)
    } else {
      signature.parameters.firstOrNull()
    }

    val args = mutableListOf<IrValue>()
    var sretSlot: IrValue? = null
    signature.sretType?.let {
      sretSlot = destPtr ?: builder.emit(
        IrAlloca(
          builder.freshLocalName("sret"),
          IrPointer(it),
          it,
        )
      )
      args.add(sretSlot!!)
    }
    val selfValue = if (receiverType is IrPointer) {
      val baseRef = emitExpr(node.expr)
      if (selfParamType is IrPointer) {
        baseRef
      } else {
        builder.emit(
          IrLoad("", baseType, baseRef)
        )
      }
    } else {
      if (selfParamType is IrPointer) {
        tryLValue(node.expr) ?: {
          val baseValue = emitExpr(node.expr)
          // If the baseValue is already a pointer (aggregate), use it directly.
          if (baseValue.type is IrPointer && isAggregate((baseValue.type as IrPointer).pointee)) {
            baseValue
          } else {
            val ret = builder.emit(
              IrAlloca("", IrPointer(baseValue.type), baseValue.type)
            )
            builder.emit(
              IrStore("", IrPrimitive(PrimitiveKind.UNIT), ret, baseValue)
            )
            ret
          }
        }()
      } else {
        emitExpr(node.expr)
      }
    }
    args.add(selfValue)
    node.params.mapIndexed { index, expr ->
      args.add(emitExpr(expr))
    }
    val callResult = builder.emit(
      IrCall(
        name = "",
        type = signature.actualReturnType,
        callee = IrFunctionRef(irName, signature.toFunctionPointer()),
        arguments = args,
      ),
    )
    return if (signature.sretType != null) {
      sretSlot!!
    } else {
      callResult
    }
  }

  /**
   * Emit an array literal.
   *
   * Stores elements directly into [destPtr] if provided, otherwise allocates a temp.
   * Returns a pointer to the array storage — never a whole-aggregate SSA value.
   */
  private fun emitArrayExpr(node: ArrayExprNode, destPtr: IrValue? = null): IrValue {
    val type = node.type as? ArrayType ?: error("array literal without type")
    val irType = toIrType(type) as IrArray
    val dest = destPtr ?: builder.emit(
      IrAlloca("", IrPointer(irType), irType)
    )
    storeArrayToPointer(node, dest)
    return dest
  }

  /**
   * Emit a struct literal.
   *
   * Stores fields directly into [destPtr] if provided, otherwise allocates a temp.
   * Returns a pointer to the struct storage — never a whole-aggregate SSA value.
   */
  private fun emitStructExpr(node: StructExprNode, destPtr: IrValue? = null): IrValue {
    val path = node.path as? PathExprNode ?: error("struct literal requires path")
    val irType = structLayout(node.type as StructType)
    val dest = destPtr ?: builder.emit(
      IrAlloca("", IrPointer(irType), irType)
    )
    storeStructToPointer(node, dest)
    return dest
  }

  /**
   * Emit a return statement.
   *
   * For aggregate returns (sret): writes the return value directly into the sret
   * pointer using destination-passing — no whole-aggregate SSA value.
   */
  private fun emitReturn(node: ReturnExprNode): IrValue {
    val expectedType = valueEnv.currentReturnType()
    val sretPtr = valueEnv.resolve(SRET_BINDING) as? Bind.Pointer
    if (sretPtr != null) {
      // Aggregate return via sret: emit directly into the sret pointer.
      if (node.expr != null) {
        emitExpr(node.expr, expectedType = expectedType, destPtr = sretPtr.addr)
      }
      builder.emitTerminator(
        IrReturn(
          name = "",
          type = IrPrimitive(PrimitiveKind.UNIT),
          value = null,
        )
      )
    } else {
      val value = node.expr?.let { emitExpr(it, expectedType = expectedType) }
      val irValue = when {
        value != null -> value
        expectedType is IrPrimitive && expectedType.kind == PrimitiveKind.UNIT -> null
        else -> IrUndef(expectedType)
      }
      builder.emitTerminator(
        IrReturn(
          name = "",
          type = expectedType,
          value = irValue,
        )
      )
    }
    return IrUndef(IrPrimitive(PrimitiveKind.NEVER))
  }

  /**
   * Emit a field access expression.
   *
   * For scalar fields: returns the loaded scalar value.
   * For aggregate fields: returns a pointer to the field. If [destPtr] is provided,
   * copies the aggregate field into [destPtr].
   */
  private fun emitFieldAccess(node: FieldAccessExprNode, destPtr: IrValue? = null): IrValue {
    val lvalue = emitLValue(node)
    val innerType = getLValueInnerType(lvalue)
    if (isAggregate(innerType)) {
      if (destPtr != null) {
        builder.emitAggregateCopy(destPtr, lvalue, innerType)
        return destPtr
      }
      return lvalue
    }
    return builder.emit(
      IrLoad(
        name = "",
        type = innerType,
        address = lvalue,
      )
    )
  }

  /**
   * Emit an index access expression.
   *
   * For scalar elements: returns the loaded scalar value.
   * For aggregate elements: returns a pointer. If [destPtr] is provided,
   * copies the aggregate element into [destPtr].
   */
  private fun emitIndexAccess(node: IndexExprNode, destPtr: IrValue? = null): IrValue {
    val lvalue = emitLValue(node)
    val innerType = getLValueInnerType(lvalue)
    if (isAggregate(innerType)) {
      if (destPtr != null) {
        builder.emitAggregateCopy(destPtr, lvalue, innerType)
        return destPtr
      }
      return lvalue
    }
    return builder.emit(
      IrLoad(
        name = "",
        type = innerType,
        address = lvalue,
      )
    )
  }

  private fun emitLValue(node: ExprNode): IrValue = when (node) {
    is PathExprNode -> {
      if (node.seg2 != null) {
        error("Qualified paths are not supported in expression lowering yet")
      }
      val identifier = node.seg1.name ?: "self"
      when (val it = valueEnv.resolve(identifier)) {
        is Bind.Value -> {
          val ret = builder.emit(
            IrAlloca(
              "",
              IrPointer(it.value.type),
              it.value.type
            )
          )
          builder.emit(
            IrStore("", IrPrimitive(PrimitiveKind.UNIT), ret, it.value)
          )
          valueEnv.bind(identifier, Bind.Pointer(ret))
          ret
        }

        is Bind.Pointer -> {
          it.addr
        }

        else -> {
          error("Unbound lvalue identifier $identifier")
        }
      }
    }

    is DerefExprNode -> emitExpr(node.expr)

    is FieldAccessExprNode -> {
      var base = emitLValue(node.expr) as? IrLocal ?: error("cannot find proper base for field access")
      val innerType = getLValueInnerType(base)
      val structType = when (innerType) {
        is IrStruct -> innerType
        is IrPointer -> innerType.pointee as? IrStruct
        else -> null
      } ?: error("field access on non-struct")

      if (innerType is IrPointer) {
        base = builder.emit(
          IrLoad(
            name = "",
            type = innerType,
            address = base,
          )
        )
      }
      val index = fieldIndex(node.structSymbol!!.type, node.id)
      builder.emit(
        IrGep(
          name = "",
          type = IrPointer(structType.fields[index]),
          base = base,
          indices = listOf(
            IrConstant(0, IrPrimitive(PrimitiveKind.I32)),
            IrConstant(index.toLong(), IrPrimitive(PrimitiveKind.I32)),
          ),
        ),
      )
    }

    is IndexExprNode -> {
      var base = emitLValue(node.base)
      //base is IrPointer(innerType) innerType: IrArray, IrPointer(IrArray), elementType, IrPointer(elementType)?
      val innerType = getLValueInnerType(base)

      val indexValue = emitExpr(node.index)

      if (innerType is IrPointer) {
        base = builder.emit(
          IrLoad(
            name = "",
            type = innerType,
            address = base,
          )
        )
      }
      //now base_type is either IrPointer(IrArray) or IrPointer(elementType)

      val elementType = when (val it = getLValueInnerType(base)) {
        is IrArray -> it.element
        else -> it
      }
      val indice = if (getLValueInnerType(base) is IrArray) {
        listOf(
          IrConstant(0, IrPrimitive(PrimitiveKind.I32)),
          indexValue
        )
      } else listOf(indexValue)

      builder.emit(
        IrGep(
          name = "",
          type = IrPointer(elementType),
          base = base,
          indices = indice
        ),
      )
    }

    else -> error(
      "Unsupported lvalue expression ${
        node::
        class.simpleName
      }"
    )
  }


  private fun tryLValue(expr: ExprNode): IrValue? = when (expr) {
    is PathExprNode, is DerefExprNode, is FieldAccessExprNode, is IndexExprNode -> {
      try {
        emitLValue(expr)
      } catch (_: Exception) {
        null
      }
    }

    else -> null
  }

  private fun mapType(node: TypeNode): Type = when (node) {
    is TypePathNode -> when (node.name) {
      "i32" -> Int32Type
      "u32" -> UInt32Type
      "isize" -> ISizeType
      "usize" -> USizeType
      "bool" -> BoolType
      "char" -> CharType
      else -> error("Unsupported cast target type ${node.name}")
    }

    is RefTypeNode -> RefType(mapType(node.type), node.hasMut)
    is ArrayTypeNode -> {
      val element = mapType(node.type)
      if (node.evaluatedSize < 0) CompileError.fail("", "Array size must be known for cast")
      ArrayType(element, node.evaluatedSize.toInt())
    }

    UnitTypeNode -> UnitType
  }

  private fun ensureSameType(lhs: IrType, rhs: IrType) {
    if (lhs == rhs) return
    if (lhs is IrPrimitive && rhs is IrPrimitive && lhs.render() == rhs.render()) return
    error("Type mismatch: $lhs vs $rhs")
  }

  private fun retargetPointer(value: IrValue, targetType: IrPointer): IrValue =
    if (value.type == targetType) {
      value
    } else if (value.type is IrPointer) {
      builder.emit(
        IrCast(
          name = "",
          type = targetType,
          value = value,
          kind = CastKind.BITCAST,
        )
      )
    } else {
      error("retargetPointer on non-pointer type ${value.type}")
    }

  private fun bitWidth(type: IrPrimitive): Int = when (type.kind) {
    PrimitiveKind.BOOL -> 1
    PrimitiveKind.CHAR -> 8
    PrimitiveKind.I32, PrimitiveKind.U32, PrimitiveKind.ISIZE, PrimitiveKind.USIZE -> 32
    PrimitiveKind.UNIT, PrimitiveKind.NEVER -> 0
  }

  private fun isUnsigned(type: IrType): Boolean =
    (type as? IrPrimitive)?.kind in setOf(PrimitiveKind.U32, PrimitiveKind.USIZE)

  private fun unitValue(): IrValue = IrUndef(IrPrimitive(PrimitiveKind.UNIT))


  private fun fieldIndex(semantic: StructType, fieldName: String): Int {
    val names = semantic.fields.keys.toList()
    val idx = names.indexOf(fieldName)
    if (idx == -1) error("Field $fieldName not found")
    return idx
  }

  fun storeStructToPointer(valueExpr: StructExprNode, toDst: IrValue) {
    val path = valueExpr.path as? PathExprNode ?: error("struct literal requires path")
    val irType = structLayout(valueExpr.type as StructType)
    irType.fields.forEachIndexed { index, type ->
      val indexAddr = builder.emit(
        IrGep(
          "", IrPointer(type), toDst, listOf(
            IrConstant(0, IrPrimitive(PrimitiveKind.I32)),
            IrConstant(index.toLong(), IrPrimitive(PrimitiveKind.I32))
          )
        )
      )
      if (isAggregate(type)) {
        // For nested aggregate fields, use destination-passing.
        emitExpr(valueExpr.fields[index].expr!!, destPtr = indexAddr)
      } else {
        val indexExpr = emitExpr(valueExpr.fields[index].expr!!)
        builder.emit(
          IrStore("", IrPrimitive(PrimitiveKind.UNIT), indexAddr, indexExpr)
        )
      }
    }
  }

  fun storeArrayToPointer(valueExpr: ArrayExprNode, toDst: IrValue) {
    val arrType = valueExpr.type as? ArrayType ?: error("array literal without type")
    val irArray = toIrType(arrType) as IrArray
    val elemType = irArray.element
    val i32 = IrPrimitive(PrimitiveKind.I32)
    val unit = IrPrimitive(PrimitiveKind.UNIT)

    if (valueExpr.repeatOp != null && valueExpr.lengthOp != null) {
      val count = valueExpr.evaluatedSize.takeIf { it >= 0 }?.toInt()
        ?: error("array repeat size unknown")
      if (count == 0) return

      // Small repeat arrays: unroll for better optimization.
      if (count <= ARRAY_UNROLL_THRESHOLD) {
        // For scalar repeat values, evaluate once and store N times.
        if (!isAggregate(elemType)) {
          val repeated = emitExpr(valueExpr.repeatOp)
          for (i in 0 until count) {
            val elemPtr = builder.emit(
              IrGep("", IrPointer(elemType), toDst, listOf(IrConstant(0, i32), IrConstant(i.toLong(), i32)))
            )
            builder.emit(IrStore("", unit, elemPtr, repeated))
          }
        } else {
          // Aggregate repeat: use destination-passing per element.
          for (i in 0 until count) {
            val elemPtr = builder.emit(
              IrGep("", IrPointer(elemType), toDst, listOf(IrConstant(0, i32), IrConstant(i.toLong(), i32)))
            )
            emitExpr(valueExpr.repeatOp, destPtr = elemPtr)
          }
        }
        return
      }

      // Large repeat arrays: use a loop.
      val idxPtr = builder.emit(IrAlloca(builder.freshLocalName("fill.idx"), IrPointer(i32), i32))
      builder.emit(IrStore("", unit, idxPtr, IrConstant(0, i32)))

      val fn = context.currentFunction ?: error("no active function")
      val condLabel = builder.freshLocalName("array.fill.cond")
      val bodyLabel = builder.freshLocalName("array.fill.body")
      val endLabel = builder.freshLocalName("array.fill.end")

      builder.emitTerminator(IrJump("", unit, condLabel))

      // cond
      val condBlock = builder.ensureBlock(condLabel)
      builder.positionAt(fn, condBlock)
      val idxVal = builder.emit(IrLoad("", i32, idxPtr))
      val cmp = builder.emit(
        IrCmp("", IrPrimitive(PrimitiveKind.BOOL), ComparePredicate.SLT, idxVal, IrConstant(count.toLong(), i32))
      )
      builder.emitTerminator(IrBranch("", unit, cmp, bodyLabel, endLabel))

      // body
      val bodyBlock = builder.ensureBlock(bodyLabel)
      builder.positionAt(fn, bodyBlock)
      val curIdx = builder.emit(IrLoad("", i32, idxPtr))
      val elemPtr = builder.emit(
        IrGep("", IrPointer(elemType), toDst, listOf(IrConstant(0, i32), curIdx))
      )
      if (isAggregate(elemType)) {
        emitExpr(valueExpr.repeatOp, destPtr = elemPtr)
      } else {
        val repeated = emitExpr(valueExpr.repeatOp)
        builder.emit(IrStore("", unit, elemPtr, repeated))
      }
      val nextIdx = builder.emit(
        IrBinary("", i32, BinaryOperator.ADD, curIdx, IrConstant(1, i32))
      )
      builder.emit(IrStore("", unit, idxPtr, nextIdx))
      builder.emitTerminator(IrJump("", unit, condLabel))

      // end
      val endBlock = builder.ensureBlock(endLabel)
      builder.positionAt(fn, endBlock)
      return
    }

    // Explicit element list
    valueExpr.elements.forEachIndexed { idx, node ->
      val elemPtr = builder.emit(
        IrGep("", IrPointer(elemType), toDst, listOf(IrConstant(0, i32), IrConstant(idx.toLong(), i32)))
      )
      if (isAggregate(elemType)) {
        emitExpr(node, destPtr = elemPtr)
      } else {
        builder.emit(IrStore("", unit, elemPtr, emitExpr(node)))
      }
    }
  }

  companion object {
    /** Arrays with repeat count at or below this threshold are unrolled instead of looped. */
    private const val ARRAY_UNROLL_THRESHOLD = 16
  }

}
