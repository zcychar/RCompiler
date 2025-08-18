package frontend.AST

import frontend.TokenType

sealed interface ExprNode

sealed interface ExprWIBlock : ExprNode

sealed interface ExprWOBlock : ExprNode

data class BlockExprNode(val hasConst: Boolean,val stmts: List<StmtNode>) : ExprWIBlock


data class LoopExprNode(val expr: BlockExprNode) : ExprWIBlock

data class WhileExprNode(val conds: List<CondExprNode>, val expr: BlockExprNode) : ExprWIBlock

data class BreakExprNode(val expr: ExprNode?) : ExprWOBlock

data class ReturnExprNode(val expr: ExprNode?) : ExprWOBlock

data object ContinueExprNode : ExprWOBlock

data class IfExprNode(
    val conds: List<CondExprNode>, val expr: BlockExprNode, val elseExpr: BlockExprNode?, val elseIf: IfExprNode?
) : ExprWIBlock

data class FieldAccessExprNode(val expr: ExprNode, val id: String) : ExprWOBlock

data class MethodCallExprNode(val expr: ExprNode, val pathSeg: PathExprNode.PathExprSeg, val params: List<ExprNode>) :
    ExprWOBlock

data class MatchExprNode(val scur: ExprNode, val arms: List<Pair<MatchArmNode, ExprNode>>) : ExprWIBlock {
    data class MatchArmNode(val pattern: PatternNode, val guard: ExprNode?)
}

data class CallExprNode(val expr: ExprNode, val params: List<ExprNode>) : ExprWOBlock

data class CondExprNode(val pattern: PatternNode?, val expr: ExprNode) : ExprNode


data class LiteralExprNode(val value: String?, val type: TokenType) : ExprWOBlock

data class IdentifierExprNode(val value: String) : ExprWOBlock

data class PathExprNode(val seg1: PathExprSeg, val seg2: PathExprSeg?) : ExprWOBlock {
    data class PathExprSeg(val id: String?, val keyword: TokenType?)
}

data class ArrayExprNode(val elements: List<ExprNode>?, val repeatOp: ExprNode?, val lengthOp: ExprNode?) : ExprWOBlock

data class IndexExprNode(val first: ExprNode, val second: ExprNode) : ExprNode

data class StructExprNode(val path: ExprNode,val fields:List<StructExprField>): ExprNode{
    data class StructExprField(val id:String ,val expr: ExprNode?)
}

data class RangeExprNode(val op: TokenType, val from: ExprNode?, val to: ExprNode?) : ExprWOBlock

data object UnderscoreExprNode : ExprWOBlock

data class UnaryExprNode(val op: TokenType, val hasMut: Boolean, val rhs: ExprNode) : ExprWOBlock

data class BinaryExprNode(val op: TokenType, val lhs: ExprNode, val rhs: ExprNode) : ExprWOBlock
