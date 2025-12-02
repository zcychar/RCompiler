package backend.ir

import kotlin.test.Test
import kotlin.test.assertTrue

class ModuleRenderTest {
    @Test
    fun `module render emits prologue before functions`() {
        val module = IrModule()
        val fn = IrFunction("foo", IrFunctionSignature(emptyList(), IrPrimitive(PrimitiveKind.UNIT)))
        module.declareFunction(fn)
        val output = module.render()
        val prologueIndex = output.indexOf("declare i32 @printf")
        val functionIndex = output.indexOf("define void @foo")
        assertTrue(prologueIndex >= 0)
        assertTrue(functionIndex > prologueIndex)
    }
}
