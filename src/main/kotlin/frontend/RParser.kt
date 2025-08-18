package frontend

import frontend.AST.*
import frontend.AST.MatchExprNode.MatchArmNode
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

    private fun tryConsume(type: TokenType): Boolean {
        if (peek(1)?.type == type) {
            consume()
            return true
        } else return false
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
        if (currentToken?.type == Keyword.CONST && nextToken?.type == Keyword.FN) return parseFunctionItem()
        return when (currentToken?.type) {
            Keyword.FN -> parseFunctionItem()
            Keyword.STRUCT -> parseStructItem()
            Keyword.ENUM -> parseEnumItem()
            Keyword.CONST -> parseConstItem()
            Keyword.TRAIT -> parseTraitItem()
            Keyword.IMPL -> parseImplItem()
            else -> throw CompileError("Parser:Encounter invalid item begin with :${input[position]}")
        }
    }

    private fun parseFunctionItem(): FunctionItemNode {
        val isConst = if (peek(1)?.type == Keyword.CONST) {
            consume()
            true
        } else false
        expectAndConsume(Keyword.FN)
        val id = expectAndConsume(Identifier)
        expectAndConsume(Punctuation.LEFT_PAREN)
        val selfParam = when (peek(1)?.type) {
            Punctuation.AMPERSAND, Keyword.MUT, Keyword.SELF -> {
                val hasBorrow = tryConsume(Punctuation.AMPERSAND)
                val hasMut = tryConsume(Keyword.MUT)
                expectAndConsume(Keyword.SELF)
                if (peek(1)?.type == Punctuation.COLON) {
                    if (hasBorrow) {
                        throw CompileError("Parser:encounter ambitious self-param")
                    }
                    consume()
                    val type = parseType()
                    FunctionItemNode.SelfParamNode(hasBorrow, hasMut, type)
                } else FunctionItemNode.SelfParamNode(hasBorrow, hasMut, null)
            }

            else -> null
        }
        if (selfParam != null) tryConsume(Punctuation.COMMA)
        val params = mutableListOf<FunctionItemNode.FunParamNode>()
        while (peek(1)?.type != Punctuation.RIGHT_PAREN) {
            val pattern = parsePattern()
            expectAndConsume(Punctuation.COLON)
            val type = parseType()
            params.add(FunctionItemNode.FunParamNode(pattern, type))
            tryConsume(Punctuation.COMMA)
        }
        expectAndConsume(Punctuation.RIGHT_PAREN)
        val returnType = if (tryConsume(Punctuation.RIGHT_ARROW)) parseType() else null

        val blockExpr = if (!tryConsume(Punctuation.SEMICOLON)) parseBlockExpr() else null
        return FunctionItemNode(isConst, id, selfParam, params, returnType, blockExpr)
    }

    private fun parseStructItem(): StructItemNode {
        expectAndConsume(Keyword.STRUCT)
        val id = expectAndConsume(Identifier)
        val fields: List<StructItemNode.StructField> = when (peek(1)?.type) {
            Punctuation.LEFT_BRACE -> {
                val tmp_field = mutableListOf<StructItemNode.StructField>()
                while (peek(1)?.type != Punctuation.RIGHT_BRACE) {
                    tmp_field.add(run {
                        val id = expectAndConsume(Identifier)
                        expectAndConsume(Punctuation.COLON)
                        val type = parseType()
                        StructItemNode.StructField(id, type)
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
        return StructItemNode(id, fields)
    }

    private fun parseEnumItem(): EnumItemNode {
        expectAndConsume(Keyword.ENUM)
        val id = expectAndConsume(Identifier)
        expectAndConsume(Punctuation.LEFT_BRACE)
        val variants = mutableListOf<String>()
        while (peek(1)?.type != Punctuation.RIGHT_BRACE) {
            variants.add(expectAndConsume(Identifier))
            if (peek(1)?.type == Punctuation.COMMA) consume()
        }
        expectAndConsume(Punctuation.RIGHT_BRACE)
        return EnumItemNode(id, variants)
    }


    private fun parseConstItem(): ConstItemNode {
        expectAndConsume(Keyword.CONST)
        val id = expectAndConsume(Identifier)
        expectAndConsume(Punctuation.COLON)
        val type = parseType()
        if (peek(1)?.type == Punctuation.EQUAL) {
            consume()
            val expr = parseExpr()
            expectAndConsume(Punctuation.SEMICOLON)
            return ConstItemNode(id, type, expr)
        } else {
            expectAndConsume(Punctuation.SEMICOLON)
            return ConstItemNode(id, type, null)
        }
    }

    private fun parseAssociatedItem(): ItemNode = when (peek(1)?.type) {
        Keyword.CONST -> parseConstItem()
        Keyword.FN -> parseFunctionItem()
        else -> throw CompileError("Parser:Expect associated item, met ${peek(1)}")
    }

    private fun parseTraitItem(): TraitItemNode {
        expectAndConsume(Keyword.TRAIT)
        val id = expectAndConsume(Identifier)
        expectAndConsume(Punctuation.LEFT_BRACE)
        val items = mutableListOf<ItemNode>()
        while (peek(1)?.type != Punctuation.RIGHT_BRACE) {
            items.add(parseAssociatedItem())
        }
        expectAndConsume(Punctuation.RIGHT_BRACE)
        return TraitItemNode(id, items)
    }

    private fun parseImplItem(): ImplItemNode {
        expectAndConsume(Keyword.IMPL)
        val id = if (peek(1)?.type == Identifier) {
            val tmp = expectAndConsume(Identifier)
            expectAndConsume(Keyword.FOR)
            tmp
        } else null
        val type = parseType()
        expectAndConsume(Punctuation.LEFT_BRACE)
        val items = mutableListOf<ItemNode>()
        while (peek(1)?.type != Punctuation.RIGHT_BRACE) {
            items.add(parseAssociatedItem())
        }
        expectAndConsume(Punctuation.RIGHT_BRACE)
        return ImplItemNode(id, type, items)
    }

    //----------------------ParseExpr------------------------------


    private fun parseExpr(pre: Int = 0): ExprNode {
        var left = when (peek(1)?.type) {
            is Literal -> parseLiteralExpr()
            Identifier -> parseIdentifierExpr()
            Punctuation.LEFT_PAREN -> parseGroupedExpr()
            Punctuation.LEFT_BRACKET -> parseArrayExpr()
            Punctuation.LEFT_BRACE -> parseBlockExpr()
            Keyword.IF -> parseIfExpr()
            Keyword.RETURN -> parseReturnExpr()
            Keyword.BREAK -> parseBreakExpr()
            Keyword.CONTINUE -> parseContinueExpr()
            Keyword.LOOP -> parseLoopExpr()
            Keyword.WHILE -> parseWhileExpr()
            Keyword.MATCH -> parseMatchExpr()
            Keyword.CONST -> {
                if (peek(2)?.type == Punctuation.LEFT_BRACE) parseBlockExpr()
                else throw CompileError("TODO")
            }

            Punctuation.UNDERSCORE -> parseUnderscoreExpr()
            Punctuation.DOT_DOT -> throw CompileError("TODO")
            in unaryOp -> parseUnaryExpr()
            else -> throw CompileError("Parser:Expect expression, met ${peek(1)}")
        }

        while (peek(1)?.type in precedence && (precedence[peek(1)?.type]?.first ?: -1) > pre) {
            left = when (peek(1)?.type) {
                in binaryOp -> parseBinaryExpr(left)
                Punctuation.LEFT_PAREN -> parseCallExpr(left)
                Punctuation.DOT -> {
                    if (peek(2)?.type == Keyword.SELF || peek(2)?.type == Keyword.SELF_UPPER || peek(3)?.type == Punctuation.LEFT_PAREN) {
                        parseMethodCallExpr(left)
                    } else parseCallExpr(left)
                }

                else -> throw CompileError("Parser:Expect right-side pattern, met ${peek(1)}")
            }
        }
        return left
    }

    private fun parseIdentifierExpr(): IdentifierExprNode = IdentifierExprNode(expectAndConsume(Identifier))

    private fun parseUnderscoreExpr(): UnderscoreExprNode {
        expectAndConsume(Punctuation.UNDERSCORE)
        return UnderscoreExprNode
    }

    private fun parseLoopExpr(): LoopExprNode {
        expectAndConsume(Keyword.LOOP)
        return LoopExprNode(parseBlockExpr())
    }

    private fun parseWhileExpr(): WhileExprNode {
        expectAndConsume(Keyword.WHILE)
        return WhileExprNode(parseConds(), parseBlockExpr())
    }


    private fun parseArrayExpr(): ArrayExprNode {
        expectAndConsume(Punctuation.LEFT_BRACKET)
        if (!tryConsume(Punctuation.RIGHT_BRACKET)) {
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
                        tryConsume(Punctuation.COMMA)
                    }
                    expectAndConsume(Punctuation.RIGHT_BRACKET)
                    return ArrayExprNode(elements, null, null)
                }

                else -> throw CompileError("Parser:Expect right-side of array-expression, met ${peek(1)}")
            }
        } else return ArrayExprNode(listOf<ExprNode>(), null, null)

    }

    private fun parseFieldAccessExpr(left: ExprNode): FieldAccessExprNode {
        expectAndConsume(Punctuation.DOT)
        val id = expectAndConsume(Identifier)
        return FieldAccessExprNode(left, id)
    }

    private fun parseMethodCallExpr(left: ExprNode): MethodCallExprNode {
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
        while (!tryConsume(Punctuation.RIGHT_PAREN)) {
            seg.add(parseExpr())
            tryConsume(Punctuation.COMMA)
        }
        return MethodCallExprNode(left, pathSeg, seg)
    }


    private fun parseLiteralExpr(): ExprNode {
        val token = peek(1)
        if (token?.type is Literal) {
            return LiteralExprNode(token.value, token.type)
        } else if (token?.type == Keyword.TRUE || token?.type == Keyword.FALSE) {
            return LiteralExprNode(null, token.type)
        } else {
            throw CompileError("Parser:Expect literal-expression, met $token")
        }
    }

    private fun parseGroupedExpr(): ExprNode {
        expectAndConsume(Punctuation.LEFT_PAREN)
        val expr = parseExpr()
        expectAndConsume(Punctuation.RIGHT_PAREN)
        return expr
    }

    private fun parseCallExpr(left: ExprNode): CallExprNode {
        expectAndConsume(Punctuation.LEFT_PAREN)
        val params = mutableListOf<ExprNode>()
        while (!tryConsume(Punctuation.RIGHT_PAREN)) {
            params.add(parseExpr())
            tryConsume(Punctuation.COMMA)
        }
        return CallExprNode(left, params)
    }

    private fun parsePathExpr(): PathExprNode {
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
        val seg2 = if (tryConsume(Punctuation.COLON_COLON)) exe() else null
        return PathExprNode(seg1, seg2)
    }

    private fun parseBlockExpr(): BlockExprNode {
        val hasConst = tryConsume(Keyword.CONST)
        expectAndConsume(Punctuation.LEFT_BRACE)
        val stmts = mutableListOf<StmtNode>()
        while (!tryConsume(Punctuation.RIGHT_BRACE)) {
            stmts.add(parseStmt())
        }
        return BlockExprNode(hasConst, stmts)
    }

    private fun parseIfExpr(): IfExprNode {
        expectAndConsume(Keyword.IF)
        val conds = parseConds()
        val block = parseBlockExpr()
        return if (tryConsume(Keyword.ELSE)) {
            when (peek(1)?.type) {
                Punctuation.LEFT_BRACE -> IfExprNode(conds, block, parseBlockExpr(), null)
                Keyword.IF -> IfExprNode(conds, block, null, parseIfExpr())
                else -> throw CompileError("Parser:Expect else-expression in if-expression, met ${peek(1)}")
            }
        } else IfExprNode(conds, block, null, null)
    }

    private fun parseMatchExpr(): MatchExprNode {
        expectAndConsume(Keyword.MATCH)
        val scru = parseExpr()
        expectAndConsume(Punctuation.LEFT_BRACE)
        val arms = mutableListOf<Pair<MatchArmNode, ExprNode>>()
        while (!tryConsume(Punctuation.RIGHT_BRACE)) {
            val pattern = parsePattern()
            val guard = if (tryConsume(Keyword.IF)) parseExpr() else null
            expectAndConsume(Punctuation.FAT_ARROW)
            val expr = parseExpr()
            arms.add(Pair(MatchExprNode.MatchArmNode(pattern, guard), expr))
            if (peek(1)?.type != Punctuation.COMMA && peek(1)?.type != Punctuation.RIGHT_BRACE && expr !is ExprWOBlock) {
                throw CompileError("Parser:Expect comma in not-end match arm, met ${peek(1)}")
            }
            tryConsume(Punctuation.COMMA)
        }
        return MatchExprNode(scru, arms)
    }

    private fun parseReturnExpr(): ReturnExprNode {
        expectAndConsume(Keyword.RETURN)
        val value = if (peek(1)?.type != Punctuation.SEMICOLON) parseExpr() else null
        return ReturnExprNode(value)
    }

    private fun parseBreakExpr(): BreakExprNode {
        expectAndConsume(Keyword.BREAK)
        val value = if (peek(1)?.type != Punctuation.SEMICOLON) parseExpr() else null
        return BreakExprNode(value)
    }

    private fun parseContinueExpr(): ContinueExprNode {
        expectAndConsume(Keyword.CONTINUE)
        return ContinueExprNode
    }

    private fun parseIndexExpr(left: ExprNode): IndexExprNode {
        expectAndConsume(Punctuation.LEFT_BRACKET)
        val right = parseExpr()
        expectAndConsume(Punctuation.RIGHT_BRACKET)
        return IndexExprNode(left, right)
    }

    private fun parseStructExpr(left: ExprNode): StructExprNode {
        expectAndConsume(Punctuation.LEFT_BRACE)
        val fields = mutableListOf<StructExprNode.StructExprField>()
        while (!tryConsume(Punctuation.RIGHT_BRACE)) {
            val id = expectAndConsume(Identifier)
            val expr = if (tryConsume(Punctuation.COLON)) parseExpr() else null
            fields.add(StructExprNode.StructExprField(id, expr))
            tryConsume(Punctuation.COLON)
        }
        return StructExprNode(left, fields)
    }

    private fun parseUnaryExpr(): ExprNode {
        val op = consume().type
        val hasMut = tryConsume(Keyword.MUT)
        val rhs = parseExpr(150)
        return UnaryExprNode(op, hasMut, rhs)
    }

    private fun parseBinaryExpr(left: ExprNode): ExprNode {
        val op = consume().type
        return when (op) {
            in precedence -> {
                val right = parseExpr(precedence[op]!!.second)
                BinaryExprNode(op, left, right)
            }

            Punctuation.LEFT_BRACKET -> parseIndexExpr(left)
            Punctuation.LEFT_BRACE -> parseStructExpr(left)
            Punctuation.LEFT_PAREN -> parseCallExpr(left)
            Punctuation.DOT -> parseFieldAccessExpr(left)
            else -> throw CompileError("Parser:Expect binary op for expression, met ${peek(1)}")
        }
    }

    private fun parseConds(): List<CondExprNode> {
        val conds = mutableListOf<CondExprNode>()
        while (peek(1)?.type != Punctuation.LEFT_BRACE) {
            conds.add(
                if (tryConsume(Keyword.LET)) {
                    val pattern = parsePattern()
                    expectAndConsume(Punctuation.EQUAL)
                    val expr = parseExpr()
                    CondExprNode(pattern, expr)
                } else CondExprNode(null, parseExpr())
            )
            tryConsume(Punctuation.AND_AND)
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
        val expr = parseLiteralExpr()
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

    private fun parsePathPattern(): PathPatternNode = PathPatternNode(parsePathExpr())


    //---------------------ParseStatement---------------------------
    //TODO:may have ambiguous expr and item
    private fun parseStmt(): StmtNode = when (peek(1)?.type) {
        Punctuation.SEMICOLON -> {
            consume()
            NullStmtNode
        }

        Keyword.FN, Keyword.STRUCT, Keyword.CONST, Keyword.TRAIT, Keyword.IMPL -> parseItemStmt()
        Keyword.LET -> parseLetStmt()
        else -> parseExprStmt()
    }

    private fun parseItemStmt(): ItemStmtNode = ItemStmtNode(parseItem())

    private fun parseLetStmt(): LetStmtNode {
        expectAndConsume(Keyword.LET)
        val pattern = parsePattern()
        val type = if (tryConsume(Punctuation.COLON)) parseType() else null
        val expr = if (tryConsume(Punctuation.EQUAL)) parseExpr() else null
        expectAndConsume(Punctuation.SEMICOLON)
        return LetStmtNode(pattern, type, expr)
    }

    private fun parseExprStmt(): ExprStmtNode {
        val expr = parseExpr()
        if (expr is ExprWOBlock) expectAndConsume(Punctuation.SEMICOLON) else tryConsume(Punctuation.SEMICOLON)
        return ExprStmtNode(expr)
    }

    //-----------------------ParseType------------------------------
    private fun parseType(): TypeNode = when (peek(1)?.type) {
        Punctuation.UNDERSCORE -> parseInferredType()
        Punctuation.AMPERSAND -> parseRefType()
        Punctuation.LEFT_BRACKET -> parseArrayOrSliceType()
        Identifier, Keyword.SELF, Keyword.SELF_UPPER -> parseTypePath()
        else -> throw CompileError("Parser:Expect type, met ${peek(1)}")
    }

    private fun parseTypePath(): TypePathNode = when (peek(1)?.type) {
        Identifier -> TypePathNode(consume().value, null)
        Keyword.SELF_UPPER, Keyword.SELF -> TypePathNode(null, consume().type)
        else -> throw CompileError("Parser:expect path-segment, met ${peek(1)}")
    }

    private fun parseRefType(): RefTypeNode {
        expectAndConsume(Punctuation.AMPERSAND)
        return RefTypeNode(tryConsume(Keyword.MUT), parseType())
    }

    private fun parseArrayOrSliceType(): TypeNode {
        expectAndConsume(Punctuation.LEFT_BRACKET)
        val type = parseType()
        if (tryConsume(Punctuation.SEMICOLON)) {
            val expr = parseExpr()
            expectAndConsume(Punctuation.RIGHT_BRACKET)
            return ArrayTypeNode(type, expr)
        } else {
            expectAndConsume(Punctuation.RIGHT_BRACKET)
            return SliceTypeNode(type)
        }
    }


    private fun parseInferredType(): InferredTypeNode {
        expectAndConsume(Punctuation.UNDERSCORE)
        return InferredTypeNode
    }


    //----------------------support---------------------------------

}


