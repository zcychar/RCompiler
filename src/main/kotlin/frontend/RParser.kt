package frontend

import frontend.AST.*
import frontend.unaryOp
import utils.CompileError


class RParser(val input: MutableList<Token>) {
    private var position = 0

    companion object


    private

    fun peek(offset: Int): Token? {
        if (position + offset - 1 < input.size) return input[position + offset - 1]
        return null
    }

    private fun consume(): Token {
        if (position < input.size) return input[position++]
        throw CompileError("Parser:Out_of_size consume requested")
    }

    private fun expectAndConsume(type: TokenType): String {
        val tmp = expect(type)
        consume()
        return tmp
    }

    private fun expect(type: TokenType): String {
        if (position >= input.size) {
            throw CompileError("Parser:Out_of_size expect requested")
        } else if (input[position].type != type) {
            throw CompileError("Parser:Encounter unexpected token ${input[position]}")
        }
        return input[position].value
    }

    private fun eof(): Boolean = position < input.size

    fun process() = parseCrate()

    private fun parseCrate(): CrateNode {
        val items = mutableListOf<ItemNode>()
        while (!eof()) {
            items.add(parseItem())
        }
        return CrateNode(items.toList())
    }


    //----------------------ParseItem------------------------------
    private fun parseItem(): ItemNode {
        val currentToken = peek(1)
        val nextToken = peek(2)
        if (currentToken?.type == Keyword.CONST && nextToken?.type == Keyword.FN) return parseFunction()
        return when (currentToken?.type) {
            Keyword.FN -> parseFunction()
            Keyword.STRUCT -> parseStruct()
            Keyword.ENUM -> parseEnum()
//            Keyword.CONST -> parseConstantItem() //TODO
//            Keyword.TRAIT -> parseTrait()
//            Keyword.IMPL -> parseImpl()
            else -> throw CompileError("Parser:Encounter invalid item begin with :${input[position]}")
        }
    }

    private fun parseFunction(): FunctionNode {
        var isConst = false
        if (peek(1)?.type == Keyword.CONST) {
            isConst = true
            consume()
        }
        expect(Keyword.FN)
        consume()
        val id: String = expectAndConsume(Identifier)
        expectAndConsume(Punctuation.LEFT_PAREN)
        val params = when (peek(1)?.type) {
            Punctuation.RIGHT_PAREN -> listOf()
            else -> parseParams()
        }
        expectAndConsume(Punctuation.RIGHT_PAREN)
        val returnType = when (peek(1)?.type) {
            Punctuation.RIGHT_ARROW -> {
                consume()
                val type = parseType()
                consume()
                type
            }

            else -> UnitTypeNode
        }
        var blockExpr: BlockExprNode? = null
//        if (peek(1)?.type != Punctuation.SEMICOLON) { TODO
//            blockExpr = parseBlockExpr()
//        } else consume()
        return FunctionNode(isConst, id, params, returnType, blockExpr)
    }

    private fun parseStruct(): StructNode {
        expectAndConsume(Keyword.STRUCT)
        val id = expectAndConsume(Identifier)
        val fields: List<StructNode.StructField> = when (peek(1)?.type) {
            Punctuation.LEFT_BRACE -> {
                val tmp_field = mutableListOf<StructNode.StructField>()
                while (peek(1)?.type != Punctuation.RIGHT_BRACE) {
                    tmp_field.add(run {
                        val id = expectAndConsume(Identifier)
                        expectAndConsume(Punctuation.COLON)
                        val type = parseType()
                        StructNode.StructField(id, type)
                    })
                    if (peek(1)?.type == Punctuation.COMMA) consume()
                }
                expectAndConsume(Punctuation.RIGHT_BRACE)
                tmp_field.toList()
            }

            Punctuation.SEMICOLON -> {
                consume()
                listOf()
            }

            else -> throw CompileError("Parser:invalid token for struct field: ${peek(1)}")
        }
        return StructNode(id, fields)
    }

    private fun parseEnum(): EnumNode {
        expectAndConsume(Keyword.ENUM)
        val id = expectAndConsume(Identifier)
        expectAndConsume(Punctuation.LEFT_BRACE)
        val varients = mutableListOf<String>()
        while (peek(1)?.type != Punctuation.RIGHT_BRACE) {
            varients.add(expectAndConsume(Identifier))
            if (peek(1)?.type == Punctuation.COMMA) consume()
        }
        expectAndConsume(Punctuation.RIGHT_BRACE)
        return EnumNode(id, varients)
    }


    private fun parseParams(): List<ParamNode> {
        //TODO
        return emptyList()
    }

    //----------------------ParseExpr------------------------------

    private fun parseExpr(precedence: Int = 0): ExprNode {

    }


    private fun parseLiteral(): ExprNode {

    }

    private fun parseGrouped(): ExprNode {
        expectAndConsume(Punctuation.LEFT_PAREN)
        val expr = parseExpr()
        expectAndConsume(Punctuation.RIGHT_PAREN)
        return expr
    }

    private fun parseCall(left: ExprNode): ExprNode {

    }


    private fun parseUnary(): ExprNode {
        val op = consume().type
        val hasMut = if (peek(1)?.type == Keyword.MUT) {
            consume()
            true
        } else false
        val rhs = parseExpr()
        return UnaryExprNode(op, hasMut, rhs)
    }

    private fun parseBinary(left: ExprNode): ExprNode {

    }


    //    private fun parseBlockExpr(): BlockExprNode {
//
//    }
    private fun parseType(): TypeNode {

        return UnitTypeNode
    }


    //----------------------support---------------------------------
    private val nudRule: (TokenType) -> ExprNode = {
        when {
            it in unaryOp -> parseUnary()
            it == Punctuation.LEFT_PAREN -> parseGrouped()
            else -> throw CompileError("Parser:expect unary expression, encounter type $it")
        }
    }
}


