package backend.ir

import frontend.ast.CrateNode
import frontend.semantic.ConstValue
import frontend.semantic.Function
import frontend.semantic.Namespace
import frontend.semantic.Scope
import frontend.semantic.Struct
import frontend.semantic.StructType
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

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
                is frontend.ast.FunctionItemNode -> emitFunction(scope, functionEmitter, item)
                is frontend.ast.ImplItemNode -> emitImpl(scope, functionEmitter, item, context)
                is frontend.ast.StructItemNode -> emitStruct(scope, item, context)
                is frontend.ast.ConstItemNode -> emitConst(scope, item, context)
                is frontend.ast.EnumItemNode -> Unit // enums map to i32; no extra type emission yet
                else -> Unit
            }
        }

        val irText = context.module.render()
        val output = Paths.get("build", "output.ll")
        Files.createDirectories(output.parent)
        Files.writeString(output, irText)
        return irText
    }

    private fun emitFunction(scope: Scope, functionEmitter: FunctionEmitter, item: frontend.ast.FunctionItemNode) {
        val symbol = scope.resolve(item.name, Namespace.VALUE) as? Function ?: return
        functionEmitter.emitFunction(symbol, item)
    }

    private fun emitImpl(scope: Scope, functionEmitter: FunctionEmitter, impl: frontend.ast.ImplItemNode, context: CodegenContext) {
        impl.items.filterIsInstance<frontend.ast.FunctionItemNode>().forEach { fn ->
            val implScope = impl.scope ?: return@forEach
            val symbol = implScope.resolve(fn.name, Namespace.VALUE) as? Function ?: return@forEach
            val implType = context.typeMapper.resolveImplType(scope, impl)
            functionEmitter.emitMethod(symbol, implType, fn)
        }
    }

    private fun emitStruct(scope: Scope, item: frontend.ast.StructItemNode, context: CodegenContext) {
        val symbol = scope.resolve(item.name, Namespace.TYPE) as? Struct ?: return
        val irStruct = context.typeMapper.structLayout(symbol.type)
        if (irStruct is IrStruct) {
            context.module.declareType(symbol.name, irStruct)
        }
    }

    private fun emitConst(scope: Scope, item: frontend.ast.ConstItemNode, context: CodegenContext) {
        val symbol = scope.resolve(item.name, Namespace.VALUE) as? frontend.semantic.Constant ?: return
        val value = symbol.value ?: return
        val irConst = constToIrConstant(value, context) ?: return
        val irType = irConst.type
        context.module.declareGlobal(IrGlobal(symbol.name, irType, irConst))
    }

    private fun constToIrConstant(value: ConstValue, context: CodegenContext): IrConstant? = when (value) {
        is ConstValue.Int -> IrIntConstant(value.value, context.typeMapper.toIrType(value.actualType))
        // IR-1 only uses integer consts; other shapes are skipped for now.
        else -> null
    }
}
