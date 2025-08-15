package frontend.AST

import frontend.TokenType

sealed interface TypeNode {
    data class BooleanTypeNode(val value: Boolean)

    data class NumericTypeNode(val value: Number, val type: TokenType)

    data class TextualTypeNode(val value: String, val type: TokenType)
}

