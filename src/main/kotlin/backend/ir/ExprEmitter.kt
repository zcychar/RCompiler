package backend.ir

import frontend.Keyword
import frontend.Literal
import frontend.Punctuation
import frontend.ast.BinaryExprNode
import frontend.ast.BorrowExprNode
import frontend.ast.BreakExprNode
import frontend.ast.CallExprNode
import frontend.ast.CastExprNode
import frontend.ast.ContinueExprNode
import frontend.ast.DerefExprNode
import frontend.ast.ExprNode
import frontend.ast.GroupedExprNode
import frontend.ast.IfExprNode
import frontend.ast.LiteralExprNode
import frontend.ast.LoopExprNode
import frontend.ast.MethodCallExprNode
import frontend.ast.StructExprNode
import frontend.ast.PathExprNode
import frontend.ast.ReturnExprNode
import frontend.ast.TypeNode
import frontend.ast.TypePathNode
import frontend.ast.UnaryExprNode
import frontend.ast.WhileExprNode
import frontend.assignOp
import frontend.semantic.ArrayType
import frontend.semantic.BoolType
import frontend.semantic.CharType
import frontend.semantic.Function
import frontend.semantic.Int32Type
import frontend.semantic.IntType
import frontend.semantic.ISizeType
import frontend.semantic.Namespace
import frontend.semantic.RefType
import frontend.semantic.StructType
import frontend.semantic.Type
import frontend.semantic.USizeType
import frontend.semantic.UInt32Type
import frontend.semantic.UnitType
import frontend.semantic.getInt
import utils.CompileError

/**
 * Expression emitter handling the currently supported lowering surface.
 * Unsupported expressions fail fast so missing coverage is obvious while we grow the backend.
 */
