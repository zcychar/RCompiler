package utils

/**
 * Simple CLI options for the compiler.
 *
 * Supported flags:
 *  - --debug=LIST   where LIST is comma-separated stages: parse, semantic, ir, all
 *  - --debug        shorthand for --debug=all (backward compatible)
 *  - --ir-out=PATH  write IR to PATH
 *
 * Positional arg: input path (or "-" for stdin). If omitted, stdin is used.
 */
data class CliOptions(
    val inputPath: String?,
    val debugStages: Set<DebugStage>,
    val irOutPath: String?,
) {
    val debugParse: Boolean get() = debugStages.contains(DebugStage.PARSE) || debugAll
    val debugSemantic: Boolean get() = debugStages.contains(DebugStage.SEMANTIC) || debugAll
    val debugIr: Boolean get() = debugStages.contains(DebugStage.IR) || debugAll
    val debugAll: Boolean get() = debugStages.contains(DebugStage.ALL)

    companion object {
        fun parse(args: Array<String>): CliOptions {
            val stages = mutableSetOf<DebugStage>()
            var irOut: String? = null
            val positional = mutableListOf<String>()

            args.forEach { arg ->
                when {
                    arg == "--debug" -> stages.add(DebugStage.ALL)
                    arg.startsWith("--debug=") -> {
                        val parts = arg.substringAfter("=").split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                        parts.forEach { part ->
                            val stage = DebugStage.fromString(part)
                                ?: throw IllegalArgumentException("Unknown debug stage: $part (use parse, semantic, ir, all)")
                            stages.add(stage)
                        }
                    }
                    arg.startsWith("--ir-out=") -> irOut = arg.substringAfter("=")
                    arg.startsWith("--") -> {
                        // ignore unknown flags for now
                    }
                    else -> positional.add(arg)
                }
            }

            val input = positional.firstOrNull()
            return CliOptions(input, stages, irOut)
        }
    }
}

enum class DebugStage {
    PARSE, SEMANTIC, IR, ALL;

    companion object {
        fun fromString(raw: String): DebugStage? = when (raw.lowercase()) {
            "parse" -> PARSE
            "semantic" -> SEMANTIC
            "ir" -> IR
            "all" -> ALL
            else -> null
        }
    }
}
