package frontend.AST

sealed interface ItemNode

data class FunctionItemNode(
    val isConst: Boolean,
    val name: String,
    val selfParam: SelfParamNode?,
    val funParams: List<FunParamNode>,
    val returnType: TypeNode?,
    val body: BlockExprNode?
) : ItemNode {
    data class SelfParamNode(val hasBorrow: Boolean, val hasMut: Boolean, val type: TypeNode?)
    data class FunParamNode(val pattern: PatternNode, val type: TypeNode)
}

data class StructItemNode(val name: String, val fields: List<StructField>?) : ItemNode {
    data class StructField(val name: String, val type: TypeNode)
}

data class EnumItemNode(val name: String, val variants: List<String>) : ItemNode

data class ConstItemNode(val id: String, val type: TypeNode, val expr: ExprNode?) : ItemNode

data class TraitItemNode(val id: String, val items: List<ItemNode>) : ItemNode

data class ImplItemNode(val id: String?, val type: TypeNode, val items: List<ItemNode>) : ItemNode





