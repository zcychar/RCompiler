package frontend.AST

import frontend.TokenType

sealed interface TypeNode

data class BooleanTypeNode(val value: Boolean) : TypeNode

data class NumericTypeNode(val value: Number, val type: TokenType) : TypeNode

data class TextualTypeNode(val value: String, val type: TokenType) : TypeNode

data object UnitTypeNode : TypeNode