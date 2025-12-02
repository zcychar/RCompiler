package backend.ir

import kotlin.test.Test
import kotlin.test.assertEquals

class CodegenContextTest {
    @Test
    fun `internString deduplicates globals`() {
        val context = CodegenContext()
        val ref1 = context.internString("hello")
        val ref2 = context.internString("hello")
        assertEquals(ref1, ref2)
        assertEquals(1, context.module.allGlobals().size)
    }
}
