package frontend

import frontend.AST.*
import frontend.precedence
import frontend.unaryOp
import utils.CompileError


class RParser(val input: MutableList<Token>) {
    private var position = 0


    private fun peek(offset: Int): Token? {
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


    private fun parseExpr(pre: Int = 0): ExprNode {
        var left = when (peek(1)?.type) {
            is Literal -> parseLiteral()
            Identifier -> parseIdentifier()
            Punctuation.LEFT_PAREN -> parseGrouped()
            Punctuation.LEFT_BRACKET -> parseArray()
            Punctuation.LEFT_BRACE -> parseBlock()
            Keyword.IF -> parseIf()
            Keyword.RETURN -> parseReturn()
            Keyword.BREAK -> parseBreak()
            Keyword.CONTINUE -> parseContinue()
            Keyword.LOOP -> parseLoop()
            Keyword.WHILE -> parseWhile()
            in unaryOp -> parseUnary()
            else -> throw CompileError("Parser:Expect expression, met ${peek(1)}")
        }

        while (peek(1)?.type in precedence && (precedence[peek(1)?.type]?.first ?: -1) > pre) {
            left = when (peek(1)?.type) {
                in binaryOp -> parseBinary(left)
                Punctuation.LEFT_PAREN -> parseCall(left)
                Punctuation.DOT -> {
                    if (peek(2)?.type == Keyword.SELF || peek(2)?.type == Keyword.SELF_UPPER || peek(3)?.type == Punctuation.LEFT_PAREN) {
                        parseMethodCall(left)
                    } else parseCall(left)
                }

                else -> throw CompileError("Parser:Expect right-side pattern, met ${peek(1)}")
            }
        }
        return left
    }

    private fun parseIdentifier(): IdentifierExprNode = IdentifierExprNode(expectAndConsume(Identifier))

    private fun parseUnderscore(): UnderscoreExprNode {
        expectAndConsume(Punctuation.UNDERSCORE)
        return UnderscoreExprNode
    }

    private fun parseLoop(): LoopExprNode {
        expectAndConsume(Keyword.LOOP)
        return LoopExprNode(parseBlock())
    }

    private fun parseWhile(): WhileExprNode {
        expectAndConsume(Keyword.WHILE)
        return WhileExprNode(parseConds(), parseBlock())
    }


    private fun parseArray(): ArrayExprNode {
        expectAndConsume(Punctuation.LEFT_BRACKET)
        val elements = mutableListOf<ExprNode>()
        if (peek(1)?.type != Punctuation.RIGHT_BRACKET) {
            val first = parseExpr()
            when (peek(1)?.type) {
                Punctuation.SEMICOLON -> {
                    consume()
                    val second = parseExpr()
                    expectAndConsume(Punctuation.RIGHT_BRACKET)
                    return ArrayExprNode(null, first, second)
                }

                Punctuation.COMMA -> {
                    consume()
                    val elements = mutableListOf<ExprNode>()
                    elements.add(first)
                    while (peek(1)?.type != Punctuation.RIGHT_BRACKET) {
                        elements.add(parseExpr())
                        if (peek(1)?.type == Punctuation.COMMA) consume()
                    }
                    expectAndConsume(Punctuation.RIGHT_BRACKET)
                    return ArrayExprNode(elements, null, null)
                }

                else -> throw CompileError("Parser:Expect right-side of array-expression, met ${peek(1)}")
            }
        } else {
            consume()
            return ArrayExprNode(listOf<ExprNode>(), null, null)
        }
    }

    private fun parseFieldAccess(left: ExprNode): FieldAccessExprNode {
        expectAndConsume(Punctuation.DOT)
        val id = expectAndConsume(Identifier)
        return FieldAccessExprNode(left, id)
    }

    private fun parseMethodCall(left: ExprNode): MethodCallExprNode {
        expectAndConsume(Punctuation.DOT)
        val pathSeg = when (peek(1)?.type) {
            Identifier -> {
                PathExprNode.PathExprSeg(consume().value, null)
            }

            Keyword.SELF_UPPER, Keyword.SELF -> {
                PathExprNode.PathExprSeg(null, consume().type)
            }

            else -> throw CompileError("Parser:expect path-segment, met ${peek(1)}")
        }
        expectAndConsume(Punctuation.LEFT_PAREN)
        val seg = mutableListOf<ExprNode>()
        while (peek(1)?.type != Punctuation.RIGHT_PAREN) {
            seg.add(parseExpr())
            if (peek(1)?.type == Punctuation.COMMA) consume()
        }
        expectAndConsume(Punctuation.RIGHT_PAREN)
        return MethodCallExprNode(left, pathSeg, seg)
    }


    private fun parseLiteral(): ExprNode {
        val token = peek(1)
        if (token?.type is Literal) {
            return LiteralExprNode(token.value, token.type)
        } else if (token?.type == Keyword.TRUE || token?.type == Keyword.FALSE) {
            return LiteralExprNode(null, token.type)
        } else {
            throw CompileError("Parser:Expect literal-expression, met $token")
        }
    }

    private fun parseGrouped(): ExprNode {
        expectAndConsume(Punctuation.LEFT_PAREN)
        val expr = parseExpr()
        expectAndConsume(Punctuation.RIGHT_PAREN)
        return expr
    }

    private fun parseCall(left: ExprNode): CallExprNode {
        expectAndConsume(Punctuation.LEFT_PAREN)
        val params = mutableListOf<ExprNode>()
        while (peek(1)?.type != Punctuation.RIGHT_PAREN) {
            params.add(parseExpr())
            if (peek(1)?.type == Punctuation.COMMA) consume()
        }
        expectAndConsume(Punctuation.RIGHT_PAREN)
        return CallExprNode(left, params)
    }

    private fun parsePath(): PathExprNode {
        val exe: () -> PathExprNode.PathExprSeg = {
            when (peek(1)?.type) {
                Identifier -> {
                    PathExprNode.PathExprSeg(consume().value, null)
                }

                Keyword.SELF_UPPER, Keyword.SELF -> {
                    PathExprNode.PathExprSeg(null, consume().type)
                }

                else -> throw CompileError("Parser:expect path-segment, met ${peek(1)}")
            }
        }
        val seg1 = exe()
        val seg2 = if (peek(1)?.type == Punctuation.COLON_COLON) {
            consume()
            exe()
        } else null
        return PathExprNode(seg1, seg2)
    }

    private fun parseBlock(): BlockExprNode {
        throw CompileError("TODO")
    }

    private fun parseIf(): IfExprNode {
        expectAndConsume(Keyword.IF)
        val conds = parseConds()
        val block = parseBlock()
        if (peek(1)?.type == Keyword.ELSE) {
            consume()
            return when (peek(1)?.type) {
                Punctuation.LEFT_BRACE -> IfExprNode(conds, block, parseBlock(), null)
                Keyword.IF -> IfExprNode(conds, block, null, parseIf())
                else -> throw CompileError("Parser:Expect else-expression in if-expression, met ${peek(1)}")
            }
        } else return IfExprNode(conds, block, null, null)
    }

    private fun parseMatch(): MatchExprNode {
        expectAndConsume(Keyword.MATCH)
        val scru = parseExpr()
        expectAndConsume(Punctuation.LEFT_BRACE)
        expectAndConsume(Punctuation.RIGHT_BRACE)
        throw CompileError("TODO")
    }

    private fun parseReturn(): ReturnExprNode {
        expectAndConsume(Keyword.RETURN)
        val value = if (peek(1)?.type != Punctuation.SEMICOLON) parseExpr() else null
        return ReturnExprNode(value)
    }

    private fun parseBreak(): BreakExprNode {
        expectAndConsume(Keyword.BREAK)
        val value = if (peek(1)?.type != Punctuation.SEMICOLON) parseExpr() else null
        return BreakExprNode(value)
    }

    private fun parseContinue(): ContinueExprNode {
        expectAndConsume(Keyword.CONTINUE)
        return ContinueExprNode
    }

    private fun parseUnary(): ExprNode {
        val op = consume().type
        val hasMut = if (peek(1)?.type == Keyword.MUT) {
            consume()
            true
        } else false
        val rhs = parseExpr(150)
        return UnaryExprNode(op, hasMut, rhs)
    }

    private fun parseBinary(left: ExprNode): ExprNode {
        val op = consume().type
        val pre = precedence.get(op)?.second ?: throw CompileError("Parser:expect binary token, met $op")
        val right = parseExpr(pre)
        return BinaryExprNode(op, left, right)
    }

    private fun parseConds(): List<CondExprNode> {
        val conds = mutableListOf<CondExprNode>()
        while (peek(1)?.type != Punctuation.LEFT_BRACE) {
            conds.add(
                when (peek(1)?.type) {
                    Keyword.LET -> {
                        consume()
                        val pattern = parsePattern()
                        expectAndConsume(Punctuation.EQUAL)
                        val expr = parseExpr()
                        CondExprNode(pattern, expr)
                    }

                    else -> CondExprNode(null, parseExpr())
                }
            )
            if (peek(1)?.type == Punctuation.AND_AND) consume()
        }
        return conds
    }


    //---------------------ParsePattern-----------------------------
    private fun parsePattern(): PatternNode {
        return when (peek(1)?.type) {
            Punctuation.UNDERSCORE -> parseWildcardPattern()
            Punctuation.AMPERSAND, Punctuation.AND_AND -> parseRefPattern()
            Punctuation.MINUS, is Literal -> parseLiteralPattern()
            Keyword.SELF_UPPER, Keyword.SELF -> parsePathPattern()
            Keyword.REF, Keyword.MUT -> parseIdentifierPattern()
            Identifier -> {
                if (peek(2)?.type == Punctuation.AT) {
                    parseIdentifierPattern()
                } else parsePathPattern()
            }

            else -> throw CompileError("Parser:Expect pattern, met ${peek(1)}")
        }
    }

    private fun parseLiteralPattern(): LiteralPatternNode {
        val hasMinus = if (peek(1)?.type == Punctuation.MINUS) {
            consume()
            true
        } else false
        val expr = parseLiteral()
        return LiteralPatternNode(hasMinus, expr)
    }

    private fun parseIdentifierPattern(): IdentifierPatternNode {
        val hasRef = if (peek(1)?.type == Keyword.REF) {
            consume()
            true
        } else false
        val hasMut = if (peek(1)?.type == Keyword.MUT) {
            consume()
            true
        } else false
        val id = expectAndConsume(Identifier)
        if (peek(1)?.type == Punctuation.AT) {
            val subPattern = parsePattern()
            return IdentifierPatternNode(hasRef, hasMut, id, subPattern)
        } else return IdentifierPatternNode(hasRef, hasMut, id, null)
    }

    private fun parseRefPattern(): RefPatternNode {
        val isDouble = when (peek(1)?.type) {
            Punctuation.AMPERSAND -> false
            Punctuation.AND_AND -> true
            else -> throw CompileError("Parser:Expect ref-pattern, met ${peek(1)}")
        }
        consume()
        val hasMut = if (peek(1)?.type == Keyword.MUT) true else false
        val pattern = parsePattern()
        return RefPatternNode(isDouble, hasMut, pattern)
    }

    private fun parseWildcardPattern(): WildcardPatternNode {
        expectAndConsume(Punctuation.UNDERSCORE)
        return WildcardPatternNode
    }

    private fun parsePathPattern(): PathPatternNode = PathPatternNode(parsePath())


    //-----------------------ParseType------------------------------
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


