package backend.ir

import frontend.ast.ConstItemNode
import frontend.ast.CrateNode
import frontend.semantic.ConstValue
import frontend.semantic.Constant
import frontend.semantic.Namespace
import frontend.semantic.Scope
import frontend.semantic.ScopeKind
import kotlin.test.Test
import kotlin.test.assertTrue

class IrBackendIntegrationTest {
    @Test
    fun `backend emits prologue and integer consts`() {
        val scope = Scope(null, ScopeKind.GLOBAL)
        val constSymbol = Constant(
            "C",
            null,
            frontend.semantic.Int32Type,
            ConstValue.Int(42, frontend.semantic.Int32Type),
            frontend.semantic.ResolutionState.RESOLVED,
        )
        scope.declare(constSymbol, Namespace.VALUE)

        val crate = CrateNode(
            listOf(
                ConstItemNode("C", frontend.ast.TypePathNode("i32", null), null),
            ),
        )
        crate.scope = scope

        val backend = IrBackend()
        val text = backend.generate(crate, scope)
        assertTrue(text.contains("declare i32 @printf"))
        assertTrue(text.contains("@C"))
        assertTrue(text.contains("42"))
    }
}
