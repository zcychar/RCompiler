package backend.ir

import kotlin.test.Test
import kotlin.test.assertEquals

class IrTypeRenderTest {
    @Test
    fun `pointer and array render`() {
        assertEquals("i32*", IrPointer(IrPrimitive(PrimitiveKind.I32)).render())
        val array = IrArray(IrPrimitive(PrimitiveKind.I32), 4)
        assertEquals(4, array.length)
        assertEquals("[4 x i32]", array.render())
    }

    @Test
    fun `struct render`() {
        val struct = IrStruct("S", listOf(IrPrimitive(PrimitiveKind.I32), IrPrimitive(PrimitiveKind.BOOL)))
        assertEquals("%S = {i32, i1}", struct.render())
    }
}
