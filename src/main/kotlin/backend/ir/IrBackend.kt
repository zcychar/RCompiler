package backend.ir

import backend.codegen.RiscVCodegen
import backend.ir.opt.PassPipeline
import backend.ir.opt.InlinePass
import backend.ir.opt.Mem2RegPass
import frontend.ast.*
import frontend.semantic.*
import frontend.semantic.Function

/**
 * Entry point for lowering a typed crate into IR and optionally compiling
 * it all the way down to RISC-V assembly.
 *
 * The pipeline is:
 *   1. AST → IR (SSA form with φ nodes after Mem2Reg)
 *   2. IR optimization passes (currently just Mem2Reg)
 *   3. (Optional) RISC-V codegen: isel → regalloc → frame layout → asm emission
 */
class IrBackend(
    private val enableOptimization: Boolean = true,
) {
    private val passPipeline = PassPipeline(
        functionPasses = listOf(
            Mem2RegPass(),
            // PhiLoweringPass removed: φ nodes are kept in SSA form and
            // lowered to register moves by the instruction selector,
            // avoiding expensive memory traffic on the REIMU target.
        )
    )

    /**
     * Generate IR text from a typed crate.
     *
     * This is the original entry point used for IR-only output (e.g., `--ir-out`).
     */
    fun generate(crate: CrateNode, globalScope: Scope): String {
        val module = buildModule(crate, globalScope)
        return module.render()
    }

    /**
     * Generate RISC-V assembly text from a typed crate.
     *
     * Runs the full pipeline: AST → IR → optimization → codegen → assembly.
     *
     * @param crate       The typed AST crate.
     * @param globalScope The global/prelude scope.
     * @param debugDump   If true, print intermediate codegen state to stderr.
     * @return GNU-style RISC-V assembly text.
     */
    fun generateAsm(crate: CrateNode, globalScope: Scope, debugDump: Boolean = false): String {
        val module = buildModule(crate, globalScope)
        return RiscVCodegen.compile(module, debugDump = debugDump)
    }

    /**
     * Build and optimise the IR module from the AST.
     *
     * Shared by both [generate] (IR text output) and [generateAsm] (codegen output).
     */
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
