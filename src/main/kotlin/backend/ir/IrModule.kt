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

    fun globalByName(name: String): IrGlobal? = globals.find { it.name == name }
    fun functionByName(name: String): IrFunction? = functions.find { it.name == name }
    fun allTypes(): Collection<IrType> = types.values
    fun allGlobals(): List<IrGlobal> = globals
    fun allFunctions(): List<IrFunction> = functions
}
