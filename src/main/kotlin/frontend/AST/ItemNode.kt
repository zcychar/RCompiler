package frontend.AST

import frontend.TokenType

sealed interface ItemNode

data class FunctionNode(
    val isConst: Boolean,
    val name: String,
    val params: List<ParamNode>,
    val returnType: TypeNode,
    val body: BlockExprNode?
) : ItemNode

data class StructNode(val name: String, val fields: List<StructField>?) : ItemNode {
    data class StructField(val name: String, val type: TypeNode)
}

data class EnumNode(val name: String, val variants: List<String>): ItemNode

data class ParamNode(val pattern: PatternNode, val type: TypeNode)



