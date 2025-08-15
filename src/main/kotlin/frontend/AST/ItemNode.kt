package frontend.AST

sealed interface ItemNode

data class FunctionNode(
    val isConst: Boolean,
    val name: String,
    val params: List<ParamNode>?,
    val returnType: TypeNode,
    val body: BlockExprNode?
) : ItemNode

data class ParamNode(val pattern: PatternNode, val type: TypeNode)
