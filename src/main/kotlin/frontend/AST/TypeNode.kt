package frontend.AST

import frontend.TokenType

sealed interface TypeNode {
    fun <T> accept(visitor: ASTVisitor<T>): T
}

data class TypePathNode(val id: String?, val type: TokenType?) : TypeNode {
    override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}

data class RefTypeNode(val hasMut: Boolean, val type: TypeNode) : TypeNode {
    override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}

data class ArrayTypeNode(val type: TypeNode, val expr: ExprNode) : TypeNode {
    override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}

data class SliceTypeNode(val type: TypeNode) : TypeNode {
    override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}

data object UnitTypeNode : TypeNode {
    override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}