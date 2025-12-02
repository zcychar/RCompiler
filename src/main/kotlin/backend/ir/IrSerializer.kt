package backend.ir

import java.nio.file.Files
import java.nio.file.Path

/**
 * Minimal serializer that dumps the current module into a textual LLVM-like form.
 * This is intentionally lightweight to unblock backend wiring; richer formatting
 * can be layered later to match the full design doc.
 */
class IrSerializer {
    fun write(module: IrModule, target: Path) {
        Files.createDirectories(target.parent)
        Files.writeString(target, renderModule(module))
    }

    private fun renderModule(module: IrModule): String = buildString {
        module.allGlobals().forEach { global ->
            appendLine(global.render())
        }
        module.allTypes().forEach { type ->
            appendLine(type.render())
        }
        module.allFunctions().forEach { function ->
            appendLine(renderFunction(function))
        }
    }

    private fun renderFunction(function: IrFunction): String = buildString {
        append("define ")
        append(function.signature.returnType.render())
        append(" @")
        append(function.name)
        append('(')
        append(
            function.signature.parameters.mapIndexed { index, param ->
                "${param.render()} %arg$index"
            }.joinToString(", "),
        )
        append(')')
        appendLine(" {")
        function.blocks.forEach { block ->
            append(block.label).appendLine(":")
            block.instructions.forEach { inst ->
                append("  ").appendLine(inst.render())
            }
            block.terminator?.let { term ->
                append("  ").appendLine(term.render())
            }
        }
        append('}')
    }
}
