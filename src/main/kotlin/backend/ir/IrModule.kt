package backend.ir

import utils.CompileError

class IrModule {
    private val types = mutableMapOf<String, IrType>()
    private val globals = mutableListOf<IrGlobal>()
    private val functions = mutableListOf<IrFunction>()

    fun declareType(name: String, type: IrType): IrType {
        val currentType = types[name]
        if (currentType != null && currentType != type) {
            CompileError.fail("", "conflicting type $name")
        }
        types[name] = type
        return type
    }

    fun declareGlobal(global: IrGlobal): IrGlobal {
        val currentGlobal = globals.find { it.name == global.name }
        if (currentGlobal != null && currentGlobal != global) {
            CompileError.fail("", "conflicting global ${global.name}")
        }
        if (currentGlobal == null) {
            globals.add(global)
        }
        return global
    }

    fun declareFunction(function: IrFunction): IrFunction {
        val currentFunction = functions.find { it.name == function.name }
        if (currentFunction != null && currentFunction != function) {
            CompileError.fail("", "conflicting function ${function.name}")
        }
        if (currentFunction == null) {
            functions.add(function)
        }
        return function
    }


    fun render(): String = buildString {
        emitBuiltinPrologue()
        types.values.filterIsInstance<IrStruct>().filter { it.name != null }.forEach { struct ->
            appendLine(struct.renderDefinition())
        }
        globals.forEach { global ->
            appendLine(global.render())
        }
        functions.forEach { function ->
            appendLine(function.render())
        }
    }
}

private fun StringBuilder.emitBuiltinPrologue() {
    appendLine("declare i32 @printf(i8*, ...)")
    appendLine("declare i32 @scanf(i8*, ...)")
    appendLine("define void @exit(i32 %code) {")
    appendLine("entry:")
    appendLine("  ret void")
    appendLine("}")
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
}
