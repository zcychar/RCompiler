package frontend.ast

import frontend.semantic.Scope

data class CrateNode(val items: List<ItemNode>) {
    fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
    var scope: Scope? = null
}