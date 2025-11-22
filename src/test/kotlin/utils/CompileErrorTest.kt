package utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CompileErrorTest {
    @Test
    fun `formats stage and message consistently`() {
        val error = assertFailsWith<CompileError> {
            CompileError.fail(" Parser ", "Unexpected token.")
        }
        assertEquals("Parser: unexpected token", error.message)
    }

    @Test
    fun `falls back to default stage when missing`() {
        val error = assertFailsWith<CompileError> {
            CompileError.fail("   ", "already lowercase.")
        }
        assertEquals("Error: already lowercase", error.message)
    }
}
