package backend.codegen

// Emits final RISC-V assembly text from machine functions.

import backend.codegen.riscv.*
import backend.ir.*

object AsmEmitter {

    private val BUILTIN_NAMES = setOf(
        "exit.",
        "printInt.",
        "printlnInt.",
        "getInt.",
        "print.",
        "println.",
        "getString.",
    )

    fun emit(functions: List<RvMachineFunction>, irModule: IrModule): String = buildString {
        emitPreamble(this)
        emitTextSection(this, functions)
        emitBuiltinRuntime(this, functions)
        emitRodataSection(this, functions)
    }

    fun emitFunction(mf: RvMachineFunction): String = buildString {
        appendFunction(this, mf)
    }

    private fun emitPreamble(sb: StringBuilder) {
        sb.appendLine("    .option nopic")
    }

    private fun emitTextSection(sb: StringBuilder, functions: List<RvMachineFunction>) {
        sb.appendLine("    .text")

        for (mf in functions) {

            if (mf.name == "main") {
                sb.appendLine("    .globl main")
            }
            sb.appendLine()
            appendFunction(sb, mf)
        }
    }

    private fun appendFunction(sb: StringBuilder, mf: RvMachineFunction) {

        sb.appendLine("${mf.name}:")

        for (block in mf.blocks) {

            if (block.label != mf.name) {
                sb.appendLine("${block.label}:")
            }

            for (inst in block.instructions) {

                if (inst is RvInst.Comment) {
                    sb.appendLine("    ${inst.render()}")
                } else {
                    sb.appendLine("    ${inst.render()}")
                }
            }
        }
    }

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

    private fun emitBuiltinRuntime(sb: StringBuilder, functions: List<RvMachineFunction>) {
        val used = collectUsedBuiltins(functions)
        if (used.isEmpty()) return

        sb.appendLine()
        sb.appendLine("    # ---- builtin runtime ----")

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

    private fun emitExit(sb: StringBuilder) {
        sb.appendLine("exit.:")
        sb.appendLine("    ret")
    }

    private fun emitPrintInt(sb: StringBuilder) {
        sb.appendLine("printInt.:")
        sb.appendLine("    addi  sp, sp, -16")
        sb.appendLine("    sd  ra, 8(sp)")
        sb.appendLine("    mv  a1, a0")
        sb.appendLine("    lui  a0, %hi(.L__fmt_d)")
        sb.appendLine("    addi  a0, a0, %lo(.L__fmt_d)")
        sb.appendLine("    call  printf")
        sb.appendLine("    ld  ra, 8(sp)")
        sb.appendLine("    addi  sp, sp, 16")
        sb.appendLine("    ret")
    }

    private fun emitPrintlnInt(sb: StringBuilder) {
        sb.appendLine("printlnInt.:")
        sb.appendLine("    addi  sp, sp, -16")
        sb.appendLine("    sd  ra, 8(sp)")
        sb.appendLine("    mv  a1, a0")
        sb.appendLine("    lui  a0, %hi(.L__fmt_d_ln)")
        sb.appendLine("    addi  a0, a0, %lo(.L__fmt_d_ln)")
        sb.appendLine("    call  printf")
        sb.appendLine("    ld  ra, 8(sp)")
        sb.appendLine("    addi  sp, sp, 16")
        sb.appendLine("    ret")
    }

    private fun emitGetInt(sb: StringBuilder) {
        sb.appendLine("getInt.:")
        sb.appendLine("    addi  sp, sp, -16")
        sb.appendLine("    sd  ra, 8(sp)")
        sb.appendLine("    lui  a0, %hi(.L__fmt_d)")
        sb.appendLine("    addi  a0, a0, %lo(.L__fmt_d)")
        sb.appendLine("    addi  a1, sp, 0")
        sb.appendLine("    call  scanf")
        sb.appendLine("    lw  a0, 0(sp)")
        sb.appendLine("    ld  ra, 8(sp)")
        sb.appendLine("    addi  sp, sp, 16")
        sb.appendLine("    ret")
    }

    private fun emitPrint(sb: StringBuilder) {
        sb.appendLine("print.:")
        sb.appendLine("    addi  sp, sp, -16")
        sb.appendLine("    sd  ra, 8(sp)")
        sb.appendLine("    mv  a1, a0")
        sb.appendLine("    lui  a0, %hi(.L__fmt_s)")
        sb.appendLine("    addi  a0, a0, %lo(.L__fmt_s)")
        sb.appendLine("    call  printf")
        sb.appendLine("    ld  ra, 8(sp)")
        sb.appendLine("    addi  sp, sp, 16")
        sb.appendLine("    ret")
    }

    private fun emitPrintln(sb: StringBuilder) {
        sb.appendLine("println.:")
        sb.appendLine("    addi  sp, sp, -16")
        sb.appendLine("    sd  ra, 8(sp)")
        sb.appendLine("    mv  a1, a0")
        sb.appendLine("    lui  a0, %hi(.L__fmt_s_ln)")
        sb.appendLine("    addi  a0, a0, %lo(.L__fmt_s_ln)")
        sb.appendLine("    call  printf")
        sb.appendLine("    ld  ra, 8(sp)")
        sb.appendLine("    addi  sp, sp, 16")
        sb.appendLine("    ret")
    }

    private fun emitGetString(sb: StringBuilder) {
        sb.appendLine("getString.:")
        sb.appendLine("    addi  sp, sp, -16")
        sb.appendLine("    sd  ra, 8(sp)")
        sb.appendLine("    li  a0, 256")
        sb.appendLine("    call  malloc")
        sb.appendLine("    sd  a0, 0(sp)")
        sb.appendLine("    mv  a1, a0")
        sb.appendLine("    lui  a0, %hi(.L__fmt_s)")
        sb.appendLine("    addi  a0, a0, %lo(.L__fmt_s)")
        sb.appendLine("    call  scanf")
        sb.appendLine("    ld  a0, 0(sp)")
        sb.appendLine("    ld  ra, 8(sp)")
        sb.appendLine("    addi  sp, sp, 16")
        sb.appendLine("    ret")
    }

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
