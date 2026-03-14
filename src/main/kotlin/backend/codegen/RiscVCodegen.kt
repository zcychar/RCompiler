package backend.codegen

import backend.codegen.riscv.*
import backend.ir.*

/**
 * Top-level RISC-V code generator.
 *
 * Orchestrates the complete pipeline from IR to assembly text:
 *
 *   IR Module
 *     → Instruction Selection  (per function: IR → machine IR with virtual regs)
 *     → Register Allocation    (per function: virtual regs → physical regs)
 *     → Frame Layout           (per function: stack layout + prologue/epilogue)
 *     → Assembly Emission      (all functions → assembly text)
 *
 * Usage:
 * ```
 *   val asm = RiscVCodegen.compile(irModule)
 * ```
 */
object RiscVCodegen {

    /**
     * Compile an IR module to RISC-V assembly text.
     *
     * @param irModule The IR module containing all functions and globals.
     * @param debugDump If true, print intermediate machine IR to stderr.
     * @return Complete GNU-style RISC-V assembly text.
     */
    fun compile(irModule: IrModule, debugDump: Boolean = false): String {
        // Phase 1: Instruction Selection — lower every IR function to machine IR.
        val isel = InstructionSelector(irModule)
        val machineFunctions = isel.selectAll()

        if (debugDump) {
            System.err.println("===== After Instruction Selection =====")
            for (mf in machineFunctions) {
                System.err.println(mf.debugRender())
            }
        }

        // Phase 2: Register Allocation — color virtual registers.
        val allocator = GraphColorRegAlloc()
        for (mf in machineFunctions) {
            allocator.allocate(mf)
        }

        if (debugDump) {
            System.err.println("===== After Register Allocation =====")
            for (mf in machineFunctions) {
                System.err.println(mf.debugRender())
            }
        }

        // Phase 3: Frame Layout — finalize stack offsets, insert prologue/epilogue.
        for (mf in machineFunctions) {
            FrameLayout.run(mf)
        }

        if (debugDump) {
            System.err.println("===== After Frame Layout =====")
            for (mf in machineFunctions) {
                System.err.println(mf.debugRender())
            }
        }

        // Phase 4: Assembly Emission — produce final assembly text.
        return AsmEmitter.emit(machineFunctions, irModule)
    }
}
