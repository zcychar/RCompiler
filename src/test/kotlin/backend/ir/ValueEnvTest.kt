package backend.ir

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ValueEnvTest {
    @Test
    fun `scopes shadow and resolve`() {
        val env = ValueEnv()
        env.enterScope()
        env.bind("a", SsaValue(IrIntConstant(1, IrPrimitive(PrimitiveKind.I32))))
        env.enterScope()
        env.bind("a", SsaValue(IrIntConstant(2, IrPrimitive(PrimitiveKind.I32))))
        val resolved = env.resolve("a") as SsaValue
        assertEquals(2, (resolved.value as IrIntConstant).value)
        env.leaveScope()
        val outer = env.resolve("a") as SsaValue
        assertEquals(1, (outer.value as IrIntConstant).value)
    }

    @Test
    fun `loop targets push and pop`() {
        val env = ValueEnv()
        env.pushFunction(IrPrimitive(PrimitiveKind.UNIT))
        env.pushLoop("break1", "cont1")
        assertEquals("break1", env.currentBreakTarget())
        env.pushLoop("break2", "cont2")
        assertEquals("break2", env.currentBreakTarget())
        env.popLoop()
        assertEquals("break1", env.currentBreakTarget())
        env.popLoop()
        assertNull(env.currentBreakTarget())
    }
}
