package frontend.ast

sealed interface StmtNode {
  fun <T> accept(visitor: ASTVisitor<T>): T
}

data class ItemStmtNode(val item: ItemNode) : StmtNode {
  override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}

data class LetStmtNode(
  val pattern: PatternNode,
  val type: TypeNode?,
  val expr: ExprNode?,
  var realType: frontend.semantic.Type? = null
) : StmtNode {
  override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}

data class ExprStmtNode(val expr: ExprNode, val hasSemiColon: Boolean = false) : StmtNode {
  override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}

data object NullStmtNode : StmtNode {
  override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}