class ExprEmitter(
    private val context: CodegenContext,
    private val builder: IrBuilder = context.builder,
    private val typeMapper: TypeMapper = context.typeMapper,
    private val valueEnv: ValueEnv = context.valueEnv,
) {
    private val blockEmitter by lazy { FunctionEmitter(context, builder, this, typeMapper, valueEnv) }

    fun emitExpr(node: ExprNode): IrValue = when (node) {
        is LiteralExprNode -> emitLiteral(node)
        is PathExprNode -> emitPath(node)
        is GroupedExprNode -> emitExpr(node.expr)
        is BinaryExprNode -> emitBinary(node)
        is UnaryExprNode -> emitUnary(node)
        is CastExprNode -> emitCast(node)
        is BorrowExprNode -> emitBorrow(node)
        is DerefExprNode -> emitDeref(node)
        is IfExprNode -> emitIf(node)
        is WhileExprNode -> emitWhile(node)
        is LoopExprNode -> emitLoop(node)
        is BreakExprNode -> emitBreak(node)
        is ContinueExprNode -> emitContinue()
        is CallExprNode -> emitCall(node)
        is MethodCallExprNode -> emitMethodCall(node)
        is StructExprNode -> emitStructLiteral(node)
        is ReturnExprNode -> emitReturn(node)
        else -> error("Unsupported expression: ${node::class.simpleName}")
    }

    private fun emitLiteral(node: LiteralExprNode): IrValue = when (node.type) {
        Keyword.TRUE -> IrBoolConstant(true, IrPrimitive(PrimitiveKind.BOOL))
        Keyword.FALSE -> IrBoolConstant(false, IrPrimitive(PrimitiveKind.BOOL))
        Literal.CHAR -> {
            val value = node.value?.firstOrNull()
                ?: CompileError.fail("", "Invalid char literal")
            IrCharConstant(value.code, IrPrimitive(PrimitiveKind.CHAR))
        }

        Literal.INTEGER -> {
            val intConst = getInt(node)
            val irType = typeMapper.toIrType(intConst.actualType)
            IrIntConstant(intConst.value, irType)
        }

        else -> error("Unsupported literal ${node.type}")
    }

    private fun emitPath(node: PathExprNode): IrValue {
        if (node.seg2 != null) {
            error("Qualified paths are not supported in expression lowering yet")
        }
        val identifier = node.seg1.name ?: "self"
        val binding = valueEnv.resolve(identifier) ?: error("Unbound identifier $identifier")
        return when (binding) {
            is SsaValue -> binding.value
            is FunctionParam -> binding.value
            is StackSlot -> builder.emit(
                IrLoad(
                    id = -1,
                    type = binding.type,
                    address = binding.address,
                ),
            )
        }
    }

    private fun emitBinary(node: BinaryExprNode): IrValue =
        if (node.op in assignOp) emitAssignment(node) else emitBinaryOp(node)

    private fun emitAssignment(node: BinaryExprNode): IrValue {
        val lvalue = emitLValue(node.lhs)
        val rhs = emitExpr(node.rhs)
        val stored = when (node.op) {
            Punctuation.EQUAL -> rhs
            Punctuation.PLUS_EQUAL -> emitArithmetic(BinaryOperator.ADD, lvalue, rhs)
            Punctuation.MINUS_EQUAL -> emitArithmetic(BinaryOperator.SUB, lvalue, rhs)
            Punctuation.STAR_EQUAL -> emitArithmetic(BinaryOperator.MUL, lvalue, rhs)
            Punctuation.SLASH_EQUAL -> emitArithmetic(
                if (isUnsigned(lvalue.pointee)) BinaryOperator.UDIV else BinaryOperator.SDIV,
                lvalue,
                rhs,
            )

            Punctuation.PERCENT_EQUAL -> emitArithmetic(
                if (isUnsigned(lvalue.pointee)) BinaryOperator.UREM else BinaryOperator.SREM,
                lvalue,
                rhs,
            )

            else -> error("Unsupported assignment operator ${node.op}")
        }
        builder.emit(
            IrStore(
                id = -1,
                type = IrPrimitive(PrimitiveKind.UNIT),
                address = lvalue.address,
                value = stored,
            ),
        )
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

            Punctuation.EQUAL_EQUAL -> emitCompare(ComparePredicate.EQ, lhs, rhs)
            Punctuation.NOT_EQUAL -> emitCompare(ComparePredicate.NE, lhs, rhs)
            Punctuation.LESS -> emitCompare(if (isUnsigned(lhs.type)) ComparePredicate.ULT else ComparePredicate.SLT, lhs, rhs)
            Punctuation.LESS_EQUAL -> emitCompare(if (isUnsigned(lhs.type)) ComparePredicate.ULE else ComparePredicate.SLE, lhs, rhs)
            Punctuation.GREATER -> emitCompare(if (isUnsigned(lhs.type)) ComparePredicate.UGT else ComparePredicate.SGT, lhs, rhs)
            Punctuation.GREATER_EQUAL -> emitCompare(if (isUnsigned(lhs.type)) ComparePredicate.UGE else ComparePredicate.SGE, lhs, rhs)
            Punctuation.AND_AND -> emitShortCircuitAnd(lhs) { emitExpr(node.rhs) }
            Punctuation.OR_OR -> emitShortCircuitOr(lhs) { emitExpr(node.rhs) }
            Punctuation.AMPERSAND -> emitLogical(BinaryOperator.AND, lhs, rhs)
            Punctuation.PIPE -> emitLogical(BinaryOperator.OR, lhs, rhs)
            Punctuation.CARET -> emitLogical(BinaryOperator.XOR, lhs, rhs)
            else -> error("Unsupported binary operator ${node.op}")
        }
    }

    private fun emitArithmetic(operator: BinaryOperator, lvalue: LValue, rhs: IrValue): IrValue {
        val current = builder.emit(
            IrLoad(
                id = -1,
                type = lvalue.pointee,
                address = lvalue.address,
            ),
        )
        return builder.emit(
            IrBinary(
                id = -1,
                type = lvalue.pointee,
                operator = operator,
                lhs = current,
                rhs = rhs,
            ),
        )
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

    private fun emitLogical(operator: BinaryOperator, lhs: IrValue, rhs: IrValue): IrValue {
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

    private fun emitShortCircuitAnd(lhs: IrValue, rhsThunk: () -> IrValue): IrValue {
        return emitShortCircuit(lhs, rhsThunk, shortCircuitOnTrue = false)
    }

    private fun emitShortCircuitOr(lhs: IrValue, rhsThunk: () -> IrValue): IrValue {
        return emitShortCircuit(lhs, rhsThunk, shortCircuitOnTrue = true)
    }

    private fun emitShortCircuit(lhs: IrValue, rhsThunk: () -> IrValue, shortCircuitOnTrue: Boolean): IrValue {
        val boolType = IrPrimitive(PrimitiveKind.BOOL)
        if (lhs.type != boolType) error("Short-circuit operators require boolean operands")

        val function = context.currentFunction ?: error("No active function for short-circuit emission")
        val resultSlot = builder.emit(
            IrAlloca(
                id = -1,
                type = IrPointer(boolType),
                allocatedType = boolType,
                slotName = builder.freshLocalName("sc"),
            ),
        )
        builder.emit(
            IrStore(
                id = -1,
                type = IrPrimitive(PrimitiveKind.UNIT),
                address = resultSlot,
                value = lhs,
            ),
        )

        val rhsLabel = builder.freshLocalName(if (shortCircuitOnTrue) "or.rhs" else "and.rhs")
        val mergeLabel = builder.freshLocalName("sc.merge")
        val rhsBlock = builder.ensureBlock(rhsLabel)
        val mergeBlock = builder.ensureBlock(mergeLabel)

        val trueTarget = if (shortCircuitOnTrue) mergeLabel else rhsLabel
        val falseTarget = if (shortCircuitOnTrue) rhsLabel else mergeLabel
        builder.emitTerminator(
            IrBranch(
                id = -1,
                type = IrPrimitive(PrimitiveKind.UNIT),
                condition = lhs,
                trueTarget = trueTarget,
                falseTarget = falseTarget,
            ),
        )

        builder.positionAt(function, rhsBlock)
        val rhs = rhsThunk()
        if (rhs.type != boolType) error("Short-circuit RHS must be boolean")
        builder.emit(
            IrStore(
                id = -1,
                type = IrPrimitive(PrimitiveKind.UNIT),
                address = resultSlot,
                value = rhs,
            ),
        )
        builder.emitTerminator(IrJump(id = -1, type = IrPrimitive(PrimitiveKind.UNIT), target = mergeLabel))

        builder.positionAt(function, mergeBlock)
        return builder.emit(
            IrLoad(
                id = -1,
                type = boolType,
                address = resultSlot,
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
                id = -1,
                type = operand.type,
                operator = operator,
                operand = operand,
            ),
        )
    }

    private fun emitCast(node: CastExprNode): IrValue {
        val value = emitExpr(node.expr)
        val targetType = mapType(node.targetType)
        val irTarget = typeMapper.toIrType(targetType)
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
        return builder.emit(
            IrCast(
                id = -1,
                type = irTarget,
                value = value,
                kind = kind,
            ),
        )
    }

    private fun emitBorrow(node: BorrowExprNode): IrValue {
        val lvalue = emitLValue(node.expr)
        val targetType = IrPointer(lvalue.pointee, mutable = node.isMut)
        return retargetPointer(lvalue.address, targetType)
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

    private fun emitIf(node: IfExprNode): IrValue {
        val function = context.currentFunction ?: error("No active function")
        val condExpr = node.conds.firstOrNull()?.expr ?: error("if without condition")
        val condition = emitExpr(condExpr)
        val boolType = IrPrimitive(PrimitiveKind.BOOL)
        if (condition.type != boolType) error("if condition must be boolean")

        val thenLabel = builder.freshLocalName("if.then")
        val elseLabel = if (node.elseExpr != null) builder.freshLocalName("if.else") else null
        val mergeLabel = builder.freshLocalName("if.merge")

        builder.emitTerminator(
            IrBranch(
                id = -1,
                type = IrPrimitive(PrimitiveKind.UNIT),
                condition = condition,
                trueTarget = thenLabel,
                falseTarget = elseLabel ?: mergeLabel,
            ),
        )

        val thenBlock = builder.ensureBlock(thenLabel)
        builder.positionAt(function, thenBlock)
        blockEmitter.emitBlock(node.expr, expectValue = false)
        if (builder.hasInsertionPoint()) {
            builder.emitTerminator(IrJump(id = -1, type = IrPrimitive(PrimitiveKind.UNIT), target = mergeLabel))
        }

        if (elseLabel != null) {
            val elseBlock = builder.ensureBlock(elseLabel)
            builder.positionAt(function, elseBlock)
            emitExpr(node.elseExpr!!)
            if (builder.hasInsertionPoint()) {
                builder.emitTerminator(IrJump(id = -1, type = IrPrimitive(PrimitiveKind.UNIT), target = mergeLabel))
            }
        }

        val mergeBlock = builder.ensureBlock(mergeLabel)
        builder.positionAt(function, mergeBlock)
        return unitValue()
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
        if (calleePath.seg2 != null || calleePath.seg1.name == null) error("unsupported callee path")
        val fnName = calleePath.seg1.name
        val fnSymbol = resolveFunction(fnName) ?: error("Unknown function $fnName")
        val signature = typeMapper.functionSignature(fnSymbol)
        val args = node.params.map { emitExpr(it) }
        return builder.emit(
            IrCall(
                id = -1,
                type = signature.returnType,
                callee = IrFunctionRef(fnName, signature.toFunctionPointer()),
                arguments = args,
            ),
        )
    }

    private fun emitMethodCall(node: MethodCallExprNode): IrValue =
        error("Method calls are not supported in the current backend subset")

    private fun emitStructLiteral(node: StructExprNode): IrValue {
        val path = node.path as? PathExprNode ?: error("struct literal requires path")
        val typeName = path.seg1.name ?: error("struct literal missing name")
        val structType = resolveStruct(typeName)
        val irType = typeMapper.structLayout(structType)
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
                    indices = listOf(IrIntConstant(0, IrPrimitive(PrimitiveKind.I32)), IrIntConstant(index.toLong(), IrPrimitive(PrimitiveKind.I32))),
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
        val value = node.expr?.let { emitExpr(it) }
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

    private fun emitLValue(node: ExprNode): LValue = when (node) {
        is PathExprNode -> {
            if (node.seg2 != null) error("Qualified paths are not supported for assignment")
            val identifier = node.seg1.name ?: "self"
            val binding = valueEnv.resolve(identifier) ?: error("Unbound identifier $identifier")
            when (binding) {
                is StackSlot -> LValue(binding.address, binding.type)
                is SsaValue -> error("Cannot take lvalue of SSA value")
                is FunctionParam -> error("Cannot take lvalue of function parameter without stack slot")
                else -> error("Expression is not assignable")
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
            val baseType = base.pointee as? IrStruct ?: error("field access on non-struct")
            val index = fieldIndex(baseType, node.id)
            val gep = builder.emit(
                IrGep(
                    id = -1,
                    type = IrPointer(baseType.fields[index]),
                    base = base.address,
                    indices = listOf(IrIntConstant(0, IrPrimitive(PrimitiveKind.I32)), IrIntConstant(index.toLong(), IrPrimitive(PrimitiveKind.I32))),
                ),
            )
            LValue(gep, baseType.fields[index])
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

        is frontend.ast.RefTypeNode -> RefType(mapType(node.type), node.hasMut)
        is frontend.ast.ArrayTypeNode -> {
            val element = mapType(node.type)
            if (node.evaluatedSize < 0) CompileError.fail("", "Array size must be known for cast")
            ArrayType(element, node.evaluatedSize.toInt())
        }

        frontend.ast.UnitTypeNode -> UnitType
    }

    private fun ensureSameType(lhs: IrType, rhs: IrType) {
        if (lhs != rhs) {
            error("Type mismatch: $lhs vs $rhs")
        }
    }

    private fun isUnsigned(type: IrType): Boolean =
        (type as? IrPrimitive)?.kind in setOf(PrimitiveKind.U32, PrimitiveKind.USIZE)

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

    private fun fieldIndex(struct: IrStruct, fieldName: String): Int =
        struct.fields.indices.firstOrNull()
            ?: error("Field $fieldName not found")

    private fun retargetPointer(value: IrValue, targetType: IrPointer): IrValue =
        if (value.type == targetType) {
            value
        } else {
            builder.emit(
                IrCast(
                    id = -1,
                    type = targetType,
                    value = value,
                    kind = CastKind.BITCAST,
                ),
            )
        }

    private data class LValue(val address: IrValue, val pointee: IrType)
}
