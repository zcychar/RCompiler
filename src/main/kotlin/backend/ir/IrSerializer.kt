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
        emitBuiltinPrologue()
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

    private fun StringBuilder.emitBuiltinPrologue() {
        appendLine("declare i32 @printf(i8*, ...)")
        appendLine("declare i32 @scanf(i8*, ...)")
        appendLine("@.str.d = private unnamed_addr constant [3 x i8] c\"%d\\00\"")
        appendLine("@.str.d_ln = private unnamed_addr constant [4 x i8] c\"%d\\0A\\00\"")
        appendLine("define void @printInt(i32 %arg0) {")
        appendLine("entry:")
        appendLine("  %0 = call i32 @printf(i8* getelementptr ([3 x i8], [3 x i8]* @.str.d, i32 0, i32 0), i32 %arg0)")
        appendLine("  ret void")
        appendLine("}")
        appendLine("define void @printlnInt(i32 %arg0) {")
        appendLine("entry:")
        appendLine("  %0 = call i32 @printf(i8* getelementptr ([4 x i8], [4 x i8]* @.str.d_ln, i32 0, i32 0), i32 %arg0)")
        appendLine("  ret void")
        appendLine("}")
        appendLine("define i32 @getInt() {")
        appendLine("entry:")
        appendLine("  %0 = alloca i32")
        appendLine("  %1 = call i32 @scanf(i8* getelementptr ([3 x i8], [3 x i8]* @.str.d, i32 0, i32 0), i32* %0)")
        appendLine("  %2 = load i32, i32* %0")
        appendLine("  ret i32 %2")
        appendLine("}")
        appendLine("define void @exit(i32 %arg0) {")
        appendLine("entry:")
        appendLine("  ret void")
        appendLine("}")
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
