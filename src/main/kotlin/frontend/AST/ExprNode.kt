package frontend.AST

import frontend.Identifier
import frontend.Keyword
import frontend.Punctuation
import frontend.TokenType

sealed interface ExprNode

data class BlockExprNode(val stmts: List<StmtNode>, val tailExpr: ExprNode?) : ExprNode

data class ConstBlockExprNode(val stmts: List<StmtNode>, val tailExpr: ExprNode?) : ExprNode

data class LoopExprNode(val expr: BlockExprNode) : ExprNode

data class WhileExprNode(val conds: List<CondExprNode>, val expr: BlockExprNode) : ExprNode

data class BreakExprNode(val expr: ExprNode?) : ExprNode

data object ContinueExprNode : ExprNode

data class IfExprNode(
    val conds: List<CondExprNode>, val expr: BlockExprNode, val elseExpr: BlockExprNode?, val elseIf: IfExprNode?
) : ExprNode

data class MatchExprNode(val scur: ExprNode, val arms: Pair<MatchArmNode, ExprNode>) : ExprNode {
    data class MatchArmNode(val pattern: PatternNode, val guard: ExprNode?)
}

data class CondExprNode(val expr: ExprNode) : ExprNode

data class LiteralExprNode(val value: String?, val type: TokenType) : ExprNode

data class PathExprNode(val segments: List<PathExprSeg>) : ExprNode {
    data class PathExprSeg(val id: String?, val keyword: Keyword?)
}

data class NumberExprNode(val value: Int) : ExprNode

data class UnaryExprNode(val op: Punctuation, val rhs: ExprNode) : ExprNode

data class BinaryExprNode(val op: Punctuation, val lhs: ExprNode, val rhs: ExprNode) : ExprNode