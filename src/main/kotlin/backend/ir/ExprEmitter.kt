package backend.ir

import frontend.Keyword
import frontend.Literal
import frontend.Punctuation
import frontend.assignOp
import frontend.ast.*
import frontend.semantic.*
import frontend.semantic.Enum
import frontend.semantic.Function
import utils.CompileError

/**
 * Expression emitter handling the currently supported lowering surface.
 * Unsupported expressions fail fast so missing coverage is obvious while we grow the backend.
 */
class ExprEmitter(
  private val context: CodegenContext,
  private val builder: IrBuilder = context.builder,
  private val valueEnv: ValueEnv = context.valueEnv,
) {
  private val blockEmitter by lazy { FunctionEmitter(context, builder, this, valueEnv) }

  fun emitExpr(node: ExprNode, expectedType: IrType? = null): IrValue = when (node) {
    is LiteralExprNode -> emitLiteral(node)
    is PathExprNode -> emitPath(node)
    is GroupedExprNode -> emitExpr(node.expr, expectedType)
    is ArrayExprNode -> emitArrayLiteral(node)
    is BlockExprNode -> emitBlockExpr(node, expectedType)
    is BinaryExprNode -> emitBinary(node)
    is UnaryExprNode -> emitUnary(node)
    is CastExprNode -> emitCast(node)
    is BorrowExprNode -> emitBorrow(node)
    is DerefExprNode -> emitDeref(node)
    is IfExprNode -> emitIf(node, expectedType)
    is WhileExprNode -> emitWhile(node)
    is LoopExprNode -> emitLoop(node)
    is BreakExprNode -> emitBreak(node)
    is ContinueExprNode -> emitContinue()
    is CallExprNode -> emitCall(node)
    is MethodCallExprNode -> emitMethodCall(node)
    is StructExprNode -> emitStructLiteral(node)
    is ReturnExprNode -> emitReturn(node)
    is FieldAccessExprNode -> emitFieldAccess(node)
    is IndexExprNode -> emitIndexAccess(node)
    else -> error("Unsupported expression: ${node::class.simpleName}")
  }

  private fun emitLiteral(node: LiteralExprNode): IrValue = when (node.type) {
    Keyword.TRUE -> builder.borrow(null,IrBoolConstant(true, IrPrimitive(PrimitiveKind.BOOL)))
    Keyword.FALSE -> builder.borrow(null,IrBoolConstant(false, IrPrimitive(PrimitiveKind.BOOL)))
    Literal.INTEGER -> {
      val intConst = getInt(node)
      val irType = toIrType(intConst.actualType)
      builder.borrow(null,IrIntConstant(intConst.value, irType))
    }
    else -> error("Unsupported literal ${node.type}")
  }

  private fun emitPath(node: PathExprNode):  IrValue {
    if (node.seg2 != null) {
      error("Qualified paths are not supported in expression lowering yet")
    }
    val identifier = node.seg1.name ?: "self"
    return valueEnv.resolve(identifier) ?: resolveConstant(identifier)
    ?: error("Unbound identifier $identifier")
  }

  private fun emitBinary(node: BinaryExprNode):  IrValue =
    if (node.op in assignOp) emitAssignment(node) else emitBinaryOp(node)

  private fun emitAssignment(node: BinaryExprNode):  IrValue {
    val lhs = emitExpr(node.lhs)
    val rhs = emitExpr(node.rhs)
    val stored = when (node.op) {
      Punctuation.EQUAL -> rhs
      Punctuation.PLUS_EQUAL -> emitArithmetic(BinaryOperator.ADD, lhs, rhs)
      Punctuation.MINUS_EQUAL -> emitArithmetic(BinaryOperator.SUB, lhs, rhs)
      Punctuation.STAR_EQUAL -> emitArithmetic(BinaryOperator.MUL, lhs, rhs)
      Punctuation.SLASH_EQUAL -> emitArithmetic(
        if (isUnsigned(lhs.type)) BinaryOperator.UDIV else BinaryOperator.SDIV,
        lhs,
        rhs,
      )

      Punctuation.PERCENT_EQUAL -> emitArithmetic(
        if (isUnsigned(lhs.type)) BinaryOperator.UREM else BinaryOperator.SREM,
        lhs,
        rhs,
      )

      Punctuation.AND_EQUAL -> emitArithmetic(BinaryOperator.AND, lhs, rhs)
      Punctuation.OR_EQUAL -> emitArithmetic(BinaryOperator.OR, lhs, rhs)
      Punctuation.CARET_EQUAL -> emitArithmetic(BinaryOperator.XOR, lhs, rhs)
      Punctuation.LESS_LESS_EQUAL -> emitArithmetic(BinaryOperator.SHL, lhs, rhs)
      Punctuation.GREATER_GREATER_EQUAL -> emitArithmetic(
        if (isUnsigned(lhs.type)) BinaryOperator.LSHR else BinaryOperator.ASHR,
        lhs,
        rhs,
      )

      else -> error("Unsupported assignment operator ${node.op}")
    }
    return unitValue()
  }

  private fun emitBinaryOp(node: BinaryExprNode):  IrValue {
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

      Punctuation.AND_AND -> emitShortCircuitAnd(lhs) { emitExpr(node.rhs) }
      Punctuation.OR_OR -> emitShortCircuitOr(lhs) { emitExpr(node.rhs) }
      Punctuation.AMPERSAND -> emitLogical(BinaryOperator.AND, lhs, rhs)
      Punctuation.PIPE -> emitLogical(BinaryOperator.OR, lhs, rhs)
      Punctuation.CARET -> emitLogical(BinaryOperator.XOR, lhs, rhs)
      else -> error("Unsupported binary operator ${node.op}")
    }
  }


  private fun emitArithmetic(operator: BinaryOperator, lhs: IrValue, rhs: IrValue):  IrValue {
    var left = lhs
    var right = rhs
    val lhsUnit = (lhs.type as? IrPrimitive)?.kind == PrimitiveKind.UNIT
    val rhsUnit = (rhs.type as? IrPrimitive)?.kind == PrimitiveKind.UNIT
    if (lhsUnit && !rhsUnit) {
      left = zeroOfType(rhs.type)
    } else if (rhsUnit && !lhsUnit) {
      right = zeroOfType(lhs.type)
    } else if (lhsUnit ) {
      val intType = IrPrimitive(PrimitiveKind.I32)
      left = IrIntConstant(0, intType)
      right = IrIntConstant(0, intType)
    }
    ensureSameType(left.type, right.type)
    val leftValue = builder.emit(IrLoad("",left.type,left))
    val rightValue = builder.emit(IrLoad("",right.type,right))
    return builder.emit(
      IrBinary(
        name = "",
        type = leftValue.type,
        operator = operator,
        lhs = leftValue,
        rhs = rightValue,
      )
    )
  }

  private fun emitCompare(predicate: ComparePredicate, lhs: IrValue, rhs: IrValue):  IrValue {
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
      left = IrIntConstant(0, intType)
      right = IrIntConstant(0, intType)
    }
    ensureSameType(left.type, right.type)
    val leftValue = builder.emit(IrLoad("",left.type,left))
    val rightValue = builder.emit(IrLoad("",right.type,right))
    return builder.emit(
      IrCmp(
        name = "",
        type = IrPrimitive(PrimitiveKind.BOOL),
        predicate = predicate,
        lhs = leftValue,
        rhs = rightValue,
      ),
    )
  }

  private fun zeroOfType(type: IrType):  IrValue = when (type) {
    is IrPrimitive -> IrIntConstant(0, type)
    is IrPointer -> IrIntConstant(0, IrPrimitive(PrimitiveKind.ISIZE))
    else -> IrIntConstant(0, IrPrimitive(PrimitiveKind.I32))
  }

  private fun emitLogical(operator: BinaryOperator, lhs: IrValue, rhs: IrValue): IrValue {
    ensureSameType(lhs.type, rhs.type)
    val leftValue = builder.emit(IrLoad("",lhs.type,lhs))
    val rightValue = builder.emit(IrLoad("",rhs.type,rhs))
    return builder.emit(
      IrBinary(
        name = "",
        type = lhs.type,
        operator = operator,
        lhs = leftValue,
        rhs = rightValue,
      )
    )
  }

  private fun emitShortCircuitAnd(lhs: IrValue, rhsThunk: () -> IrValue):  IrValue {
    return emitShortCircuit(lhs, rhsThunk, shortCircuitOnTrue = false)
  }

  private fun emitShortCircuitOr(lhs: IrValue, rhsThunk: () -> IrValue):  IrValue {
    return emitShortCircuit(lhs, rhsThunk, shortCircuitOnTrue = true)
  }

  private fun emitShortCircuit(lhs: IrValue, rhsThunk: () -> IrValue, shortCircuitOnTrue: Boolean): IrValue {
    val boolType = IrPrimitive(PrimitiveKind.BOOL)
    if (lhs.type != boolType) error("Short-circuit operators require boolean operands")
    val lhsValue = builder.emit(IrLoad("",lhs.type,lhs))
    val function = context.currentFunction ?: error("No active function for short-circuit emission")
    val resultAddr = builder.borrow(null, lhsValue)
    val rhsLabel = builder.freshLocalName(if (shortCircuitOnTrue) "or.rhs" else "and.rhs")
    val mergeLabel = builder.freshLocalName("sc.merge")
    val rhsBlock = builder.ensureBlock(rhsLabel)
    val mergeBlock = builder.ensureBlock(mergeLabel)

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

    builder.positionAt(function, rhsBlock)
    val rhs = rhsThunk()
    if (rhs.type != boolType) error("Short-circuit RHS must be boolean")
    builder.copy(rhs,resultAddr)

    builder.emitTerminator(IrJump(name = "", type = IrPrimitive(PrimitiveKind.UNIT), target = mergeLabel))

    builder.positionAt(function, mergeBlock)
    return resultAddr
  }

  private fun emitUnary(node: UnaryExprNode): IrValue {
    val operand = emitExpr(node.rhs)
    val operator = when (node.op) {
      Punctuation.MINUS -> UnaryOperator.NEG
      Punctuation.BANG -> UnaryOperator.NOT
      else -> error("Unsupported unary operator ${node.op}")
    }
    val rightValue = builder.emit(IrLoad("",operand.type,operand))
    return builder.emit(
      IrUnary(
        name = "",
        type = operand.type,
        operator = operator,
        operand = rightValue,
      )
    )
  }

  private fun emitCast(node: CastExprNode):  IrValue {
    val value = emitExpr(node.expr)
    val targetType = mapType(node.targetType)
    val irTarget = toIrType(targetType)
    val valueType = value.type
    if (irTarget == valueType) return value
    val kind = when {
      valueType is IrPrimitive && irTarget is IrPrimitive -> when {
        valueType.kind == PrimitiveKind.BOOL -> CastKind.ZEXT
        irTarget.kind == PrimitiveKind.BOOL -> CastKind.TRUNC
        else -> CastKind.BITCAST
      }

      else -> CastKind.BITCAST
    }
    val loadValue = builder.emit(IrLoad("",value.type,value))
    return builder.emit(
      IrCast(
        name = "",
        type = irTarget,
        value = loadValue,
        kind = kind,
      ),
    )
  }

  private fun emitBorrow(node: BorrowExprNode): IrValue {
    val baseAddr = emitExpr(node.expr)
    return builder.borrow(null, baseAddr)
  }

  private fun emitDeref(node: DerefExprNode): IrValue {
    val pointer = emitExpr(node.expr)
    val pointerType = pointer.type as? IrPointer
      ?: error("Cannot dereference non-pointer type ${pointer.type}")
    return builder.emit(
      IrLoad(
        id = -1,
        type = pointerType.pointee,
        address = pointer,
      ),
    )
  }

  private fun emitBlockExpr(block:BlockExprNode, expectedType: IrType?): IrValue {
    val value = blockEmitter.emitBlock(block, expectValue = block.hasFinal())
    return value ?: expectedType?.let { IrUndef(it) } ?: unitValue()
  }

  private fun emitIf(node: IfExprNode, expectedType: IrType?): IrValue {
    val function = context.currentFunction ?: error("No active function")
    val condExpr = node.conds.firstOrNull()?.expr ?: error("if without condition")
    val condition = emitExpr(condExpr)
    val boolType = IrPrimitive(PrimitiveKind.BOOL)
    if (condition.type != boolType) error("if condition must be boolean")

    val resultType = expectedType ?: IrPrimitive(PrimitiveKind.UNIT)
    val needsValue = resultType !is IrPrimitive || resultType.kind != PrimitiveKind.UNIT
    val resultSlot = if (needsValue) {
      builder.emit(
        IrAlloca(
          id = -1,
          type = IrPointer(resultType),
          allocatedType = resultType,
          slotName = builder.freshLocalName("if.res"),
        ),
      )
    } else null

    val thenLabel = builder.freshLocalName("if.then")
    val elseLabel = builder.freshLocalName("if.else")
    val mergeLabel = builder.freshLocalName("if.merge")

    builder.emitTerminator(
      IrBranch(
        id = -1,
        type = IrPrimitive(PrimitiveKind.UNIT),
        condition = condition,
        trueTarget = thenLabel,
        falseTarget = elseLabel,
      ),
    )

    val thenBlock = builder.ensureBlock(thenLabel)
    builder.positionAt(function, thenBlock)
    val thenValue =
      blockEmitter.emitBlock(node.expr, expectValue = needsValue, expectedType = resultType) ?: unitValue()
    val thenActive = builder.hasInsertionPoint()
    if (needsValue && thenActive) ensureSameType(resultType, thenValue.type)
    if (thenActive) {
      if (needsValue) {
        builder.emit(
          IrStore(
            id = -1,
            type = IrPrimitive(PrimitiveKind.UNIT),
            address = resultSlot!!,
            value = thenValue,
          ),
        )
      }
      builder.emitTerminator(IrJump(id = -1, type = IrPrimitive(PrimitiveKind.UNIT), target = mergeLabel))
    }

    val elseBlock = builder.ensureBlock(elseLabel)
    builder.positionAt(function, elseBlock)
    val elseValue = when (val elseExpr = node.elseExpr) {
      null -> if (needsValue) IrUndef(resultType) else unitValue()
      is BlockExprNode -> blockEmitter.emitBlock(
        elseExpr,
        expectValue = needsValue,
        expectedType = resultType
      )
        ?: if (needsValue) IrUndef(resultType) else unitValue()

      else -> emitExpr(elseExpr, expectedType = resultType)
    }
    val elseActive = builder.hasInsertionPoint()
    if (needsValue && elseActive) ensureSameType(resultType, elseValue.type)
    if (elseActive) {
      if (needsValue) {
        builder.emit(
          IrStore(
            id = -1,
            type = IrPrimitive(PrimitiveKind.UNIT),
            address = resultSlot!!,
            value = elseValue,
          ),
        )
      }
      builder.emitTerminator(IrJump(id = -1, type = IrPrimitive(PrimitiveKind.UNIT), target = mergeLabel))
    }

    val mergeBlock = builder.ensureBlock(mergeLabel)
    builder.positionAt(function, mergeBlock)
    return if (needsValue) {
      builder.emit(
        IrLoad(
          id = -1,
          type = resultType,
          address = resultSlot!!,
        ),
      )
    } else {
      unitValue()
    }
  }

  private fun emitWhile(node: WhileExprNode): IrValue {
    val function = context.currentFunction ?: error("No active function")
    val condLabel = builder.freshLocalName("while.cond")
    val bodyLabel = builder.freshLocalName("while.body")
    val exitLabel = builder.freshLocalName("while.end")

    builder.emitTerminator(IrJump(id = -1, type = IrPrimitive(PrimitiveKind.UNIT), target = condLabel))

    val condBlock = builder.ensureBlock(condLabel)
    builder.positionAt(function, condBlock)
    val condExpr = node.conds.firstOrNull()?.expr ?: error("while without condition")
    val condition = emitExpr(condExpr)
    builder.emitTerminator(
      IrBranch(
        id = -1,
        type = IrPrimitive(PrimitiveKind.UNIT),
        condition = condition,
        trueTarget = bodyLabel,
        falseTarget = exitLabel,
      ),
    )

    val bodyBlock = builder.ensureBlock(bodyLabel)
    builder.positionAt(function, bodyBlock)
    valueEnv.pushLoop(breakTarget = exitLabel, continueTarget = condLabel)
    blockEmitter.emitBlock(node.expr, expectValue = false)
    valueEnv.popLoop()
    if (builder.hasInsertionPoint()) {
      builder.emitTerminator(IrJump(id = -1, type = IrPrimitive(PrimitiveKind.UNIT), target = condLabel))
    }

    val exitBlock = builder.ensureBlock(exitLabel)
    builder.positionAt(function, exitBlock)
    return unitValue()
  }

  private fun emitLoop(node: LoopExprNode): IrValue {
    val function = context.currentFunction ?: error("No active function")
    val bodyLabel = builder.freshLocalName("loop.body")
    val exitLabel = builder.freshLocalName("loop.end")

    builder.emitTerminator(IrJump(id = -1, type = IrPrimitive(PrimitiveKind.UNIT), target = bodyLabel))

    val bodyBlock = builder.ensureBlock(bodyLabel)
    builder.positionAt(function, bodyBlock)
    valueEnv.pushLoop(breakTarget = exitLabel, continueTarget = bodyLabel)
    blockEmitter.emitBlock(node.expr, expectValue = false)
    valueEnv.popLoop()
    if (builder.hasInsertionPoint()) {
      builder.emitTerminator(IrJump(id = -1, type = IrPrimitive(PrimitiveKind.UNIT), target = bodyLabel))
    }

    val exitBlock = builder.ensureBlock(exitLabel)
    builder.positionAt(function, exitBlock)
    return unitValue()
  }

  private fun emitBreak(node: BreakExprNode): IrValue {
    if (node.expr != null) error("break with value not supported")
    val target = valueEnv.currentBreakTarget() ?: error("break outside loop")
    builder.emitTerminator(IrJump(id = -1, type = IrPrimitive(PrimitiveKind.UNIT), target = target))
    return IrUndef(IrPrimitive(PrimitiveKind.NEVER))
  }

  private fun emitContinue(): IrValue {
    val target = valueEnv.currentContinueTarget() ?: error("continue outside loop")
    builder.emitTerminator(IrJump(id = -1, type = IrPrimitive(PrimitiveKind.UNIT), target = target))
    return IrUndef(IrPrimitive(PrimitiveKind.NEVER))
  }

  private fun emitCall(node: CallExprNode): IrValue {
    val calleePath = node.expr as? PathExprNode ?: error("function calls require path callee")
    val fnName = calleePath.seg2?.name ?: calleePath.seg1.name ?: error("unsupported callee path")
    val fnSymbol = if (calleePath.seg2 != null) {
      val typeName = calleePath.seg1.name ?: error("type path missing name")
      resolveAssociatedFunction(typeName, fnName) ?: resolveFunction(fnName)
    } else {
      resolveFunction(fnName)
    } ?: error("Unknown function $fnName")
    val signature = irFunctionSignature(fnSymbol)
    val irName = if (calleePath.seg2 != null) {
      val typeName = calleePath.seg1.name ?: error("type path missing name")
      "$typeName.$fnName"
    } else {
      context.irFunctionName(fnSymbol)
    }
    val args = node.params.mapIndexed { index, expr ->
      val expected = signature.parameters.getOrNull(index)
      emitArgument(expr, expected, calleePath.seg1.name ?: "arg$index")
    }
    return builder.emit(
      IrCall(
        id = -1,
        type = signature.returnType,
        callee = IrFunctionRef(irName, signature.toFunctionPointer()),
        arguments = args,
      ),
    )
  }

  private fun emitMethodCall(node: MethodCallExprNode): IrValue {
    val fnName = node.pathSeg.name ?: error("method name missing")
    val receiver = emitExpr(node.expr)
    val fnSymbol = resolveMethod(receiver.type, fnName) ?: resolveFunction(fnName) ?: error("Unknown method $fnName")
    val signature = irFunctionSignature(fnSymbol)
    val baseType = (receiver.type as? IrPointer)?.pointee ?: receiver.type
    val ownerName = (baseType as? IrStruct)?.name
    val irName = ownerName?.let { "$it.$fnName" } ?: context.irFunctionName(fnSymbol)
    val selfParamType = signature.parameters.firstOrNull()
    val coercedReceiver = when {
      selfParamType is IrPointer -> {
        tryLValue(node.expr)?.let { lv ->
          ensureSameType(selfParamType.pointee, lv.pointee)
          retargetPointer(lv.address, selfParamType)
        } ?: coerceArgument(receiver, selfParamType, hint = "self")
      }

      else -> receiver
    }
    val argValues = node.params.mapIndexed { index, expr ->
      val expected = signature.parameters.getOrNull(index + 1) // offset self
      emitArgument(expr, expected, node.pathSeg.name ?: "arg$index")
    }
    val args = listOf(coercedReceiver) + argValues
    return builder.emit(
      IrCall(
        id = -1,
        type = signature.returnType,
        callee = IrFunctionRef(irName, signature.toFunctionPointer()),
        arguments = args,
      ),
    )
  }

  private fun emitArrayLiteral(node: ArrayExprNode): IrValue {
    val elements = if (node.elements.isNotEmpty()) {
      node.elements.map { emitExpr(it) }
    } else if (node.repeatOp != null) {
      val count = node.evaluatedSize.takeIf { it >= 0 }?.toInt()
        ?: error("array repeat size unknown")
      List(count) { emitExpr(node.repeatOp) }
    } else {
      error("empty array literal not supported")
    }
    val elementType = elements.first().type
    val irArrayType = IrArray(elementType, elements.size)
    val alloca = builder.emit(
      IrAlloca(
        id = -1,
        type = IrPointer(irArrayType),
        allocatedType = irArrayType,
        slotName = builder.freshLocalName("arr"),
      ),
    )
    elements.forEachIndexed { index, value ->
      val gep = builder.emit(
        IrGep(
          id = -1,
          type = IrPointer(elementType),
          base = alloca,
          indices = listOf(
            IrIntConstant(0, IrPrimitive(PrimitiveKind.I32)),
            IrIntConstant(index.toLong(), IrPrimitive(PrimitiveKind.I32)),
          ),
        ),
      )
      builder.emit(
        IrStore(
          id = -1,
          type = IrPrimitive(PrimitiveKind.UNIT),
          address = gep,
          value = value,
        ),
      )
    }
    return builder.emit(
      IrLoad(
        id = -1,
        type = irArrayType,
        address = alloca,
      ),
    )
  }

  private fun emitStructLiteral(node: StructExprNode): IrValue {
    val path = node.path as? PathExprNode ?: error("struct literal requires path")
    val typeName = path.seg1.name ?: error("struct literal missing name")
    val structType = resolveStruct(typeName)
    val irType = structLayout(structType)
    val fieldValues = structType.fields.keys.map { fieldName ->
      val fieldExpr = node.fields.find { it.id == fieldName }?.expr
        ?: error("missing field $fieldName in struct literal")
      emitExpr(fieldExpr)
    }
    val alloca = builder.emit(
      IrAlloca(
        id = -1,
        type = IrPointer(irType),
        allocatedType = irType,
        slotName = builder.freshLocalName(typeName),
      ),
    )
    fieldValues.forEachIndexed { index, value ->
      val gep = builder.emit(
        IrGep(
          id = -1,
          type = IrPointer(value.type),
          base = alloca,
          indices = listOf(
            IrIntConstant(0, IrPrimitive(PrimitiveKind.I32)),
            IrIntConstant(index.toLong(), IrPrimitive(PrimitiveKind.I32))
          ),
        ),
      )
      builder.emit(
        IrStore(
          id = -1,
          type = IrPrimitive(PrimitiveKind.UNIT),
          address = gep,
          value = value,
        ),
      )
    }
    return builder.emit(
      IrLoad(
        id = -1,
        type = irType,
        address = alloca,
      ),
    )
  }

  private fun emitReturn(node: ReturnExprNode): IrValue {
    val expectedType = valueEnv.currentReturnType()
    val value = node.expr?.let { emitExpr(it, expectedType = expectedType) }
    val irValue = when {
      value != null -> value
      expectedType is IrPrimitive && expectedType.kind == PrimitiveKind.UNIT -> null
      else -> IrUndef(expectedType)
    }
    builder.emitTerminator(
      IrReturn(
        id = -1,
        type = expectedType,
        value = irValue,
      ),
    )
    return IrUndef(IrPrimitive(PrimitiveKind.NEVER))
  }

  private fun emitFieldAccess(node: frontend.ast.FieldAccessExprNode): IrValue {
    val lvalue = emitLValue(node)
    return builder.emit(
      IrLoad(
        id = -1,
        type = lvalue.pointee,
        address = lvalue.address,
      ),
    )
  }

  private fun emitIndexAccess(node: frontend.ast.IndexExprNode): IrValue {
    val lvalue = emitLValue(node)
    return builder.emit(
      IrLoad(
        id = -1,
        type = lvalue.pointee,
        address = lvalue.address,
      ),
    )
  }

  private fun emitLValue(node: ExprNode): LValue = when (node) {
    is PathExprNode -> {
      if (node.seg2 != null) error("Qualified paths are not supported for assignment")
      val identifier = node.seg1.name ?: "self"
      val binding = valueEnv.resolve(identifier) ?: error("Unbound identifier $identifier")
      when (binding) {
        is StackSlot -> LValue(binding.address, binding.type)
        is SsaValue -> {
          val type = binding.value.type
          if (binding.value is IrParameter && type is IrPointer) {
            LValue(binding.value, type.pointee)
          }
          val address = builder.emit(
            IrAlloca(
              id = -1,
              type = IrPointer(type),
              allocatedType = type,
              slotName = builder.freshLocalName(identifier),
            ),
          )
          builder.emit(
            IrStore(
              id = -1,
              type = IrPrimitive(PrimitiveKind.UNIT),
              address = address,
              value = binding.value,
            ),
          )
          val stackSlot = StackSlot(address, type)
            valueEnv.bind(identifier, stackSlot)
            LValue(address, type)
          }
      }
    }

    is DerefExprNode -> {
      val pointer = emitExpr(node.expr)
      val pointerType = pointer.type as? IrPointer
        ?: error("Cannot dereference non-pointer type ${pointer.type}")
      LValue(pointer, pointerType.pointee)
    }

    is frontend.ast.FieldAccessExprNode -> {
      val base = emitLValue(node.expr)
      var baseAddress = base.address
      val structType = when (val pointee = base.pointee) {
        is IrStruct -> pointee
        is IrPointer -> pointee.pointee as? IrStruct
        else -> null
      } ?: error("field access on non-struct")

      if (base.pointee is IrPointer) {
        baseAddress = builder.emit(
          IrLoad(
            id = -1,
            type = base.pointee,
            address = base.address,
          ),
        )
      }

      val index = fieldIndex(structType, node.id)
      val gep = builder.emit(
        IrGep(
          id = -1,
          type = IrPointer(structType.fields[index]),
          base = baseAddress,
          indices = listOf(
            IrIntConstant(0, IrPrimitive(PrimitiveKind.I32)),
            IrIntConstant(index.toLong(), IrPrimitive(PrimitiveKind.I32)),
          ),
        ),
      )
      LValue(gep, structType.fields[index])
    }

    is frontend.ast.IndexExprNode -> {
      val base = emitLValue(node.base)
      val baseType = base.pointee as? IrArray ?: error("indexing non-array")
      val indexValue = emitExpr(node.index)
      val gep = builder.emit(
        IrGep(
          id = -1,
          type = IrPointer(baseType.element),
          base = base.address,
          indices = listOf(IrIntConstant(0, IrPrimitive(PrimitiveKind.I32)), indexValue),
        ),
      )
      LValue(gep, baseType.element)
    }

    else -> error("Unsupported lvalue expression ${node::class.simpleName}")
  }

  private fun emitArgument(expr: ExprNode, expected: IrType?, hint: String): IrValue {
    if (expected is IrPointer) {
      tryLValue(expr)?.let { lv ->
        // If the lvalue already holds a pointer, passing its address would create a pointer-to-pointer.
        if (lv.pointee is IrPointer) return emitExpr(expr, expectedType = expected)
        ensureSameType(expected.pointee, lv.pointee)
        return retargetPointer(lv.address, expected)
      }
    }
    val value = emitExpr(expr, expectedType = expected)
    return if (expected is IrPointer) coerceArgument(value, expected, hint) else value
  }

  private fun tryLValue(expr: ExprNode): LValue? = when (expr) {
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

  private fun isUnsigned(type: IrType): Boolean =
    (type as? IrPrimitive)?.kind in setOf(PrimitiveKind.U32, PrimitiveKind.USIZE)

  private fun coerceArgument(value: IrValue, targetType: IrType, hint: String): IrValue {
    if (value.type == targetType) return value

    if (targetType is IrPointer) {
      return when (value.type) {
        is IrPointer -> retargetPointer(value, targetType)
        else -> {
          val addr = builder.emit(
            IrAlloca(
              id = -1,
              type = targetType,
              allocatedType = targetType.pointee,
              slotName = builder.freshLocalName(hint),
            ),
          )
          ensureSameType(targetType.pointee, value.type)
          builder.emit(
            IrStore(
              id = -1,
              type = IrPrimitive(PrimitiveKind.UNIT),
              address = addr,
              value = value,
            ),
          )
          addr
        }
      }
    }

    if (value.type is IrPrimitive && targetType is IrPrimitive && value.type.render() == targetType.render()) {
      return value
    }

    error("Type mismatch: cannot pass ${value.type} to parameter of type $targetType")
  }

  private fun unitValue(): IrValue = IrUndef(IrPrimitive(PrimitiveKind.UNIT))

  private fun resolveFunction(name: String): Function? {
    var scope = context.currentScope ?: context.rootScope
    while (scope != null) {
      val symbol = scope.resolve(name, Namespace.VALUE)
      if (symbol is Function) return symbol
      scope = scope.parentScope()
    }
    return null
  }

  private fun resolveStruct(name: String): StructType {
    var scope = context.currentScope ?: context.rootScope
    while (scope != null) {
      val symbol = scope.resolve(name, Namespace.TYPE)
      if (symbol is frontend.semantic.Struct) return symbol.type
      scope = scope.parentScope()
    }
    error("Unknown struct $name")
  }

  private fun resolveConstant(name: String): IrValue? {
    var scope = context.currentScope ?: context.rootScope
    while (scope != null) {
      val symbol = scope.resolve(name, Namespace.VALUE)
      if (symbol is Constant) {
        val value = symbol.value
        if (value is ConstValue.Int) {
          val irType = toIrType(value.actualType)
          return IrIntConstant(value.value, irType)
        }
      }
      scope = scope.parentScope()
    }
    return null
  }

  private fun resolveMethod(receiverType: IrType, name: String): Function? {
    val base = if (receiverType is IrPointer) receiverType.pointee else receiverType
    val struct = base as? IrStruct ?: return null
    val structName = struct.name ?: return null
    var scope = context.currentScope ?: context.rootScope
    while (scope != null) {
      val symbol = scope.resolve(structName, Namespace.TYPE)
      if (symbol is frontend.semantic.Struct) {
        return symbol.methods[name]
      }
      scope = scope.parentScope()
    }
    return null
  }

  private fun resolveAssociatedFunction(typeName: String, fnName: String): Function? {
    var scope = context.currentScope ?: context.rootScope
    while (scope != null) {
      when (val symbol = scope.resolve(typeName, Namespace.TYPE)) {
        is Struct -> {
          symbol.associateItems[fnName]?.let { return it as? Function }
          symbol.methods[fnName]?.let { return it }
        }

        is Enum -> {
          symbol.associateItems[fnName]?.let { return it as? Function }
          symbol.methods[fnName]?.let { return it }
        }

        else -> {}
      }
      scope = scope.parentScope()
    }
    return null
  }

  private fun fieldIndex(structType: IrStruct, fieldName: String): Int {
    val semantic = resolveStruct(structType.name ?: error("unnamed struct"))
    val names = semantic.fields.keys.toList()
    val idx = names.indexOf(fieldName)
    if (idx == -1) error("Field $fieldName not found")
    return idx
  }

}
