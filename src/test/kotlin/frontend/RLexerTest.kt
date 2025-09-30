package frontend

import utils.CompileError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith


class RLexerTest {

    @Test
    fun `handles empty or whitespace-only input`() {
        val lexerEmpty = RLexer("")
        assertEquals(
            emptyList(),
            lexerEmpty.process(),
            "Test failed: Empty input should produce no tokens.\nActual output:\n${lexerEmpty.dumpToString()}"
        )

        val lexerWhitespace = RLexer("   ")
        assertEquals(
            emptyList(),
            lexerWhitespace.process(),
            "Test failed: Whitespace-only input should produce no tokens.\nActual output:\n${lexerWhitespace.dumpToString()}"
        )
    }

    @Test
    fun `tokenizes all keywords`() {
        val allKeywords = Keyword.entries.joinToString(" ") { it.id }
        val lexer = RLexer(allKeywords)
        val expectedTokens = Keyword.entries.map { Token(it, it.id) }

        assertEquals(
            expectedTokens,
            lexer.process(),
            "Test failed: Keyword tokenization mismatch.\nActual output:\n${lexer.dumpToString()}"
        )
    }

    @Test
    fun `tokenizes various valid identifiers`() {
        val input = "x my_variable u____ a123"
        val lexer = RLexer(input)
        val expectedTokens = listOf(
            Token(Identifier, "x"),
            Token(Identifier, "my_variable"),
            Token(Identifier, "u____"),
            Token(Identifier, "a123")
        )
        assertEquals(
            expectedTokens,
            lexer.process(),
            "Test failed: Identifier tokenization mismatch.\nActual output:\n${lexer.dumpToString()}"
        )
    }

    @Test
    fun `tokenizes integer literals`() {
        val input = "123 0 98765"
        val lexer = RLexer(input)
        val expectedTokens = listOf(
            Token(Literal.INTEGER, "123"),
            Token(Literal.INTEGER, "0"),
            Token(Literal.INTEGER, "98765")
        )
        assertEquals(
            expectedTokens,
            lexer.process(),
            "Test failed: Integer literal tokenization mismatch.\nActual output:\n${lexer.dumpToString()}"
        )
    }

    @Test
    fun `tokenizes all punctuation correctly`() {
        val allPunctuation = Punctuation.entries.sortedByDescending { it.id.length }.joinToString(" ") { it.id }
        val lexer = RLexer(allPunctuation)
        val expectedTokens = Punctuation.entries.sortedByDescending { it.id.length }.map { Token(it, it.id) }

        assertEquals(
            expectedTokens,
            lexer.process(),
            "Test failed: Punctuation tokenization mismatch.\nActual output:\n${lexer.dumpToString()}"
        )
    }

    @Test
    fun `prioritizes longest punctuation match`() {
        val input = "<<= >>= ... ..="
        val lexer = RLexer(input)
        val expectedTokens = listOf(
            Token(Punctuation.LESS_LESS_EQUAL, "<<="),
            Token(Punctuation.GREATER_GREATER_EQUAL, ">>="),
            Token(Punctuation.DOT_DOT_DOT, "..."),
            Token(Punctuation.DOT_DOT_EQUAL, "..=")
        )
        assertEquals(
            expectedTokens,
            lexer.process(),
            "Test failed: Did not prioritize the longest punctuation match.\nActual output:\n${lexer.dumpToString()}"
        )
    }

    @Test
    fun `tokenizes string and char literals with corner cases`() {
        val input = """ "" "hello" "\"escaped\"" '' 'c' '\n' """
        print(input)
        val lexer = RLexer(input)
        val expectedTokens = listOf(
            Token(Literal.STRING, ""),
            Token(Literal.STRING, "hello"),
            Token(Literal.STRING, "\\\"escaped\\\""),
            Token(Literal.CHAR, ""),
            Token(Literal.CHAR, "c"),
            Token(Literal.CHAR, "\\n")
        )
        assertEquals(
            expectedTokens,
            lexer.process(),
            "Test failed: String or char literal (including corner cases) tokenization mismatch.\nActual output:\n${lexer.dumpToString()}"
        )
    }

    @Test
    fun `tokenizes raw strings with corner cases`() {
        val input = """ r"" r"hello" r#"hello"# r##"contains "#"## cr"" cr"c-style" """
        val lexer = RLexer(input)
        val expectedTokens = listOf(
            Token(Literal.RAW_STRING, ""),
            Token(Literal.RAW_STRING, "hello"),
            Token(Literal.RAW_STRING, "hello"),
            Token(Literal.RAW_STRING, "contains \"#"),
            Token(Literal.RAW_C_STRING, ""),
            Token(Literal.RAW_C_STRING, "c-style")
        )
        assertEquals(
            expectedTokens,
            lexer.process(),
            "Test failed: Raw string (including corner cases) tokenization mismatch.\nActual output:\n${lexer.dumpToString()}"
        )
    }

    // region Error Handling Tests

    @Test
    fun `fails on unrecognized punctuation`() {
        assertFailsWith<CompileError>(
            message = "Test failed: Lexer should have thrown an exception for unrecognized punctuation.",
            block = { RLexer("let a ` 1").process() }
        )
    }

    @Test
    fun `fails on unterminated string literal`() {
        assertFailsWith<CompileError>(
            message = "Test failed: Lexer should have thrown an exception for an unterminated string.",
            block = { RLexer(""" "hello world """).process() }
        )
    }

    @Test
    fun `fails on unterminated char literal`() {
        assertFailsWith<CompileError>(
            message = "Test failed: Lexer should have thrown an exception for an unterminated char.",
            block = { RLexer("'a").process() }
        )
    }

    @Test
    fun `fails on unterminated raw string literal`() {
        assertFailsWith<CompileError>(
            message = "Test failed: Lexer should have thrown an exception for an unterminated raw string.",
            block = { RLexer(""" r#"hello world "#" """).process() }
        )
    }

    // endregion
}