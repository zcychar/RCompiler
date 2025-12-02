package backend.ir

import frontend.Keyword
import frontend.Literal
import frontend.ast.BlockExprNode
import frontend.ast.ExprStmtNode
import frontend.ast.IfExprNode
import frontend.ast.LiteralExprNode
import kotlin.test.Test
import kotlin.test.assertTrue

class ExprEmitterIfTest {
    @Test
    fun `if expression emits branch terminator`() {
        val context = CodegenContext()
        val builder = context.builder
        val function = IrFunction("ifFn", IrFunctionSignature(emptyList(), IrPrimitive(PrimitiveKind.UNIT)))
        builder.positionAt(function, function.entryBlock())
        context.currentFunction = function
        context.valueEnv.pushFunction(IrPrimitive(PrimitiveKind.UNIT))
        context.valueEnv.enterScope()
        val emitter = ExprEmitter(context)

        val ifExpr = IfExprNode(
            conds = listOf(frontend.ast.CondExprNode(null, LiteralExprNode("true", Keyword.TRUE))),
            expr = BlockExprNode(false, listOf(ExprStmtNode(LiteralExprNode("1", Literal.INTEGER)))),
            elseExpr = BlockExprNode(false, listOf(ExprStmtNode(LiteralExprNode("2", Literal.INTEGER)))),
        )

        emitter.emitExpr(ifExpr)

        val terminators = function.blocks.mapNotNull { it.terminator }
        assertTrue(terminators.any { it is IrBranch })

        context.valueEnv.leaveScope()
        context.valueEnv.popFunction()
        context.currentFunction = null
    }
}
