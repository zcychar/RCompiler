package frontend.AST

import frontend.TokenType

sealed interface TypeNode

data class TypePathNode(val id: String?, val type: TokenType?) : TypeNode

data class RefTypeNode(val hasMut: Boolean, val type: TypeNode) : TypeNode

data class ArrayTypeNode(val type: TypeNode, val expr: ExprNode) : TypeNode

data class SliceTypeNode(val type: TypeNode) : TypeNode

data object InferredTypeNode : TypeNode

data object UnitTypeNode : TypeNode
