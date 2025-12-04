package backend.ir

import frontend.Keyword
import frontend.Literal
import frontend.Punctuation
import frontend.ast.*
import frontend.semantic.getInt

/**
 * Result wrapper for expression emission in address-only mode.
 */
data class ExprResult(
  val kind: Kind,
  val type: IrType,
  val scalar: IrValue? = null,
  val addr: IrValue? = null,
  val isLvalue: Boolean = false,
) {
  enum class Kind { Scalar, Addr }
}

/**
 * Placeholder for new address-only ExprEmitter implementation.
 * Methods are stubs to be filled in per the new design, with a few helpers implemented.
 */
class ExprEmitter(
  private val context: CodegenContext,
  private val builder: IrBuilder = context.builder,
  private val valueEnv: ValueEnv = context.valueEnv,
) {
  fun emitExpr(node: ExprNode): ExprResult = when (node) {
    is LiteralExprNode -> emitLiteralExpr(node)
    is IdentifierExprNode -> emitIdentifierExpr(node)
    is PathExprNode -> emitPathExpr(node)
    is GroupedExprNode -> emitGroupedExpr(node)
    is ArrayExprNode -> emitArrayExpr(node)
    is BlockExprNode -> error("Block expressions should be handled by FunctionEmitter")
    is BinaryExprNode -> emitBinaryExpr(node)
    is UnaryExprNode -> emitUnaryExpr(node)
    is CastExprNode -> emitCastExpr(node)
    is BorrowExprNode -> emitBorrowExpr(node)
    is DerefExprNode -> emitDerefExpr(node)
    is IfExprNode -> emitIfExpr(node)
    is WhileExprNode -> emitWhileExpr(node)
    is LoopExprNode -> emitLoopExpr(node)
    is BreakExprNode -> emitBreakExpr(node)
    is ContinueExprNode -> emitContinueExpr(node)
    is CallExprNode -> emitCallExpr(node)
    is MethodCallExprNode -> emitMethodCall(node)
    is StructExprNode -> emitStructExpr(node)
    is ReturnExprNode -> emitReturnExpr(node)
    is FieldAccessExprNode -> emitFieldAccess(node)
    is IndexExprNode -> emitIndexExpr(node)
    else -> error("Unsupported expression: ${node::class.simpleName}")
  }

  fun emitExprInto(destAddr: IrValue, node: ExprNode) {
    // TODO: optimize aggregate literals by constructing directly into destAddr to avoid temp+copy.
    val destPtr = destAddr.type as? IrPointer
      ?: error("Destination for emitExprInto must be a pointer, got ${destAddr.type}")
    val destType = destPtr.pointee
    val value = emitExpr(node)
    if (isAggregate(destType)) {
      val srcAddr = when (value.kind) {
        ExprResult.Kind.Addr -> value.addr
        ExprResult.Kind.Scalar if value.type is IrPointer -> value.scalar
        else -> error("Cannot materialize aggregate into destination from $value")
      } ?: error("Missing source address for aggregate copy")
      emitCopyAggregate(destAddr, srcAddr, destType)
      return
    }
    val scalar = if (value.kind == ExprResult.Kind.Scalar) {
      value.scalar ?: error("Scalar ExprResult missing value")
    } else {
      materializeScalar(value)
    }
    ensureSameType(destType, scalar.type)
    builder.emit(
      IrStore(
        id = -1,
        type = IrPrimitive(PrimitiveKind.UNIT),
        address = destAddr,
        value = scalar,
      ),
    )
  }

  fun materializeScalar(result: ExprResult): IrValue =
    when (result.kind) {
      ExprResult.Kind.Scalar -> result.scalar
        ?: error("Scalar ExprResult missing scalar value")

      ExprResult.Kind.Addr -> {
        val addr = result.addr ?: error("Addr ExprResult missing address")
        builder.emit(
          IrLoad(
            id = -1,
            type = result.type,
            address = addr,
          ),
        )
      }
    }

  fun materializeAddress(result: ExprResult): IrValue =
    when (result.kind) {
      ExprResult.Kind.Addr -> result.addr
        ?: error("Addr ExprResult missing address")

      ExprResult.Kind.Scalar -> {
        val scalar = result.scalar
          ?: error("Scalar ExprResult missing scalar value")
        val slotName = builder.freshLocalName("spill")
        val slot = builder.emit(
          IrAlloca(
            id = -1,
            type = IrPointer(result.type),
            allocatedType = result.type,
            slotName = slotName,
          ),
        )
        builder.emit(
          IrStore(
            id = -1,
            type = IrPrimitive(PrimitiveKind.UNIT),
            address = slot,
            value = scalar,
          ),
        )
        slot
      }
    }

  // --- Expression forms ---

  private fun emitIfExpr(node: IfExprNode): ExprResult {
    // TODO
    throw NotImplementedError("emitIfExpr")
  }

  private fun emitLoopExpr(node: LoopExprNode): ExprResult {
    // TODO
    throw NotImplementedError("emitLoopExpr")
  }

  private fun emitWhileExpr(node: WhileExprNode): ExprResult {
    // TODO
    throw NotImplementedError("emitWhileExpr")
  }

  private fun emitBreakExpr(node: BreakExprNode): ExprResult {
    // TODO
    throw NotImplementedError("emitBreakExpr")
  }

  private fun emitContinueExpr(node: ContinueExprNode): ExprResult {
    // TODO
    throw NotImplementedError("emitContinueExpr")
  }

  private fun emitReturnExpr(node: ReturnExprNode): ExprResult {
    // TODO
    throw NotImplementedError("emitReturnExpr")
  }

  private fun emitFieldAccess(node: FieldAccessExprNode): ExprResult {
    val base = emitExpr(node.expr)
    val basePtr: IrValue
    val structType: IrStruct
    when (base.kind) {
      ExprResult.Kind.Addr -> {
        val addr = base.addr ?: error("Addr ExprResult missing address")
        val pointee = base.type as? IrStruct
          ?: error("Field access on non-struct address ${base.type}")
        basePtr = addr
        structType = pointee
      }

      ExprResult.Kind.Scalar -> {
        val scalar = base.scalar ?: error("Scalar ExprResult missing value")
        val ptrType = scalar.type as? IrPointer
          ?: error("Field access on non-pointer scalar ${scalar.type}")
        val pointee = ptrType.pointee as? IrStruct
          ?: error("Field access on non-struct pointer ${ptrType.pointee}")
        basePtr = scalar
        structType = pointee
      }
    }
    val fieldIdx = fieldIndex(structType, node.id)
    val fieldType = structType.fields[fieldIdx]
    val gep = builder.emit(
      IrGep(
        id = -1,
        type = IrPointer(fieldType),
        base = basePtr,
        indices = listOf(
          IrIntConstant(0, IrPrimitive(PrimitiveKind.I32)),
          IrIntConstant(fieldIdx.toLong(), IrPrimitive(PrimitiveKind.I32)),
        ),
      ),
    )
    return ExprResult(
      kind = ExprResult.Kind.Addr,
      type = fieldType,
      addr = gep,
      isLvalue = true,
    )
  }

  private fun emitMethodCall(node: MethodCallExprNode): ExprResult {
    // TODO
    throw NotImplementedError("emitMethodCall")
  }

  private fun emitCallExpr(node: CallExprNode): ExprResult {
    // TODO
    throw NotImplementedError("emitCallExpr")
  }


  private fun emitLiteralExpr(node: LiteralExprNode): ExprResult = when (node.type) {
    Keyword.TRUE -> ExprResult(
      kind = ExprResult.Kind.Scalar,
      type = IrPrimitive(PrimitiveKind.BOOL),
      scalar = IrBoolConstant(true, IrPrimitive(PrimitiveKind.BOOL)),
    )

    Keyword.FALSE -> ExprResult(
      kind = ExprResult.Kind.Scalar,
      type = IrPrimitive(PrimitiveKind.BOOL),
      scalar = IrBoolConstant(false, IrPrimitive(PrimitiveKind.BOOL)),
    )

    Literal.INTEGER -> {
      val intConst = getInt(node)
      val irType = toIrType(intConst.actualType)
      ExprResult(
        kind = ExprResult.Kind.Scalar,
        type = irType,
        scalar = IrIntConstant(intConst.value, irType),
      )
    }

    else -> error("Unsupported literal ${node.type}")
  }

  private fun emitIdentifierExpr(node: IdentifierExprNode): ExprResult {
    val binding = valueEnv.resolve(node.value)
      ?: error("Unbound identifier ${node.value}")
    return when (binding) {
      is StackSlot -> ExprResult(
        kind = ExprResult.Kind.Addr,
        type = binding.type,
        addr = binding.address,
        isLvalue = true,
      )

      is SsaValue -> ExprResult(
        kind = ExprResult.Kind.Scalar,
        type = binding.value.type,
        scalar = binding.value,
        isLvalue = false,
      )
    }
  }

  private fun emitPathExpr(node: PathExprNode): ExprResult {
    if (node.seg2 != null) {
      error("Qualified paths are not supported in expression lowering yet")
    }
    val identifier = node.seg1.name ?: "self"
    val binding = valueEnv.resolve(identifier)
      ?: error("Unbound identifier $identifier")
    return when (binding) {
      is StackSlot -> ExprResult(
        kind = ExprResult.Kind.Addr,
        type = binding.type,
        addr = binding.address,
        isLvalue = true,
      )

      is SsaValue -> ExprResult(
        kind = ExprResult.Kind.Scalar,
        type = binding.value.type,
        scalar = binding.value,
        isLvalue = false,
      )
    }
  }

  private fun emitArrayExpr(node: ArrayExprNode): ExprResult {
    val elementType = node.type?.let { toIrType(it) } ?: error("Array element type unavailable")
    val length = node.evaluatedSize.takeIf { it >= 0 }?.toInt()
      ?: error("Array size must be evaluated before lowering")
    val irArray = IrArray(elementType, length)
    val slot = builder.emit(
      IrAlloca(
        id = -1,
        type = IrPointer(irArray),
        allocatedType = irArray,
        slotName = builder.freshLocalName("array"),
      ),
    )

    fun elementPtr(idxValue: IrValue): IrValue =
      builder.emit(
        IrGep(
          id = -1,
          type = IrPointer(elementType),
          base = slot,
          indices = listOf(
            IrIntConstant(0, IrPrimitive(PrimitiveKind.I32)),
            idxValue,
          ),
        ),
      )

    if (node.repeatOp != null) {
      val value = emitExpr(node.repeatOp)
      val valueScalar = if (isAggregate(elementType)) null else materializeScalar(value)
      repeat(length) { idx ->
        val ptr = elementPtr(IrIntConstant(idx.toLong(), IrPrimitive(PrimitiveKind.I32)))
        if (isAggregate(elementType)) {
          emitExprInto(ptr, node.repeatOp)
        } else {
          val scalar = valueScalar ?: materializeScalar(emitExpr(node.repeatOp))
          ensureSameType(elementType, scalar.type)
          builder.emit(IrStore(id = -1, type = IrPrimitive(PrimitiveKind.UNIT), address = ptr, value = scalar))
        }
      }
    } else {
      if (node.elements.size != length) error("Array literal length mismatch")
      node.elements.forEachIndexed { idx, elem ->
        val ptr = elementPtr(IrIntConstant(idx.toLong(), IrPrimitive(PrimitiveKind.I32)))
        if (isAggregate(elementType)) {
          emitExprInto(ptr, elem)
        } else {
          val scalar = materializeScalar(emitExpr(elem))
          ensureSameType(elementType, scalar.type)
          builder.emit(IrStore(id = -1, type = IrPrimitive(PrimitiveKind.UNIT), address = ptr, value = scalar))
        }
      }
    }

    return ExprResult(
      kind = ExprResult.Kind.Addr,
      type = irArray,
      addr = slot,
      isLvalue = false,
    )
  }

  private fun emitIndexExpr(node: IndexExprNode): ExprResult {
    val base = emitExpr(node.base)
    val index = materializeScalar(emitExpr(node.index))
    val basePtr: IrValue
    val arrayType: IrArray
    when (base.kind) {
      ExprResult.Kind.Addr -> {
        basePtr = base.addr ?: error("Addr ExprResult missing address")
        arrayType = base.type as? IrArray
          ?: error("Indexing non-array address ${base.type}")
      }

      ExprResult.Kind.Scalar -> {
        val scalar = base.scalar ?: error("Scalar ExprResult missing value")
        val ptrType = scalar.type as? IrPointer
          ?: error("Indexing non-pointer scalar ${scalar.type}")
        arrayType = ptrType.pointee as? IrArray
          ?: error("Indexing non-array pointer ${ptrType.pointee}")
        basePtr = scalar
      }
    }
    val elemType = arrayType.element
    val gep = builder.emit(
      IrGep(
        id = -1,
        type = IrPointer(elemType),
        base = basePtr,
        indices = listOf(
          IrIntConstant(0, IrPrimitive(PrimitiveKind.I32)),
          index,
        ),
      ),
    )
    return ExprResult(
      kind = ExprResult.Kind.Addr,
      type = elemType,
      addr = gep,
      isLvalue = true,
    )
  }

  private fun emitCastExpr(node: CastExprNode): ExprResult {
    val value = materializeScalar(emitExpr(node.expr))
    val targetType = mapType(node.targetType)
    if (targetType == value.type) {
      return ExprResult(kind = ExprResult.Kind.Scalar, type = targetType, scalar = value)
    }
    val kind = when {
      value.type is IrPrimitive && targetType is IrPrimitive -> when {
        (value.type as IrPrimitive).kind == PrimitiveKind.BOOL -> CastKind.ZEXT
        targetType.kind == PrimitiveKind.BOOL -> CastKind.TRUNC
        else -> CastKind.BITCAST
      }

      else -> CastKind.BITCAST
    }
    val casted = builder.emit(
      IrCast(
        id = -1,
        type = targetType,
        value = value,
        kind = kind,
      ),
    )
    return ExprResult(kind = ExprResult.Kind.Scalar, type = targetType, scalar = casted)
  }

  private fun mapType(node: TypeNode): IrType = when (node) {
    is TypePathNode -> when (node.name) {
      "i32" -> IrPrimitive(PrimitiveKind.I32)
      "u32" -> IrPrimitive(PrimitiveKind.U32)
      "isize" -> IrPrimitive(PrimitiveKind.ISIZE)
      "usize" -> IrPrimitive(PrimitiveKind.USIZE)
      "bool" -> IrPrimitive(PrimitiveKind.BOOL)
      "char" -> IrPrimitive(PrimitiveKind.CHAR)
      else -> error("Unsupported cast target type ${node.name}")
    }

    is RefTypeNode -> IrPointer(mapType(node.type))
    is ArrayTypeNode -> {
      val element = mapType(node.type)
      if (node.evaluatedSize < 0) {
        error("Array size must be known for cast")
      }
      IrArray(element, node.evaluatedSize.toInt())
    }

    UnitTypeNode -> IrPrimitive(PrimitiveKind.UNIT)
  }

  private fun emitStructExpr(node: StructExprNode): ExprResult {
    val path = node.path as? PathExprNode ?: error("struct literal requires path")
    val typeName = path.seg1.name ?: error("struct literal missing name")
    val structSemantic = resolveStruct(typeName)
    val irStruct = structLayout(structSemantic)
    val slot = builder.emit(
      IrAlloca(
        id = -1,
        type = IrPointer(irStruct),
        allocatedType = irStruct,
        slotName = builder.freshLocalName(typeName),
      ),
    )
    structSemantic.fields.keys.forEachIndexed { index, fieldName ->
      val fieldExpr = node.fields.find { it.id == fieldName }?.expr
        ?: error("Missing field $fieldName in struct literal")
      val fieldType = irStruct.fields[index]
      val fieldPtr = builder.emit(
        IrGep(
          id = -1,
          type = IrPointer(fieldType),
          base = slot,
          indices = listOf(
            IrIntConstant(0, IrPrimitive(PrimitiveKind.I32)),
            IrIntConstant(index.toLong(), IrPrimitive(PrimitiveKind.I32)),
          ),
        ),
      )
      if (isAggregate(fieldType)) {
        emitExprInto(fieldPtr, fieldExpr)
      } else {
        val value = materializeScalar(emitExpr(fieldExpr))
        ensureSameType(fieldType, value.type)
        builder.emit(
          IrStore(
            id = -1,
            type = IrPrimitive(PrimitiveKind.UNIT),
            address = fieldPtr,
            value = value,
          ),
        )
      }
    }
    return ExprResult(
      kind = ExprResult.Kind.Addr,
      type = irStruct,
      addr = slot,
      isLvalue = false,
    )
  }

  private fun emitGroupedExpr(node: GroupedExprNode): ExprResult =
    emitExpr(node.expr)

  private fun emitBorrowExpr(node: BorrowExprNode): ExprResult {
    val base = emitExpr(node.expr)
    val addr = materializeAddress(base)
    return ExprResult(
      kind = ExprResult.Kind.Scalar,
      type = IrPointer(base.type),
      scalar = addr,
      isLvalue = false,
    )
  }

  private fun emitDerefExpr(node: DerefExprNode): ExprResult {
    val ptr = materializeScalar(emitExpr(node.expr))
    val ptrType = ptr.type as? IrPointer
      ?: error("Cannot dereference non-pointer type ${ptr.type}")
    return ExprResult(
      kind = ExprResult.Kind.Addr,
      type = ptrType.pointee,
      addr = ptr,
      isLvalue = true,
    )
  }

  private fun emitUnaryExpr(node: UnaryExprNode): ExprResult {
    val operand = materializeScalar(emitExpr(node.rhs))
    val op = when (node.op) {
      Punctuation.MINUS -> UnaryOperator.NEG
      frontend.Punctuation.BANG -> UnaryOperator.NOT
      else -> error("Unsupported unary operator ${node.op}")
    }
    val result = builder.emit(
      IrUnary(
        id = -1,
        type = operand.type,
        operator = op,
        operand = operand,
      ),
    )
    return ExprResult(
      kind = ExprResult.Kind.Scalar,
      type = result.type,
      scalar = result,
    )
  }

  private fun emitBinaryExpr(node: BinaryExprNode): ExprResult {
    val lhs = materializeScalar(emitExpr(node.lhs))
    val rhs = materializeScalar(emitExpr(node.rhs))
    val result: IrValue = when (node.op) {
      frontend.Punctuation.PLUS -> emitArithmetic(BinaryOperator.ADD, lhs, rhs)
      frontend.Punctuation.MINUS -> emitArithmetic(BinaryOperator.SUB, lhs, rhs)
      frontend.Punctuation.STAR -> emitArithmetic(BinaryOperator.MUL, lhs, rhs)
      frontend.Punctuation.SLASH -> emitArithmetic(
        if (isUnsigned(lhs.type)) BinaryOperator.UDIV else BinaryOperator.SDIV,
        lhs,
        rhs,
      )

      frontend.Punctuation.PERCENT -> emitArithmetic(
        if (isUnsigned(lhs.type)) BinaryOperator.UREM else BinaryOperator.SREM,
        lhs,
        rhs,
      )

      frontend.Punctuation.LESS_LESS -> emitArithmetic(BinaryOperator.SHL, lhs, rhs)
      frontend.Punctuation.GREATER_GREATER -> emitArithmetic(
        if (isUnsigned(lhs.type)) BinaryOperator.LSHR else BinaryOperator.ASHR,
        lhs,
        rhs,
      )

      frontend.Punctuation.AND_AND -> error("Short-circuit && not yet implemented")
      frontend.Punctuation.OR_OR -> error("Short-circuit || not yet implemented")
      frontend.Punctuation.AMPERSAND -> emitArithmetic(BinaryOperator.AND, lhs, rhs)
      frontend.Punctuation.PIPE -> emitArithmetic(BinaryOperator.OR, lhs, rhs)
      frontend.Punctuation.CARET -> emitArithmetic(BinaryOperator.XOR, lhs, rhs)
      frontend.Punctuation.EQUAL_EQUAL -> emitCompare(ComparePredicate.EQ, lhs, rhs)
      frontend.Punctuation.NOT_EQUAL -> emitCompare(ComparePredicate.NE, lhs, rhs)
      frontend.Punctuation.LESS -> emitCompare(
        if (isUnsigned(lhs.type)) ComparePredicate.ULT else ComparePredicate.SLT,
        lhs,
        rhs,
      )

      frontend.Punctuation.LESS_EQUAL -> emitCompare(
        if (isUnsigned(lhs.type)) ComparePredicate.ULE else ComparePredicate.SLE,
        lhs,
        rhs,
      )

      frontend.Punctuation.GREATER -> emitCompare(
        if (isUnsigned(lhs.type)) ComparePredicate.UGT else ComparePredicate.SGT,
        lhs,
        rhs,
      )

      frontend.Punctuation.GREATER_EQUAL -> emitCompare(
        if (isUnsigned(lhs.type)) ComparePredicate.UGE else ComparePredicate.SGE,
        lhs,
        rhs,
      )

      else -> error("Unsupported binary operator ${node.op}")
    }
    return ExprResult(
      kind = ExprResult.Kind.Scalar,
      type = result.type,
      scalar = result,
    )
  }

  private fun emitShortCircuitAnd(lhs: ExprNode, rhs: ExprNode): ExprResult {
    // TODO
    throw NotImplementedError("emitShortCircuitAnd")
  }

  private fun emitShortCircuitOr(lhs: ExprNode, rhs: ExprNode): ExprResult {
    // TODO
    throw NotImplementedError("emitShortCircuitOr")
  }

  // --- Helpers ---

  private fun fieldIndex(structType: IrStruct, fieldName: String): Int {
    val semantic = resolveStruct(structType.name ?: error("Unnamed struct"))
    val names = semantic.fields.keys.toList()
    val idx = names.indexOf(fieldName)
    if (idx == -1) error("Field $fieldName not found in ${structType.name}")
    return idx
  }

  private fun resolveStruct(name: String): frontend.semantic.StructType {
    var scope = context.currentScope ?: context.rootScope
    while (scope != null) {
      val symbol = scope.resolve(name, frontend.semantic.Namespace.TYPE)
      if (symbol is frontend.semantic.Struct) return symbol.type
      scope = scope.parentScope()
    }
    error("Unknown struct $name")
  }

  private fun emitCopyAggregate(destPtr: IrValue, srcPtr: IrValue, type: IrType) {
    require(isAggregate(type)) { "emitCopyAggregate expects aggregate type, got $type" }
    when (type) {
      is IrStruct -> {
        type.fields.forEachIndexed { idx, fieldTy ->
          val destField = builder.emit(
            IrGep(
              id = -1,
              type = IrPointer(fieldTy),
              base = destPtr,
              indices = listOf(
                IrIntConstant(0, IrPrimitive(PrimitiveKind.I32)),
                IrIntConstant(idx.toLong(), IrPrimitive(PrimitiveKind.I32))
              )
            )
          )
          val srcField = builder.emit(
            IrGep(
              id = -1,
              type = IrPointer(fieldTy),
              base = srcPtr,
              indices = listOf(
                IrIntConstant(0, IrPrimitive(PrimitiveKind.I32)),
                IrIntConstant(idx.toLong(), IrPrimitive(PrimitiveKind.I32))
              )
            )
          )
          if (isAggregate(fieldTy)) {
            emitCopyAggregate(destField, srcField, fieldTy)
          } else {
            val value = builder.emit(IrLoad(id = -1, type = fieldTy, address = srcField))
            builder.emit(IrStore(id = -1, type = IrPrimitive(PrimitiveKind.UNIT), address = destField, value = value))
          }
        }
      }

      is IrArray -> {
        val len = type.length
        if (len == 0) return

        fun copyElement(idxValue: IrValue) {
          val destElem = builder.emit(
            IrGep(
              id = -1,
              type = IrPointer(type.element),
              base = destPtr,
              indices = listOf(IrIntConstant(0, IrPrimitive(PrimitiveKind.I32)), idxValue)
            )
          )
          val srcElem = builder.emit(
            IrGep(
              id = -1,
              type = IrPointer(type.element),
              base = srcPtr,
              indices = listOf(IrIntConstant(0, IrPrimitive(PrimitiveKind.I32)), idxValue)
            )
          )
          if (isAggregate(type.element)) {
            emitCopyAggregate(destElem, srcElem, type.element)
          } else {
            val value = builder.emit(IrLoad(id = -1, type = type.element, address = srcElem))
            builder.emit(IrStore(id = -1, type = IrPrimitive(PrimitiveKind.UNIT), address = destElem, value = value))
          }
        }

        val unrollThreshold = 8
        if (len <= unrollThreshold) {
          repeat(len) { idx ->
            copyElement(IrIntConstant(idx.toLong(), IrPrimitive(PrimitiveKind.I32)))
          }
        } else {
          val idxSlot = builder.emit(
            IrAlloca(
              id = -1,
              type = IrPointer(IrPrimitive(PrimitiveKind.I32)),
              allocatedType = IrPrimitive(PrimitiveKind.I32),
              slotName = builder.freshLocalName("idx")
            )
          )
          builder.emit(
            IrStore(
              id = -1,
              type = IrPrimitive(PrimitiveKind.UNIT),
              address = idxSlot,
              value = IrIntConstant(0, IrPrimitive(PrimitiveKind.I32))
            )
          )

          val function = context.currentFunction ?: error("No current function for loop emission")
          val condBlock = builder.ensureBlock(builder.freshLocalName("copy.cond"))
          val bodyBlock = builder.ensureBlock(builder.freshLocalName("copy.body"))
          val exitBlock = builder.ensureBlock(builder.freshLocalName("copy.exit"))

          builder.emitTerminator(IrJump(id = -1, type = IrPrimitive(PrimitiveKind.UNIT), target = condBlock.label))

          // cond
          builder.positionAt(function, condBlock)
          val idxVal = builder.emit(
            IrLoad(id = -1, type = IrPrimitive(PrimitiveKind.I32), address = idxSlot)
          )
          val cmp = builder.emit(
            IrCmp(
              id = -1, type = IrPrimitive(PrimitiveKind.BOOL),
              predicate = ComparePredicate.SLT,
              lhs = idxVal,
              rhs = IrIntConstant(
                len.toLong(),
                IrPrimitive(PrimitiveKind.I32)
              )
            )
          )
          builder.emitTerminator(
            IrBranch(
              id = -1,
              type = IrPrimitive(PrimitiveKind.UNIT),
              condition = cmp,
              trueTarget = bodyBlock.label,
              falseTarget = exitBlock.label
            )
          )

          // body
          builder.positionAt(function, bodyBlock)
          val idxCurrent = builder.emit(IrLoad(id = -1, type = IrPrimitive(PrimitiveKind.I32), address = idxSlot))
          copyElement(idxCurrent)
          val nextIdx = builder.emit(
            IrBinary(
              id = -1,
              type = IrPrimitive(PrimitiveKind.I32),
              operator = BinaryOperator.ADD,
              lhs = idxCurrent,
              rhs = IrIntConstant(1, IrPrimitive(PrimitiveKind.I32))
            )
          )
          builder.emit(IrStore(id = -1, type = IrPrimitive(PrimitiveKind.UNIT), address = idxSlot, value = nextIdx))
          builder.emitTerminator(IrJump(id = -1, type = IrPrimitive(PrimitiveKind.UNIT), target = condBlock.label))

          // exit
          builder.positionAt(function, exitBlock)
        }
      }

      else -> error("Unexpected aggregate kind $type")
    }
  }

  private fun emitArithmetic(operator: BinaryOperator, lhs: IrValue, rhs: IrValue): IrValue {
    ensureSameType(lhs.type, rhs.type)
    return builder.emit(
      IrBinary(
        id = -1,
        type = lhs.type,
        operator = operator,
        lhs = lhs,
        rhs = rhs,
      ),
    )
  }

  private fun emitCompare(predicate: ComparePredicate, lhs: IrValue, rhs: IrValue): IrValue {
    ensureSameType(lhs.type, rhs.type)
    return builder.emit(
      IrCmp(
        id = -1,
        type = IrPrimitive(PrimitiveKind.BOOL),
        predicate = predicate,
        lhs = lhs,
        rhs = rhs,
      ),
    )
  }

  private fun ensureSameType(lhs: IrType, rhs: IrType) {
    if (lhs == rhs) return
    if (lhs is IrPrimitive && rhs is IrPrimitive && lhs.render() == rhs.render()) return
    error("Type mismatch: $lhs vs $rhs")
  }

  private fun isUnsigned(type: IrType): Boolean =
    (type as? IrPrimitive)?.kind in setOf(PrimitiveKind.U32, PrimitiveKind.USIZE)
}
