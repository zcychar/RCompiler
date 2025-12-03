package backend.ir

import frontend.ast.BlockExprNode
import frontend.ast.ExprStmtNode
import frontend.ast.IdentifierPatternNode
import frontend.ast.ItemStmtNode
import frontend.ast.LetStmtNode
import frontend.ast.NullStmtNode
import frontend.ast.StmtNode
import frontend.semantic.Function
import frontend.semantic.Type

/**
 * Drives lowering of functions and methods into IR according to the backend design doc.
 * Expression-level work is delegated to [ExprEmitter]; this class wires up scopes,
 * parameters, and blocks.
 */
class FunctionEmitter(
    private val context: CodegenContext,
    private val builder: IrBuilder = context.builder,
    private val exprEmitter: ExprEmitter = ExprEmitter(context),
    private val valueEnv: ValueEnv = context.valueEnv,
) {

    fun emitFunction(fnSymbol: Function, node: frontend.ast.FunctionItemNode, owner: Type? = null): IrFunction {
        val signature = irFunctionSignature(fnSymbol)
        val ownerName = when (val resolvedOwner = owner ?: fnSymbol.self) {
            is frontend.semantic.StructType -> resolvedOwner.name
            is frontend.semantic.EnumType -> resolvedOwner.name
            else -> null
        }
        val irName = ownerName?.let { "$it.${fnSymbol.name}" } ?: fnSymbol.name
        val parameterNames = buildList {
            fnSymbol.selfParam?.let { add("self") }
            fnSymbol.params.forEach { add(it.name) }
        }
        val function = IrFunction(irName, signature, parameterNames)
        context.module.declareFunction(function)
        context.currentFunction = function
        val previousScope = context.currentScope
        context.currentScope = node.body?.scope ?: previousScope

        builder.positionAt(function, function.entryBlock())
        valueEnv.pushFunction(signature.returnType)
        valueEnv.enterScope()

        bindParameters(fnSymbol, signature)
        val expectsValue = signature.returnType !is IrPrimitive || signature.returnType.kind != PrimitiveKind.UNIT
        val blockResult = node.body?.let { emitBlock(it, expectValue = expectsValue, expectedType = signature.returnType) }

        if (builder.hasInsertionPoint()) {
            val returnValue = when {
                signature.returnType is IrPrimitive && signature.returnType.kind == PrimitiveKind.UNIT -> null
                blockResult != null -> blockResult
                else -> IrUndef(signature.returnType)
            }
            builder.emitTerminator(
                IrReturn(
                    id = -1,
                    type = signature.returnType,
                    value = returnValue,
                ),
            )
        }

        valueEnv.leaveScope()
        valueEnv.popFunction()
        context.currentFunction = null
        context.currentScope = previousScope
        return function
    }

    fun emitMethod(fnSymbol: Function, implType: Type, node: frontend.ast.FunctionItemNode): IrFunction {
        return emitFunction(fnSymbol, node, implType)
    }

    fun emitBlock(block: BlockExprNode, expectValue: Boolean, expectedType: IrType? = null): IrValue? {
        valueEnv.enterScope()
        var result: IrValue? = null
        for ((index, stmt) in block.stmts.withIndex()) {
            val isLastExpr = expectValue && index == block.stmts.lastIndex && stmt is ExprStmtNode && !stmt.hasSemiColon
            result = emitStmt(stmt, isLastExpr, expectedType)
            if (!builder.hasInsertionPoint()) break
        }
        valueEnv.leaveScope()
        return result
    }

    private fun emitStmt(stmt: StmtNode, expectValue: Boolean, expectedType: IrType?): IrValue? = when (stmt) {
        is LetStmtNode -> {
            emitLet(stmt)
            null
        }

        is ExprStmtNode -> {
            val value = exprEmitter.emitExpr(stmt.expr, expectedType.takeIf { expectValue })
            if (expectValue) value else null
        }

        is ItemStmtNode -> null // items are handled by higher-level drivers
        NullStmtNode -> null
    }

    private fun emitLet(stmt: LetStmtNode) {
        val pattern = stmt.pattern as? IdentifierPatternNode
            ?: error("Only identifier patterns are supported in codegen")
        val initializer = stmt.expr ?: error("let without initializer is not supported in codegen")
        val expr = exprEmitter.emitExpr(initializer)

        // Simple heuristic: mutable or ref bindings get a stack slot, others stay SSA.
        if (pattern.hasMut || pattern.hasRef) {
            val slotName = builder.freshLocalName(pattern.id)
            val address = builder.emit(
                IrAlloca(
                    id = -1,
                    type = IrPointer(expr.type),
                    allocatedType = expr.type,
                    slotName = slotName,
                ),
            )
            builder.emit(
                IrStore(
                    id = -1,
                    type = IrPrimitive(PrimitiveKind.UNIT),
                    address = address,
                    value = expr,
                ),
            )
            valueEnv.bind(pattern.id, StackSlot(address, expr.type))
        } else {
            valueEnv.bind(pattern.id, SsaValue(expr))
        }
    }

    private fun bindParameters(fnSymbol: Function, signature: IrFunctionSignature) {
        var index = 0
        fnSymbol.selfParam?.let {
            val param = IrParameter(index, "self", signature.parameters[index])
            valueEnv.bind("self", SsaValue(param))
            index++
        }
        fnSymbol.params.forEach { param ->
            val irParam = IrParameter(index, param.name, signature.parameters[index])
            valueEnv.bind(param.name, SsaValue(irParam))
            index++
        }
    }

}
