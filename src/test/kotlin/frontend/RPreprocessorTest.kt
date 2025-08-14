package frontend

import kotlin.test.Test
import kotlin.test.assertEquals

class RPreprocessorTest {

    @Test
    fun `removes line comments`() {
        val input = "fn main() { // This is a comment\n let a = 1; }"
        val preprocessor = RPreprocessor(input)
        val expected = "fn main() { let a = 1; }"
        assertEquals(
            expected,
            preprocessor.process(),
            "Test failed: Line comment was not removed correctly.\nInput: $input"
        )
    }

    @Test
    fun `removes block comments`() {
        val input = "fn main() { /* This is a block comment */ let a = 1; }"
        val preprocessor = RPreprocessor(input)
        val expected = "fn main() { let a = 1; }"
        assertEquals(
            expected,
            preprocessor.process(),
            "Test failed: Block comment was not removed correctly.\nInput: $input"
        )
    }

    @Test
    fun `handles nested block comments`() {
        val input = "fn main() { /* outer /* inner */ outer */ let a = 1; }"
        val preprocessor = RPreprocessor(input)
        val expected = "fn main() { let a = 1; }"
        assertEquals(
            expected,
            preprocessor.process(),
            "Test failed: Nested block comments were not handled correctly.\nInput: $input"
        )
    }

    @Test
    fun `preserves raw strings while removing comments`() {
        val input = """
            // some comment
            let d = r#"raw_string"#; /* another comment */
        """.trimIndent()
        val preprocessor = RPreprocessor(input)
        val expected = "let d = r#\"raw_string\"#; " // Note the trailing space from the newline

        assertEquals(
            expected,
            preprocessor.process(),
            "Test failed: Raw string was not preserved correctly during comment removal.\nInput: $input\nExpected: '$expected'\nActual: '${preprocessor.dumpToString()}'"
        )
    }
}