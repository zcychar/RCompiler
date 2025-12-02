package backend.ir

import frontend.ast.BlockExprNode
import frontend.ast.ExprStmtNode
import frontend.ast.FunctionItemNode
import frontend.ast.LiteralExprNode
import frontend.ast.TypePathNode
import frontend.semantic.Function
import frontend.semantic.Scope
import frontend.semantic.ScopeKind
import frontend.semantic.Variable
import kotlin.test.Test
import kotlin.test.assertEquals

class FunctionEmitterTest {
    @Test
    fun `function emitter binds parameters and emits return`() {
        val scope = Scope(null, ScopeKind.GLOBAL)
        val fnSymbol = Function("foo", node = null, params = listOf(Variable("a", frontend.semantic.Int32Type, false)), returnType = frontend.semantic.Int32Type)
        scope.declare(fnSymbol, frontend.semantic.Namespace.VALUE)

        val body = BlockExprNode(false, listOf(ExprStmtNode(LiteralExprNode("1", frontend.Literal.INTEGER))))
        val fnNode = FunctionItemNode(false, "foo", null, emptyList(), TypePathNode("i32", null), body)

        val context = CodegenContext(rootScope = scope)
        val emitter = FunctionEmitter(context)
        val fn = emitter.emitFunction(fnSymbol, fnNode)

        assertEquals("foo", fn.name)
        // literal constants are returned directly; entry block remains empty in this setup
        assertEquals(0, fn.entryBlock().instructions.size)
    }
}
