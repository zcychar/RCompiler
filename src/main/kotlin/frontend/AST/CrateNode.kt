package frontend.AST

data class CrateNode(val items: List<ItemNode>) {
    fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}