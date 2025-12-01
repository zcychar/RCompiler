package backend.ir

import frontend.semantic.Type

/**
 * Minimal skeleton for lowering functions to IR.
 * Assumes semantics/type-checking are already done; no verification here.
 */
class FunctionEmitter(
    private val builder: IrBuilder,
    private val typeMapper: TypeMapper,
    private val valueEnv: ValueEnv,
) {
    fun emitFunction(name: String, paramTypes: List<Type>, returnType: Type, body: () -> Unit): IrFunction {
        val signature = typeMapper.functionSignature(paramTypes, returnType)
        val function = IrFunction(name, signature)
        builder.positionAt(function, function.entryBlock())
        valueEnv.pushFunction(signature.returnType)
        valueEnv.enterScope()
        // Binding parameters as SSA values; caller will supply actual params separately.
        paramTypes.forEachIndexed { index, type ->
            val param = IrParameter(index, "arg$index", typeMapper.toIrType(type))
            valueEnv.bind("arg$index", SsaValue(param))
        }
        body()
        valueEnv.leaveScope()
        valueEnv.popFunction()
        return function
    }
}
