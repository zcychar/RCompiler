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
                is ConstItemNode -> context.emitConst(scope, item)
                else -> Unit
            }
        }
        crate.items.forEach { item ->
            when (item) {
                is StructItemNode -> context.emitStruct(scope, item,  functionEmitter)
                else -> Unit
            }
        }
        crate.items.forEach { item ->
            when (item) {
                is FunctionItemNode -> context.emitFunction(scope, functionEmitter, item)
                else -> Unit
            }
        }
        return context.module.render()
    }


}
