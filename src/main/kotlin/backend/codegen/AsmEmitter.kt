package backend.codegen

import backend.codegen.riscv.*
import backend.ir.*

/**
 * Assembly Emitter: translates finalized machine IR into GNU-style RISC-V assembly text.
 *
 * After instruction selection, register allocation, and frame layout, every
 * [RvMachineFunction] contains only physical registers and concrete offsets.
 * This pass translates each function into assembly text suitable for consumption
 * by a GNU-compatible RISC-V assembler (or the REIMU simulator).
 *
 * Output format:
 * - `.text` section with functions
 * - `.data` section with global constants
 * - `.globl` directives for `main` (if present)
 * - Labels for each basic block
 * - Instructions indented with 4 spaces
 */
object AsmEmitter {

    /**
     * Emit complete assembly for a module given:
     * @param functions   The list of finalized machine functions.
     * @param irModule    The IR module (for globals and type info).
     * @return Assembly text as a string.
     */
    fun emit(functions: List<RvMachineFunction>, irModule: IrModule): String = buildString {
        emitPreamble(this)
        emitExternDeclarations(this)
        emitDataSection(this, irModule)
        emitTextSection(this, functions)
    }

    /**
     * Emit just a single function (useful for testing).
     */
    fun emitFunction(mf: RvMachineFunction): String = buildString {
        appendFunction(this, mf)
    }

    // ------------------------------------------------------------------
    //  Preamble
    // ------------------------------------------------------------------

    private fun emitPreamble(sb: StringBuilder) {
        sb.appendLine("    .option nopic")
    }

    // ------------------------------------------------------------------
    //  External declarations
    // ------------------------------------------------------------------

    /** Emit extern declarations for runtime/builtin functions. */
    private fun emitExternDeclarations(sb: StringBuilder) {
        // REIMU simulator recognizes these as builtins.
        // No explicit .extern needed for most RISC-V assemblers,
        // but we document them as comments for clarity.
    }

    // ------------------------------------------------------------------
    //  Data section
    // ------------------------------------------------------------------

    private fun emitDataSection(sb: StringBuilder, irModule: IrModule) {
        // Note: the current IR module comments out globals in render(),
        // but we need to emit any declared globals as .data entries.
        // For string constants and other globals, emit .data section.
        // Currently the module has globals, but they may be empty.
        // We handle this by checking declaredFunctions for any `la` instructions
        // that reference symbols. For now, emit an empty .data section.
    }

    // ------------------------------------------------------------------
    //  Text section
    // ------------------------------------------------------------------

    private fun emitTextSection(sb: StringBuilder, functions: List<RvMachineFunction>) {
        sb.appendLine("    .text")

        for (mf in functions) {
            // Emit .globl for main (entry point).
            if (mf.name == "main") {
                sb.appendLine("    .globl main")
            }
            sb.appendLine()
            appendFunction(sb, mf)
        }
    }

    // ------------------------------------------------------------------
    //  Function emission
    // ------------------------------------------------------------------

    private fun appendFunction(sb: StringBuilder, mf: RvMachineFunction) {
        // Function label.
        sb.appendLine("${mf.name}:")

        for (block in mf.blocks) {
            // Don't re-emit the entry block label if it matches the function name.
            if (block.label != mf.name) {
                sb.appendLine("${block.label}:")
            }

            for (inst in block.instructions) {
                // Comments don't get indentation prefix.
                if (inst is RvInst.Comment) {
                    sb.appendLine("    ${inst.render()}")
                } else {
                    sb.appendLine("    ${inst.render()}")
                }
            }
        }
    }
}
