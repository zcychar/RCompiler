package frontend.ast

import frontend.semantic.Scope

sealed interface ItemNode {
    fun <T> accept(visitor: ASTVisitor<T>): T
}

data class FunctionItemNode(
    val isConst: Boolean,
    val name: String,
    val selfParam: SelfParamNode?,
    val funParams: List<FunParamNode>,
    val returnType: TypeNode,
    val body: BlockExprNode?,
    var declScope: Scope? = null,
) : ItemNode {
    override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
    data class SelfParamNode(val hasBorrow: Boolean, val hasMut: Boolean, val type: TypeNode?)
    data class FunParamNode(val pattern: PatternNode, val type: TypeNode)
}

data class StructItemNode(val name: String, val fields: List<StructField>) : ItemNode {
    override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
    data class StructField(val name: String, val type: TypeNode)
}

data class EnumItemNode(val name: String, val variants: List<String>) : ItemNode {
    override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}

data class ConstItemNode(val name: String, val type: TypeNode, val expr: ExprNode?) : ItemNode {
    override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}

data class TraitItemNode(val name: String, val items: List<ItemNode>) : ItemNode {
    override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}

data class ImplItemNode(val name: String?, val type: TypeNode, val items: List<ItemNode>,var scope : Scope?) : ItemNode {
    override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}
