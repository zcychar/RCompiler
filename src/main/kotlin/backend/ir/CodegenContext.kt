package backend.ir

import frontend.ast.ConstItemNode
import frontend.ast.FunctionItemNode
import frontend.ast.StructItemNode
import frontend.semantic.ConstValue
import frontend.semantic.Constant
import frontend.semantic.Function
import frontend.semantic.Namespace
import frontend.semantic.Scope
import frontend.semantic.Struct
import frontend.semantic.Type

/**
 * Shared backend context. Tracks the module under construction and exposes helpers
 * for type mapping, builder access, and string interning as outlined in the design docs.
 */
class CodegenContext(
    val module: IrModule = IrModule(),
    val rootScope: Scope? = null,
) {
    val builder: IrBuilder = IrBuilder(module)
    val valueEnv: ValueEnv = ValueEnv()

    var currentFunction: IrFunction? = null
    var currentScope: Scope? = null

    fun emitFunction(scope: Scope, functionEmitter: FunctionEmitter, item: FunctionItemNode) {
        val symbol = scope.resolve(item.name, Namespace.VALUE) as? frontend.semantic.Function ?: return
        functionEmitter.emitFunction(symbol, item)
    }

    fun emitStruct(
        scope: Scope,
        item: StructItemNode,
        functionEmitter: FunctionEmitter,
    ) {
        val symbol = scope.resolve(item.name, Namespace.TYPE) as? Struct ?: return
        val irStruct = structLayout(symbol.type)
        module.declareType(symbol.name, irStruct)
        val previousScope = currentScope
        // Emit methods with self
        symbol.methods.values.forEach { fn ->
            val fnNode = fn.node as? FunctionItemNode ?: return@forEach
            val implScope = fnNode.declScope
            currentScope = implScope
            functionEmitter.emitMethod(fn, symbol.type, fnNode)
        }
        // Emit associated functions without self (not present in methods map)
        symbol.associateItems.values.forEach { assoc ->
            if (assoc is Function && assoc.selfParam == null) {
                val fnNode = assoc.node as? frontend.ast.FunctionItemNode ?: return@forEach
                val implScope = fnNode.declScope
                currentScope = implScope
                functionEmitter.emitMethod(assoc, symbol.type, fnNode)
            }
        }
        currentScope = previousScope
    }

    fun emitConst(scope: Scope, item: ConstItemNode) {
        val symbol = scope.resolve(item.name, Namespace.VALUE) as? Constant ?: return
        val value = symbol.value ?: return
        val irConst = constToIrConstant(value) ?: return
        val irType = irConst.type
        module.declareGlobal(IrGlobal(symbol.name, irType, irConst))
    }

    private fun constToIrConstant(value: ConstValue): IrConstant? = when (value) {
        is ConstValue.Int -> IrConstant(value.value, toIrType(value.actualType))
        // IR-1 only uses integer consts; other shapes are skipped for now.
        else -> null
    }
}
