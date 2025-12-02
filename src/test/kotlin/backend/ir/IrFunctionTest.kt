package backend.ir

import kotlin.test.Test
import kotlin.test.assertEquals

class IrFunctionTest {
    @Test
    fun `entry block auto created`() {
        val sig = IrFunctionSignature(emptyList(), IrPrimitive(PrimitiveKind.UNIT))
        val fn = IrFunction("f", sig)
        val entry = fn.entryBlock()
        assertEquals("entry", entry.label)
        assertEquals(entry, fn.blocks.first())
    }
}
