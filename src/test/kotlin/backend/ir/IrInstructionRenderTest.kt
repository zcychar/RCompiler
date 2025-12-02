package backend.ir

import kotlin.test.Test
import kotlin.test.assertEquals

class IrInstructionRenderTest {
    @Test
    fun `binary render includes operator and operands`() {
        val inst = IrBinary(
            id = 1,
            type = IrPrimitive(PrimitiveKind.I32),
            operator = BinaryOperator.ADD,
            lhs = IrRegister(0, IrPrimitive(PrimitiveKind.I32)),
            rhs = IrRegister(2, IrPrimitive(PrimitiveKind.I32)),
        )
        assertEquals("%1 = add i32 %0, %2", inst.render())
    }

    @Test
    fun `call render lists arguments`() {
        val call = IrCall(
            id = 5,
            type = IrPrimitive(PrimitiveKind.I32),
            callee = IrFunctionRef("foo", IrFunctionSignature(listOf(IrPrimitive(PrimitiveKind.I32)), IrPrimitive(PrimitiveKind.I32)).toFunctionPointer()),
            arguments = listOf(IrRegister(0, IrPrimitive(PrimitiveKind.I32))),
        )
        assertEquals("%5 = call i32 @foo(i32 %0)", call.render())
    }
}
