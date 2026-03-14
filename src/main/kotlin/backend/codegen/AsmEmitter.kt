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
 * - `.text` section with functions (including builtin runtime wrappers)
 * - `.data` / `.rodata` section with format string constants
 * - `.globl` directives for `main` (if present)
 * - Labels for each basic block
 * - Instructions indented with 4 spaces
 *
 * Builtin runtime functions
 * -------------------------
 * The source language exposes several prelude functions (`printInt`, `printlnInt`,
 * `getInt`, `exit`, etc.) which the front-end mangles with a trailing `.`
 * (e.g. `printInt.`).  In the IR path these are emitted as LLVM IR that calls
 * libc's `printf` / `scanf`.  For the native RISC-V path we emit thin assembly
 * wrappers that call `printf` / `scanf` — primitives recognised by REIMU at the
 * simulator level — so no external `builtin.s` is needed.
 */
object AsmEmitter {

    // ------------------------------------------------------------------
    //  Names of builtins the compiler may call
    // ------------------------------------------------------------------

    /** Builtin functions whose assembly wrappers we must emit. */
    private val BUILTIN_NAMES = setOf(
        "exit.",
        "printInt.",
        "printlnInt.",
        "getInt.",
        "print.",
        "println.",
        "getString.",
    )

    /**
     * Emit complete assembly for a module given:
     * @param functions   The list of finalized machine functions.
     * @param irModule    The IR module (for globals and type info).
     * @return Assembly text as a string.
     */
    fun emit(functions: List<RvMachineFunction>, irModule: IrModule): String = buildString {
        emitPreamble(this)
        emitTextSection(this, functions)
        emitBuiltinRuntime(this, functions)
        emitRodataSection(this, functions)
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

    // ------------------------------------------------------------------
    //  Builtin runtime wrappers
    // ------------------------------------------------------------------

    /**
     * Scan all emitted functions for `call` instructions that reference
     * builtin names and emit only the wrappers that are actually used.
     * This avoids polluting the output with unreferenced symbols.
     */
    private fun collectUsedBuiltins(functions: List<RvMachineFunction>): Set<String> {
        val used = mutableSetOf<String>()
        for (mf in functions) {
            for (block in mf.blocks) {
                for (inst in block.instructions) {
                    if (inst is RvInst.Call && inst.target in BUILTIN_NAMES) {
                        used.add(inst.target)
                    }
                }
            }
        }
        return used
    }

    /**
     * Emit RISC-V assembly implementations of the builtin prelude functions.
     *
     * These are thin wrappers around libc primitives (`printf`, `scanf`, …)
     * that REIMU recognises at the simulator level.
     *
     * Format string constants used by these wrappers are emitted separately
     * in [emitRodataSection].
     */
    private fun emitBuiltinRuntime(sb: StringBuilder, functions: List<RvMachineFunction>) {
        val used = collectUsedBuiltins(functions)
        if (used.isEmpty()) return

        sb.appendLine()
        sb.appendLine("    # ---- builtin runtime ----")

        // Track which format strings we need so rodata can emit only what's required.
        // (We always emit both since they're tiny, but the logic is here if needed.)

        if ("exit." in used) {
            sb.appendLine()
            emitExit(sb)
        }
        if ("printInt." in used) {
            sb.appendLine()
            emitPrintInt(sb)
        }
        if ("printlnInt." in used) {
            sb.appendLine()
            emitPrintlnInt(sb)
        }
        if ("getInt." in used) {
            sb.appendLine()
            emitGetInt(sb)
        }
        if ("print." in used) {
            sb.appendLine()
            emitPrint(sb)
        }
        if ("println." in used) {
            sb.appendLine()
            emitPrintln(sb)
        }
        if ("getString." in used) {
            sb.appendLine()
            emitGetString(sb)
        }
    }

    // -- exit.() : void  (no-op, just returns) -------------------------

    private fun emitExit(sb: StringBuilder) {
        sb.appendLine("exit.:")
        sb.appendLine("    ret")
    }

    // -- printInt.(a0: i32) : void  ------------------------------------
    //    printf("%d", a0)

    private fun emitPrintInt(sb: StringBuilder) {
        sb.appendLine("printInt.:")
        sb.appendLine("    addi  sp, sp, -16")
        sb.appendLine("    sw  ra, 12(sp)")
        sb.appendLine("    mv  a1, a0")
        sb.appendLine("    lui  a0, %hi(.L__fmt_d)")
        sb.appendLine("    addi  a0, a0, %lo(.L__fmt_d)")
        sb.appendLine("    call  printf")
        sb.appendLine("    lw  ra, 12(sp)")
        sb.appendLine("    addi  sp, sp, 16")
        sb.appendLine("    ret")
    }

    // -- printlnInt.(a0: i32) : void  ----------------------------------
    //    printf("%d\n", a0)

    private fun emitPrintlnInt(sb: StringBuilder) {
        sb.appendLine("printlnInt.:")
        sb.appendLine("    addi  sp, sp, -16")
        sb.appendLine("    sw  ra, 12(sp)")
        sb.appendLine("    mv  a1, a0")
        sb.appendLine("    lui  a0, %hi(.L__fmt_d_ln)")
        sb.appendLine("    addi  a0, a0, %lo(.L__fmt_d_ln)")
        sb.appendLine("    call  printf")
        sb.appendLine("    lw  ra, 12(sp)")
        sb.appendLine("    addi  sp, sp, 16")
        sb.appendLine("    ret")
    }

    // -- getInt.() : i32  ----------------------------------------------
    //    scanf("%d", &local); return local

    private fun emitGetInt(sb: StringBuilder) {
        sb.appendLine("getInt.:")
        sb.appendLine("    addi  sp, sp, -16")
        sb.appendLine("    sw  ra, 12(sp)")
        sb.appendLine("    lui  a0, %hi(.L__fmt_d)")
        sb.appendLine("    addi  a0, a0, %lo(.L__fmt_d)")
        sb.appendLine("    addi  a1, sp, 8")
        sb.appendLine("    call  scanf")
        sb.appendLine("    lw  a0, 8(sp)")
        sb.appendLine("    lw  ra, 12(sp)")
        sb.appendLine("    addi  sp, sp, 16")
        sb.appendLine("    ret")
    }

    // -- print.(a0: &str) : void  --------------------------------------
    //    printf("%s", a0)

    private fun emitPrint(sb: StringBuilder) {
        sb.appendLine("print.:")
        sb.appendLine("    addi  sp, sp, -16")
        sb.appendLine("    sw  ra, 12(sp)")
        sb.appendLine("    mv  a1, a0")
        sb.appendLine("    lui  a0, %hi(.L__fmt_s)")
        sb.appendLine("    addi  a0, a0, %lo(.L__fmt_s)")
        sb.appendLine("    call  printf")
        sb.appendLine("    lw  ra, 12(sp)")
        sb.appendLine("    addi  sp, sp, 16")
        sb.appendLine("    ret")
    }

    // -- println.(a0: &str) : void  ------------------------------------
    //    printf("%s\n", a0)

    private fun emitPrintln(sb: StringBuilder) {
        sb.appendLine("println.:")
        sb.appendLine("    addi  sp, sp, -16")
        sb.appendLine("    sw  ra, 12(sp)")
        sb.appendLine("    mv  a1, a0")
        sb.appendLine("    lui  a0, %hi(.L__fmt_s_ln)")
        sb.appendLine("    addi  a0, a0, %lo(.L__fmt_s_ln)")
        sb.appendLine("    call  printf")
        sb.appendLine("    lw  ra, 12(sp)")
        sb.appendLine("    addi  sp, sp, 16")
        sb.appendLine("    ret")
    }

    // -- getString.() : *u8  -------------------------------------------
    //    buf = malloc(256); scanf("%s", buf); return buf

    private fun emitGetString(sb: StringBuilder) {
        sb.appendLine("getString.:")
        sb.appendLine("    addi  sp, sp, -16")
        sb.appendLine("    sw  ra, 12(sp)")
        sb.appendLine("    li  a0, 256")
        sb.appendLine("    call  malloc")
        sb.appendLine("    sw  a0, 8(sp)")          // save buf ptr
        sb.appendLine("    mv  a1, a0")
        sb.appendLine("    lui  a0, %hi(.L__fmt_s)")
        sb.appendLine("    addi  a0, a0, %lo(.L__fmt_s)")
        sb.appendLine("    call  scanf")
        sb.appendLine("    lw  a0, 8(sp)")          // return buf
        sb.appendLine("    lw  ra, 12(sp)")
        sb.appendLine("    addi  sp, sp, 16")
        sb.appendLine("    ret")
    }

    // ------------------------------------------------------------------
    //  Read-only data section (format strings for builtins)
    // ------------------------------------------------------------------

    /**
     * Emit `.rodata` format string constants used by the builtin wrappers.
     * Only emits strings that are actually referenced.
     */
    private fun emitRodataSection(sb: StringBuilder, functions: List<RvMachineFunction>) {
        val used = collectUsedBuiltins(functions)
        if (used.isEmpty()) return

        val needFmtD   = "printInt." in used || "printlnInt." in used || "getInt." in used
        val needFmtDLn = "printlnInt." in used
        val needFmtS   = "print." in used || "println." in used || "getString." in used
        val needFmtSLn = "println." in used

        if (!needFmtD && !needFmtDLn && !needFmtS && !needFmtSLn) return

        sb.appendLine()
        sb.appendLine("    .section .rodata")

        if (needFmtD) {
            sb.appendLine(".L__fmt_d:")
            sb.appendLine("    .asciz \"%d\"")
        }
        if (needFmtDLn) {
            sb.appendLine(".L__fmt_d_ln:")
            sb.appendLine("    .asciz \"%d\\n\"")
        }
        if (needFmtS) {
            sb.appendLine(".L__fmt_s:")
            sb.appendLine("    .asciz \"%s\"")
        }
        if (needFmtSLn) {
            sb.appendLine(".L__fmt_s_ln:")
            sb.appendLine("    .asciz \"%s\\n\"")
        }
    }
}
