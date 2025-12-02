package backend.ir

import utils.CompileError
import kotlin.test.Test
import kotlin.test.assertFailsWith

class IrModuleTest {
    @Test
    fun `declaring conflicting function fails`() {
        val module = IrModule()
        val sig = IrFunctionSignature(emptyList(), IrPrimitive(PrimitiveKind.UNIT))
        val f1 = IrFunction("foo", sig)
        val f2 = IrFunction("foo", sig)
        module.declareFunction(f1)
        assertFailsWith<CompileError> { module.declareFunction(f2) }
    }

    @Test
    fun `declaring conflicting global fails`() {
        val module = IrModule()
        val g1 = IrGlobal("g", IrPrimitive(PrimitiveKind.I32), IrIntConstant(1, IrPrimitive(PrimitiveKind.I32)))
        val g2 = IrGlobal("g", IrPrimitive(PrimitiveKind.I32), IrIntConstant(2, IrPrimitive(PrimitiveKind.I32)))
        module.declareGlobal(g1)
        assertFailsWith<CompileError> { module.declareGlobal(g2) }
    }
}
