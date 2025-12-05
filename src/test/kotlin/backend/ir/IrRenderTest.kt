package backend.ir

import kotlin.test.Test
import kotlin.test.assertEquals

class IrRenderTest {
    @Test
    fun `value renderings`() {
        assertEquals("true", IrBoolConstant(true, IrPrimitive(PrimitiveKind.BOOL)).render())
        assertEquals("42", IrIntConstant(42, IrPrimitive(PrimitiveKind.I32)).render())
        assertEquals("%1", IrLocal(1, IrPrimitive(PrimitiveKind.I32)).render())
        assertEquals("%arg0", IrParameter(0, "", IrPrimitive(PrimitiveKind.I32)).render())
    }

    @Test
    fun `type renderings`() {
        assertEquals("i32*", IrPointer(IrPrimitive(PrimitiveKind.I32)).render())
        assertEquals("[4 x i32]", IrArray(IrPrimitive(PrimitiveKind.I32), 4).render())
        val struct = IrStruct("S", listOf(IrPrimitive(PrimitiveKind.I32), IrPrimitive(PrimitiveKind.BOOL)))
        assertEquals("%S", struct.render())
    }

    @Test
    fun `instruction renderings`() {
        val add = IrBinary(
            id = 1,
            type = IrPrimitive(PrimitiveKind.I32),
            operator = BinaryOperator.ADD,
            lhs = IrLocal(0, IrPrimitive(PrimitiveKind.I32)),
            rhs = IrLocal(2, IrPrimitive(PrimitiveKind.I32)),
        )
        assertEquals("%1 = add i32 %0, %2", add.render())

        val call = IrCall(
            id = 5,
            type = IrPrimitive(PrimitiveKind.I32),
            callee = IrFunctionRef("foo", IrFunctionSignature(listOf(IrPrimitive(PrimitiveKind.I32)), IrPrimitive(PrimitiveKind.I32)).toFunctionPointer()),
            arguments = listOf(IrLocal(0, IrPrimitive(PrimitiveKind.I32))),
        )
        assertEquals("%5 = call i32 @foo(i32 %0)", call.render())
    }
}
