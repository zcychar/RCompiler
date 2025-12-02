package backend.ir

import kotlin.test.Test
import kotlin.test.assertEquals

class IrValueRenderTest {
    @Test
    fun `constants render`() {
        assertEquals("true", IrBoolConstant(true, IrPrimitive(PrimitiveKind.BOOL)).render())
        assertEquals("42", IrIntConstant(42, IrPrimitive(PrimitiveKind.I32)).render())
        assertEquals("\"hi\"", IrStringConstant("hi", IrArray(IrPrimitive(PrimitiveKind.CHAR), 3)).render())
    }

    @Test
    fun `register and parameter render`() {
        assertEquals("%1", IrRegister(1, IrPrimitive(PrimitiveKind.I32)).render())
        assertEquals("%arg0", IrParameter(0, "", IrPrimitive(PrimitiveKind.I32)).render())
    }
}
