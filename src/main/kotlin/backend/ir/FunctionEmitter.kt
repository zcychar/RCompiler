package backend.ir

import frontend.ast.BlockExprNode
import frontend.ast.FunctionItemNode
import frontend.semantic.Function
import frontend.semantic.Type

/**
 * Placeholder for new address-only FunctionEmitter implementation.
 * Methods are stubs to be filled in per the new design.
 */
class FunctionEmitter(
    private val context: CodegenContext,
    private val builder: IrBuilder = context.builder,
    private val exprEmitter: ExprEmitter = ExprEmitter(context),
    private val valueEnv: ValueEnv = context.valueEnv,
) {
    fun emitFunction(fnSymbol: Function, node: FunctionItemNode, owner: Type? = null): IrFunction {
        // TODO: implement
        throw NotImplementedError()
    }

    fun emitMethod(fnSymbol: Function, implType: Type, node: FunctionItemNode): IrFunction {
        // TODO: implement
        throw NotImplementedError()
    }

    fun emitBlock(block: BlockExprNode, expectValue: Boolean, expectedType: IrType? = null): IrValue? {
        // TODO: implement
        throw NotImplementedError()
    }
}
