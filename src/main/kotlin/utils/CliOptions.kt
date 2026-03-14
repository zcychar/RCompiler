package utils

/**
 * CLI options for the RCompiler.
 *
 * Usage:
 *   rcompiler [options] [INPUT]
 *
 * INPUT is a source file path. Use "-" or omit to read from stdin.
 *
 * Output control:
 *   --emit=ir|asm          Select output format (default: asm)
 *   -o PATH, --output=PATH Write output to PATH (default: stdout)
 *
 * Legacy aliases (still accepted):
 *   --ir-out=PATH          Equivalent to --emit=ir -o PATH
 *   --asm-out=PATH         Equivalent to --emit=asm -o PATH
 *
 * Optimization:
 *   --opt=on|off           Toggle IR optimization passes (default: on)
 *   --no-opt               Shorthand for --opt=off
 *
 * Debugging:
 *   --debug[=STAGES]       Enable debug output for comma-separated STAGES
 *                           Stages: parse, semantic, ir, codegen, all
 *                           Bare --debug is equivalent to --debug=all
 *
 * Other:
 *   --help, -h             Print help message and exit
 */
data class CliOptions(
    val inputPath: String?,
    val emit: EmitMode,
    val outputPath: String?,
    val debugStages: Set<DebugStage>,
    val optimize: Boolean = true,
    val showHelp: Boolean = false,
) {
    val debugParse: Boolean get() = debugStages.contains(DebugStage.PARSE) || debugAll
    val debugSemantic: Boolean get() = debugStages.contains(DebugStage.SEMANTIC) || debugAll
    val debugIr: Boolean get() = debugStages.contains(DebugStage.IR) || debugAll
    val debugCodegen: Boolean get() = debugStages.contains(DebugStage.CODEGEN) || debugAll
    val debugAll: Boolean get() = debugStages.contains(DebugStage.ALL)

    companion object {
        fun parse(args: Array<String>): CliOptions {
            val stages = mutableSetOf<DebugStage>()
            var emit: EmitMode? = null
            var outputPath: String? = null
            var optimize = true
            var showHelp = false
            val positional = mutableListOf<String>()

            // Legacy paths — resolved after the loop
            var legacyIrOut: String? = null
            var legacyAsmOut: String? = null

            val iter = args.iterator()
            while (iter.hasNext()) {
                val arg = iter.next()
                when {
                    arg == "--help" || arg == "-h" -> showHelp = true

                    arg == "--debug" -> stages.add(DebugStage.ALL)
                    arg.startsWith("--debug=") -> {
                        val parts = arg.substringAfter("=").split(",")
                            .map { it.trim().lowercase() }
                            .filter { it.isNotEmpty() }
                        parts.forEach { part ->
                            val stage = DebugStage.fromString(part)
                                ?: throw IllegalArgumentException(
                                    "Unknown debug stage: $part (use parse, semantic, ir, codegen, all)"
                                )
                            stages.add(stage)
                        }
                    }

                    // Unified output flags
                    arg.startsWith("--emit=") -> {
                        emit = when (arg.substringAfter("=").trim().lowercase()) {
                            "ir" -> EmitMode.IR
                            "asm", "assembly" -> EmitMode.ASM
                            else -> throw IllegalArgumentException(
                                "Unknown emit mode: ${arg.substringAfter("=")} (use ir or asm)"
                            )
                        }
                    }
                    arg == "-o" -> {
                        if (!iter.hasNext()) throw IllegalArgumentException("-o requires a path argument")
                        outputPath = iter.next()
                    }
                    arg.startsWith("-o") && arg.length > 2 && !arg.startsWith("-o-") -> {
                        // Support -oPATH (no space) style
                        outputPath = arg.substring(2)
                    }
                    arg.startsWith("--output=") -> outputPath = arg.substringAfter("=")

                    // Legacy aliases (backward compatible)
                    arg.startsWith("--ir-out=") -> legacyIrOut = arg.substringAfter("=")
                    arg.startsWith("--asm-out=") -> legacyAsmOut = arg.substringAfter("=")

                    // Optimization
                    arg == "--no-opt" -> optimize = false
                    arg.startsWith("--opt=") -> {
                        when (arg.substringAfter("=").trim().lowercase()) {
                            "on", "true", "1", "yes", "y" -> optimize = true
                            "off", "false", "0", "no", "n" -> optimize = false
                            else -> throw IllegalArgumentException("Unknown --opt value (use on/off)")
                        }
                    }

                    arg.startsWith("--") -> {
                        // Ignore unknown flags for forward compatibility
                    }
                    else -> positional.add(arg)
                }
            }

            // Resolve legacy flags into the unified model.
            // Explicit --emit / -o always take priority over legacy flags.
            // If both --ir-out and --asm-out are given, --asm-out wins
            // (assembly is the later pipeline stage).
            if (emit == null && outputPath == null) {
                when {
                    legacyAsmOut != null -> { emit = EmitMode.ASM; outputPath = legacyAsmOut }
                    legacyIrOut != null  -> { emit = EmitMode.IR;  outputPath = legacyIrOut }
                }
            }

            // Default emit mode: assembly (full pipeline).
            val resolvedEmit = emit ?: EmitMode.ASM

            val input = positional.firstOrNull()
            return CliOptions(input, resolvedEmit, outputPath, stages, optimize, showHelp)
        }

        /** Human-readable help text printed by --help. */
        val HELP_TEXT = """
            |RCompiler — a Rust-subset compiler targeting RISC-V (RV32IM)
            |
            |Usage: rcompiler [options] [INPUT]
            |
            |INPUT is a source file path. Use "-" or omit to read from stdin.
            |
            |Output control:
            |  --emit=ir|asm          Select output format (default: asm)
            |  -o PATH, --output=PATH Write output to PATH (default: stdout)
            |
            |Legacy aliases (still accepted):
            |  --ir-out=PATH          Equivalent to --emit=ir -o PATH
            |  --asm-out=PATH         Equivalent to --emit=asm -o PATH
            |
            |Optimization:
            |  --opt=on|off           Toggle IR optimization passes (default: on)
            |  --no-opt               Shorthand for --opt=off
            |
            |Debugging:
            |  --debug[=STAGES]       Enable debug output for comma-separated STAGES
            |                         Stages: parse, semantic, ir, codegen, all
            |                         Bare --debug is equivalent to --debug=all
            |
            |Examples:
            |  rcompiler foo.rx                     Compile to RISC-V asm on stdout
            |  rcompiler foo.rx -o foo.s            Compile to RISC-V asm file
            |  rcompiler foo.rx --emit=ir           Print IR to stdout
            |  rcompiler foo.rx --emit=ir -o foo.ll Write IR to file
            |  rcompiler foo.rx --no-opt            Disable optimization passes
            |  rcompiler foo.rx --debug=ir,codegen  Show IR and codegen debug output
        """.trimMargin()
    }
}

/**
 * What the compiler should emit as its primary output.
 */
enum class EmitMode {
    /** Emit LLVM-style IR text. */
    IR,
    /** Emit RISC-V assembly text (full codegen pipeline). */
    ASM,
}

enum class DebugStage {
    PARSE, SEMANTIC, IR, CODEGEN, ALL;

    companion object {
        fun fromString(raw: String): DebugStage? = when (raw.lowercase()) {
            "parse" -> PARSE
            "semantic" -> SEMANTIC
            "ir" -> IR
            "codegen" -> CODEGEN
            "all" -> ALL
            else -> null
        }
    }
}
