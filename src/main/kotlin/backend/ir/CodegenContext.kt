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

}
