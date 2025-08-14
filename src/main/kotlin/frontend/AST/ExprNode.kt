package frontend.AST

sealed interface ExprNode

data class BlockExprNode(val stmts: List<StmtNode>, val tailExpr: ExprNode?) : ExprNode
