package frontend.AST

sealed interface StmtNode

data class ItemStmtNode(val item: ItemNode) : StmtNode

data class LetStmtNode(val pattern: PatternNode, val type: TypeNode?, val expr: ExprNode?) : StmtNode

data class ExprStmtNode(val expr: ExprNode) : StmtNode

data object NullStmtNode : StmtNode
