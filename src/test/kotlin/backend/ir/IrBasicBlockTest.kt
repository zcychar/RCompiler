package backend.ir

import utils.CompileError
import kotlin.test.Test
import kotlin.test.assertFailsWith

class IrBasicBlockTest {
    @Test
    fun `cannot append after terminator`() {
        val block = IrBasicBlock("b")
        block.setTerminator(IrJump(id = -1, type = IrPrimitive(PrimitiveKind.UNIT), target = "exit"))
        assertFailsWith<CompileError> {
            block.append(
                IrConst(id = -1, type = IrPrimitive(PrimitiveKind.I32), constant = IrIntConstant(1, IrPrimitive(PrimitiveKind.I32))),
            )
        }
    }

    @Test
    fun `double terminator fails`() {
        val block = IrBasicBlock("b")
        block.setTerminator(IrJump(id = -1, type = IrPrimitive(PrimitiveKind.UNIT), target = "exit"))
        assertFailsWith<CompileError> {
            block.setTerminator(IrJump(id = -1, type = IrPrimitive(PrimitiveKind.UNIT), target = "exit2"))
        }
    }
}
