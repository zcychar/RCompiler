package frontend

import frontend.AST.BlockExprNode
import frontend.AST.CrateNode
import frontend.AST.FunctionNode
import frontend.AST.ItemNode
import frontend.AST.ParamNode
import frontend.AST.TypeNode
import frontend.AST.UnitTypeNode
import utils.CompileError

class RParser(val input: MutableList<Token>) {
    private var position = 0


    private fun peek(offset: Int): Token? {
        if (position + offset - 1 < input.size) return input[position + offset - 1]
        return null
    }

    private fun consume() {
        if (position < input.size) position++
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

    private fun parseItem(): ItemNode {
        val currentToken = peek(1)
        val nextToken = peek(2)
        if (currentToken?.type == Keyword.CONST && nextToken?.type == Keyword.FN) return parseFunction()
        return when (currentToken?.type) {
            Keyword.FN -> parseFunction()
//            Keyword.STRUCT -> parseStruct()
//            Keyword.ENUM -> parseEnum()
//            Keyword.CONST -> parseConstantItem()
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
        expectAndConsume(Punctuation.LEFT_BRACKET)
        var params : List<ParamNode>? = null
        if (peek(1)?.type != Punctuation.RIGHT_BRACKET) {
            params = parseParams()
        }
        expectAndConsume(Punctuation.RIGHT_BRACKET)
        var returnType : TypeNode = UnitTypeNode
        if (peek(1)?.type == Punctuation.RIGHT_ARROW) {
            consume()
            returnType = parseType()
            consume()
        }
        var blockExpr : BlockExprNode? = null
//        if (peek(1)?.type != Punctuation.SEMICOLON) {
//            blockExpr = parseBlockExpr()
//        } else consume()
        return FunctionNode(isConst, id, params, returnType, blockExpr)
    }


    private fun parseParams(): List<ParamNode> {

        return emptyList()
    }


//    private fun parseBlockExpr(): BlockExprNode {
//
//    }

    private fun parseType() : TypeNode{

        return UnitTypeNode
    }
}

