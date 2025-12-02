package backend.ir

import frontend.semantic.Scope
import frontend.semantic.Type

/**
 * Shared backend context. Tracks the module under construction and exposes helpers
 * for type mapping, builder access, and string interning as outlined in the design docs.
 */
class CodegenContext(
    val module: IrModule = IrModule(),
    val rootScope: Scope? = null,
) {
    val builder: IrBuilder = IrBuilder(module)
    val valueEnv: ValueEnv = ValueEnv()
    val stringTable: MutableMap<String, IrGlobal> = mutableMapOf()
    val typeMapper: TypeMapper = TypeMapper(this)

    var currentFunction: IrFunction? = null
    var currentScope: Scope? = null

    fun withFunction(function: IrFunction, scope: Scope? = null, body: () -> Unit) {
        val previousFunction = currentFunction
        val previousScope = currentScope

        currentFunction = function
        currentScope = scope

        module.declareFunction(function)
        builder.positionAt(function, function.entryBlock())
        valueEnv.pushFunction(function.signature.returnType)
        valueEnv.enterScope()
        try {
            body()
        } finally {
            valueEnv.leaveScope()
            valueEnv.popFunction()
            currentFunction = previousFunction
            currentScope = previousScope
        }
    }

    fun resolveType(semantic: Type): IrType = typeMapper.toIrType(semantic)

    fun internString(value: String): IrGlobalRef {
        stringTable[value]?.let { return it.asValue() }

        val bytes = value.encodeToByteArray()
        val arrayType = IrArray(IrPrimitive(PrimitiveKind.CHAR), bytes.size + 1)
        val globalName = ".str.${stringTable.size}"
        val constant = IrStringConstant(value, arrayType)
        val global = IrGlobal(globalName, arrayType, constant)

        module.declareGlobal(global)
        stringTable[value] = global
        return global.asValue()
    }

    fun enterScope(scope: Scope) {
        currentScope = scope
        valueEnv.enterScope()
    }

    fun leaveScope() {
        valueEnv.leaveScope()
        currentScope = currentScope?.parentScope()
    }
}
