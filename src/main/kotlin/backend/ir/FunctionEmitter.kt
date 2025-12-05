package backend.ir

import frontend.ast.*
import frontend.semantic.Function
import frontend.semantic.NeverType
import frontend.semantic.RefType
import frontend.semantic.Type

/**
 * &self also store self copy too
 * other param's name is changed to param.tmp (ssa value) would be stored immediately at entry block with true names.
 */
class FunctionEmitter(
    private val context: CodegenContext,
    private val builder: IrBuilder = context.builder,
    private val exprEmitter: ExprEmitter = ExprEmitter(context),
    private val valueEnv: ValueEnv = context.valueEnv,
) {

    fun emitFunction(fnSymbol: Function, node: FunctionItemNode, owner: Type? = null): IrFunction {
        val signature = irFunctionSignature(fnSymbol)
        val ownerName = when (val resolvedOwner = owner ?: fnSymbol.self) {
            is frontend.semantic.StructType -> resolvedOwner.name
            is frontend.semantic.EnumType -> resolvedOwner.name
            else -> null
        }
        val irName = ownerName?.let { "$it.${fnSymbol.name}" } ?: fnSymbol.name
        val parameterNames = buildList {
            fnSymbol.selfParam?.let { add("self.tmp") }
            fnSymbol.params.forEach { add(it.name+".tmp") }
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
                    name = "",
                    type = signature.returnType,
                    value = returnValue,
                )
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

        val patternName = builder.freshLocalName(pattern.id)
        val patternValue = builder.emit(
            IrAlloca(
                name = patternName,
                type = IrPointer(expr.type),
                allocatedType = expr.type,
            ),patternName
        )
        builder.copy(expr,patternValue)
    }

    private fun bindParameters(fnSymbol: Function, signature: IrFunctionSignature) {
        var index = 0
        fnSymbol.selfParam?.let {
            val irParam = IrParameter(index, "self.tmp", signature.parameters[index])
            val parmAddr =builder.borrow("self",irParam)
            valueEnv.bind("self", parmAddr)
            index++
        }
        fnSymbol.params.forEach { param ->
            val irParam = IrParameter(index, fnSymbol.name+".tmp", signature.parameters[index])
            val parmAddr =builder.borrow(param.name,irParam)
            valueEnv.bind(param.name, parmAddr)
            index++
        }
    }

}

fun irFunctionSignature(function: Function): IrFunctionSignature {
    val params = mutableListOf<IrType>()
    function.selfParam?.let {
        val rawSelf = function.self ?: error("method missing self target")
        val selfSemantic = if (it.isRef) RefType(rawSelf, it.isMut) else rawSelf
        params += toIrType(selfSemantic)
    }
    params += function.params.map { param -> toIrType(param.type) }
    val ret = toIrType(function.returnType)
    return IrFunctionSignature(params, ret)
}

