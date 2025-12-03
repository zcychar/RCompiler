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

    var currentFunction: IrFunction? = null
    var currentScope: Scope? = null

    /**
     * Compute or retrieve the unique IR-level name for a semantic function.
     * Methods and associated functions are qualified with their impl type name
     * to avoid collisions (e.g., `Edge.new` vs `Graph.new`).
     */
    fun irFunctionName(function: frontend.semantic.Function, ownerOverride: Type? = null): String {
        val owner = ownerOverride ?: function.self
        val ownerName = when (owner) {
            is frontend.semantic.StructType -> owner.name
            is frontend.semantic.EnumType -> owner.name
            else -> null
        }
        return if (ownerName != null) "$ownerName.${function.name}" else function.name
    }
}
