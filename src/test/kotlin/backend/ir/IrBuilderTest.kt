package backend.ir

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IrBuilderTest {
    @Test
    fun `emit terminator clears insertion point`() {
        val module = IrModule()
        val builder = IrBuilder(module)
        val fn = IrFunction("f", IrFunctionSignature(emptyList(), IrPrimitive(PrimitiveKind.UNIT)))
        builder.positionAt(fn, fn.entryBlock())
        builder.emitTerminator(IrJump(id = -1, type = IrPrimitive(PrimitiveKind.UNIT), target = "exit"))
        assertTrue(!builder.hasInsertionPoint())
    }

    @Test
    fun `fresh local names increment`() {
        val module = IrModule()
        val builder = IrBuilder(module)
        val name1 = builder.freshLocalName("x")
        val name2 = builder.freshLocalName("x")
        assertEquals("x", name1)
        assertEquals("x.2", name2)
    }
}
