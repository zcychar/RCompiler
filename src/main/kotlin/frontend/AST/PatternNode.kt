package frontend.AST

import frontend.Identifier

sealed interface PatternNode

data class LiteralPatternNode(val hasMinus: Boolean, val expr: ExprNode) : PatternNode

data class IdentifierPatternNode(
    val hasRef: Boolean,
    val hasMut: Boolean,
    val id: String,
    val subPattern: PatternNode?
) :
    PatternNode

data class RefPatternNode(val isDouble: Boolean,val hasMut: Boolean,val pattern: PatternNode): PatternNode

data class PathPatternNode(val path: PathExprNode): PatternNode
data object WildcardPatternNode : PatternNode