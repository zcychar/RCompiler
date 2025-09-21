package frontend.AST

import frontend.Identifier

sealed interface PatternNode {
    fun <T> accept(visitor: ASTVisitor<T>): T
}

data class IdentifierPatternNode(
    val hasRef: Boolean,
    val hasMut: Boolean,
    val id: String,
    val subPattern: PatternNode?
) :
    PatternNode {
    override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}

data class RefPatternNode(val isDouble: Boolean, val hasMut: Boolean, val pattern: PatternNode) : PatternNode {
    override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}


data object WildcardPatternNode : PatternNode {
    override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}