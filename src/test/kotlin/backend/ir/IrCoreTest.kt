package backend.ir

import utils.CompileError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IrCoreTest {
    @Test
    fun `module rejects duplicate definitions`() {
        val module = IrModule()
        val sig = IrFunctionSignature(emptyList(), IrPrimitive(PrimitiveKind.UNIT))
        val f1 = IrFunction("foo", sig)
        val f2 = IrFunction("foo", sig)
        module.declareFunction(f1)
        assertFailsWith<CompileError> { module.declareFunction(f2) }

        val g1 = IrGlobal("g", IrPrimitive(PrimitiveKind.I32), IrIntConstant(1, IrPrimitive(PrimitiveKind.I32)))
        val g2 = IrGlobal("g", IrPrimitive(PrimitiveKind.I32), IrIntConstant(2, IrPrimitive(PrimitiveKind.I32)))
        module.declareGlobal(g1)
        assertFailsWith<CompileError> { module.declareGlobal(g2) }
    }

    @Test
    fun `basic block enforces single terminator`() {
        val block = IrBasicBlock("b")
        block.setTerminator(IrJump(id = -1, type = IrPrimitive(PrimitiveKind.UNIT), target = "exit"))
        assertFailsWith<CompileError> {
            block.setTerminator(IrJump(id = -1, type = IrPrimitive(PrimitiveKind.UNIT), target = "exit2"))
        }
        assertFailsWith<CompileError> {
            block.append(IrConst(id = -1, type = IrPrimitive(PrimitiveKind.I32), constant = IrIntConstant(1, IrPrimitive(PrimitiveKind.I32))))
        }
    }

    @Test
    fun `global render includes name and initializer`() {
        val global = IrGlobal("g", IrPrimitive(PrimitiveKind.I32), IrIntConstant(42, IrPrimitive(PrimitiveKind.I32)))
        val text = global.render()
        assertTrue(text.contains("@g"))
        assertTrue(text.contains("42"))
    }

    @Test
    fun `function entry block auto created`() {
        val sig = IrFunctionSignature(emptyList(), IrPrimitive(PrimitiveKind.UNIT))
        val fn = IrFunction("f", sig)
        val entry = fn.entryBlock()
        assertEquals("entry", entry.label)
        assertEquals(entry, fn.blocks.first())
    }
}
