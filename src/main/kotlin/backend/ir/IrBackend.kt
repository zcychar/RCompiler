package backend.ir

import frontend.ast.CrateNode
import frontend.semantic.Function
import frontend.semantic.Namespace
import frontend.semantic.Scope
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Entry point for lowering a typed crate into IR. Currently emits an empty module stub
 * so the pipeline can be exercised end-to-end.
 */
class IrBackend(
    private val serializer: IrSerializer = IrSerializer(),
) {
    fun generate(crate: CrateNode, globalScope: Scope): Path {
        val context = CodegenContext(rootScope = crate.scope ?: globalScope)
        val functionEmitter = FunctionEmitter(context)

        val scope = crate.scope ?: globalScope
        crate.items.forEach { item ->
            when (item) {
                is frontend.ast.FunctionItemNode -> {
                    val symbol = scope.resolve(item.name, Namespace.VALUE) as? Function
                        ?: return@forEach
                    functionEmitter.emitFunction(symbol, item)
                }

                is frontend.ast.ImplItemNode -> {
                    item.items.filterIsInstance<frontend.ast.FunctionItemNode>().forEach { fn ->
                        val implScope = item.scope ?: return@forEach
                        val symbol = implScope.resolve(fn.name, Namespace.VALUE) as? Function ?: return@forEach
                        val implType = context.typeMapper.resolveImplType(scope, item)
                        functionEmitter.emitMethod(symbol, implType, fn)
                    }
                }

                else -> Unit
            }
        }
        val output = Paths.get("build", "output.ll")
        serializer.write(context.module, output)
        return output
    }
}
