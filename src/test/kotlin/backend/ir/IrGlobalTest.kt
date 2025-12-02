package backend.ir

import kotlin.test.Test
import kotlin.test.assertTrue

class IrGlobalTest {
    @Test
    fun `render includes name and initializer`() {
        val global = IrGlobal("g", IrPrimitive(PrimitiveKind.I32), IrIntConstant(42, IrPrimitive(PrimitiveKind.I32)))
        val text = global.render()
        assertTrue(text.contains("@g"))
        assertTrue(text.contains("42"))
    }
}
