package backend.ir

import frontend.ast.*
import frontend.semantic.*
import frontend.semantic.Function

/**
 * Entry point for lowering a typed crate into IR. Currently emits an empty module stub
 * so the pipeline can be exercised end-to-end.
 */
class IrBackend(
) {
    fun generate(crate: CrateNode, globalScope: Scope): String {
        val context = CodegenContext(rootScope = crate.scope ?: globalScope)
        val functionEmitter = FunctionEmitter(context)
        val scope = crate.scope ?: globalScope

        crate.items.forEach { item ->
            when (item) {
                is FunctionItemNode -> emitFunction(scope, functionEmitter, item)
                is StructItemNode -> emitStruct(scope, item, context, functionEmitter)
                is ConstItemNode -> emitConst(scope, item, context)
                else -> Unit
            }
        }

        return context.module.render()
    }

    private fun emitFunction(scope: Scope, functionEmitter: FunctionEmitter, item: FunctionItemNode) {
        val symbol = scope.resolve(item.name, Namespace.VALUE) as? Function ?: return
        functionEmitter.emitFunction(symbol, item)
    }

    private fun emitStruct(
        scope: Scope,
        item: StructItemNode,
        context: CodegenContext,
        functionEmitter: FunctionEmitter,
    ) {
        val symbol = scope.resolve(item.name, Namespace.TYPE) as? Struct ?: return
        val irStruct = structLayout(symbol.type)
        if (irStruct is IrStruct) {
            context.module.declareType(symbol.name, irStruct)
        }
        val previousScope = context.currentScope
        // Emit methods with self
        symbol.methods.values.forEach { fn ->
            val fnNode = fn.node as? FunctionItemNode ?: return@forEach
            val implScope = fnNode.declScope
            context.currentScope = implScope
            functionEmitter.emitMethod(fn, symbol.type, fnNode)
        }
        // Emit associated functions without self (not present in methods map)
        symbol.associateItems.values.forEach { assoc ->
            if (assoc is Function && assoc.selfParam == null) {
                val fnNode = assoc.node as? frontend.ast.FunctionItemNode ?: return@forEach
                val implScope = fnNode.declScope
                context.currentScope = implScope
                functionEmitter.emitMethod(assoc, symbol.type, fnNode)
            }
        }
        context.currentScope = previousScope
    }

    private fun emitConst(scope: Scope, item: ConstItemNode, context: CodegenContext) {
        val symbol = scope.resolve(item.name, Namespace.VALUE) as? Constant ?: return
        val value = symbol.value ?: return
        val irConst = constToIrConstant(value) ?: return
        val irType = irConst.type
        context.module.declareGlobal(IrGlobal(symbol.name, irType, irConst))
    }

    private fun constToIrConstant(value: ConstValue): IrConstant? = when (value) {
        is ConstValue.Int -> IrIntConstant(value.value, toIrType(value.actualType))
        // IR-1 only uses integer consts; other shapes are skipped for now.
        else -> null
    }
}
