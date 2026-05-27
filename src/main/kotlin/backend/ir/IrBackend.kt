package backend.ir

// Builds optimized IR and optionally lowers it to RISC-V assembly.

import backend.codegen.RiscVCodegen
import backend.ir.opt.AggressiveDeadCodeEliminationPass
import backend.ir.opt.CfgSimplificationPass
import backend.ir.opt.ConstantPropagationPass
import backend.ir.opt.DeadCodeEliminationPass
import backend.ir.opt.InlinePass
import backend.ir.opt.Mem2RegPass
import backend.ir.opt.PassPipeline
import backend.ir.opt.SroaPass
import frontend.ast.*
import frontend.semantic.*
import frontend.semantic.Function

class IrBackend(
    private val enableOptimization: Boolean = true,
) {
    private val passPipeline = PassPipeline(
        functionPasses = listOf(
            SroaPass(),
            Mem2RegPass(),
            ConstantPropagationPass(),
            CfgSimplificationPass(),
            DeadCodeEliminationPass(),
            AggressiveDeadCodeEliminationPass(),
            CfgSimplificationPass(),
            DeadCodeEliminationPass(),

        )
    )

    fun generate(crate: CrateNode, globalScope: Scope): String {
        val module = buildModule(crate, globalScope)
        return module.render()
    }

    fun generateAsm(crate: CrateNode, globalScope: Scope, debugDump: Boolean = false): String {
        val module = buildModule(crate, globalScope)
        return RiscVCodegen.compile(module, debugDump = debugDump)
    }

    private fun buildModule(crate: CrateNode, globalScope: Scope): IrModule {
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
                is StructItemNode -> context.emitStruct(scope, item, functionEmitter)
                else -> Unit
            }
        }
        crate.items.forEach { item ->
            when (item) {
                is FunctionItemNode -> context.emitFunction(scope, functionEmitter, item)
                else -> Unit
            }
        }
        if (enableOptimization) {
            InlinePass().run(context.module)
            passPipeline.run(context.module)
        }
        return context.module
    }
}
