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
    private val typeMapper: TypeMapper = context.typeMapper,
    private val valueEnv: ValueEnv = context.valueEnv,
) {

    fun emitFunction(fnSymbol: Function, node: frontend.ast.FunctionItemNode): IrFunction {
        val signature = typeMapper.functionSignature(fnSymbol)
        val function = IrFunction(fnSymbol.name, signature)
        context.module.declareFunction(function)
        context.currentFunction = function
        val previousScope = context.currentScope
        context.currentScope = node.body?.scope ?: previousScope

        builder.positionAt(function, function.entryBlock())
        valueEnv.pushFunction(signature.returnType)
        valueEnv.enterScope()

        bindParameters(fnSymbol, signature)
        node.body?.let { emitBlock(it, expectValue = false) }

        valueEnv.leaveScope()
        valueEnv.popFunction()
        context.currentFunction = null
        context.currentScope = previousScope
        return function
    }

    fun emitMethod(fnSymbol: Function, implType: Type, node: frontend.ast.FunctionItemNode): IrFunction {
        typeMapper.beginMethod(implType)
        try {
            return emitFunction(fnSymbol, node)
        } finally {
            typeMapper.endMethod()
        }
    }

    fun emitBlock(block: BlockExprNode, expectValue: Boolean): IrValue? {
        valueEnv.enterScope()
        var result: IrValue? = null
        for ((index, stmt) in block.stmts.withIndex()) {
            val isLastExpr = expectValue && index == block.stmts.lastIndex && stmt is ExprStmtNode && !stmt.hasSemiColon
            result = emitStmt(stmt, isLastExpr)
            if (!builder.hasInsertionPoint()) break
        }
        valueEnv.leaveScope()
        return result
    }

    private fun emitStmt(stmt: StmtNode, expectValue: Boolean): IrValue? = when (stmt) {
        is LetStmtNode -> {
            emitLet(stmt)
            null
        }

        is ExprStmtNode -> {
            val value = exprEmitter.emitExpr(stmt.expr)
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
            valueEnv.bind(pattern.id, StackSlot(address, expr.type, pattern.hasMut))
        } else {
            valueEnv.bind(pattern.id, SsaValue(expr))
        }
    }

    private fun bindParameters(fnSymbol: Function, signature: IrFunctionSignature) {
        var index = 0
        fnSymbol.selfParam?.let {
            val param = IrParameter(index, "self", signature.parameters[index])
            valueEnv.bind("self", FunctionParam(index, param))
            index++
        }
        fnSymbol.params.forEach { param ->
            val irParam = IrParameter(index, param.name, signature.parameters[index])
            valueEnv.bind(param.name, FunctionParam(index, irParam))
            index++
        }
    }

}
