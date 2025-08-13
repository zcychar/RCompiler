package frontend

import utils.*



class RLexer(private val input: String) {
    private val tokens: MutableList<Token> = mutableListOf()
    private var position = 0

    fun process(): MutableList<Token> {
        skipWhitespace()
        while (!isEnd()) {
            nextToken()
            //println("now process ${tokens.last()},now sequence: ${input.substring(position)}")
            skipWhitespace()
        }
        return tokens
    }

    fun dumpToString(): String {
        return tokens.joinToString("\n") { it.toString() }
    }

    private fun nextToken() {
        when (peek()) {
            'c' -> {
                when (peek(1)) {
                    '\"' -> cString()
                    'r' -> {
                        when (peek(2)) {
                            '\"', '#' -> rawCString()
                        }
                    }

                    else -> identifierOrKeyword()
                }
            }

            'r' -> {
                when (peek(1)) {
                    '\"', '#' -> rawString()
                    else -> identifierOrKeyword()
                }
            }

            '\"' -> string()
            '\'' -> char()
            in '0'..'9' -> number()
            in 'a'..'z', in 'A'..'Z', '_' -> identifierOrKeyword()
            else -> punctuation()
        }

    }

//    private fun advance(): Char {
//        return input[position++]
//    }

    private fun peek(offset: Int = 0): Char {
        if (position + offset >= input.length) return '\u0000'
        return input[position + offset]
    }

    //only used between words
    private fun skipWhitespace() {
        while (!isEnd() && input[position] == ' ') position++
    }

    private fun isEnd(): Boolean {
        return position >= input.length
    }

    private fun cString() {
        ++position
        literal('\"', Literal.C_STRING)
    }

    private fun rawCString() {
        position += 2
        rawLiteral(Literal.RAW_C_STRING)
    }

    private fun rawString() {
        ++position
        rawLiteral(Literal.RAW_STRING)
    }

    private fun string() = literal('\"', Literal.STRING)

    private fun char() = literal('\'', Literal.CHAR)

    private fun literal(id: Char, type: TokenType) {
        var fi = position + 1
        while (fi < input.length) {
            when (input[fi]) {
                '\\' -> {
                    fi++
                    continue
                }

                id -> {
                    val str = if (fi == position + 1) "" else input.substring(position + 1..fi - 1)
                    position = fi + 1
                    tokens.add(Token(type, str))
                    return
                }
            }
            fi++
        }
        throw CompileError("Lexer:encounter no-end char/string literal ${input.substring(position)}")
    }

    private fun rawLiteral(type: TokenType) {
        var prefix_end=position
        while(prefix_end<input.length&&input[prefix_end]=='#')prefix_end++
        if(input[prefix_end]!='\"')throw CompileError("Lexer:missing quotes in raw_string literal")
        val prefix_length=prefix_end-position
        val suffix='\"'+"#".repeat(prefix_length)
        val suffix_begin=input.indexOf(suffix,prefix_end+1)
        if(suffix_begin==-1){
            throw CompileError("Lexer:encounter no-end raw_string literal ${input.substring(position)}")
        }else{
            val str= if(suffix_begin==prefix_end+1) "" else input.substring(prefix_end+1..suffix_begin-1)
            tokens.add(Token(type,str))
            position=suffix_begin+suffix.length
        }
    }

    private fun identifierOrKeyword() {
        var fi = position
        while (fi < input.length && input[fi].isWord()) fi++
        val str = input.substring(position until fi)
        position = fi
        Keyword.fromId(str)?.let {
            tokens.add(Token(it, str))
            return
        }
        tokens.add(Token(Identifier, str))
    }

    private fun number() {
        var fi = position
        while (fi < input.length && input[fi].isWord()) fi++
        val str = input.substring(position until fi)
        position = fi
        tokens.add(Token(Literal.INTEGER, str))
    }

    private fun punctuation() {
        for (i in 3 downTo 1) {
            if (position + i > input.length) continue
            val str = input.substring(position until position + i)
            Punctuation.fromId(str)?.let {
                tokens.add(Token(it, str))
                position += i
                return
            }
        }
        throw CompileError("Lexer:encounter unrecognized punctuation ${span()}")
    }

//    private fun wordspan(): String {
//        var fi = position
//        while (fi < input.length && input[fi].isWord()) fi++
//        return input.substring(position until fi)
//    }

    private fun span(): String {
        var fi = position
        while (fi < input.length && input[fi] != ' ') fi++
        return input.substring(position until fi)
    }
}