package frontend.AST

import frontend.TokenType

sealed interface ExprNode {
    fun <T> accept(visitor: ASTVisitor<T>): T
}

sealed interface ExprWIBlock : ExprNode

sealed interface ExprWOBlock : ExprNode

data class BlockExprNode(val hasConst: Boolean, val stmts: List<StmtNode>) : ExprWIBlock {
    override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}

data class LoopExprNode(val expr: BlockExprNode) : ExprWIBlock {
    override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}

data class WhileExprNode(val conds: List<CondExprNode>, val expr: BlockExprNode) : ExprWIBlock {
    override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}

data class BreakExprNode(val expr: ExprNode?) : ExprWOBlock {
    override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}

data class ReturnExprNode(val expr: ExprNode?) : ExprWOBlock {
    override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}

data object ContinueExprNode : ExprWOBlock {
    override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}

data class IfExprNode(
    val conds: List<CondExprNode>, val expr: BlockExprNode, val elseExpr: BlockExprNode?, val elseIf: IfExprNode?
) : ExprWIBlock {
    override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}

data class FieldAccessExprNode(val expr: ExprNode, val id: String) : ExprWOBlock {
    override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}

data class MethodCallExprNode(val expr: ExprNode, val pathSeg: PathExprNode.PathExprSeg, val params: List<ExprNode>) :
    ExprWOBlock {
    override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}

data class MatchExprNode(val scur: ExprNode, val arms: List<Pair<MatchArmNode, ExprNode>>) : ExprWIBlock {
    override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
    data class MatchArmNode(val pattern: PatternNode, val guard: ExprNode?)
}

data class CallExprNode(val expr: ExprNode, val params: List<ExprNode>) : ExprWOBlock {
    override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}

data class CondExprNode(val pattern: PatternNode?, val expr: ExprNode) : ExprNode {
    override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}

data class LiteralExprNode(val value: String?, val type: TokenType) : ExprWOBlock {
    override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}

data class IdentifierExprNode(val value: String) : ExprWOBlock {
    override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}

data class PathExprNode(val seg1: PathExprSeg, val seg2: PathExprSeg?) : ExprWOBlock {
    override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
    data class PathExprSeg(val id: String?, val keyword: TokenType?)
}

data class ArrayExprNode(val elements: List<ExprNode>?, val repeatOp: ExprNode?, val lengthOp: ExprNode?) :
    ExprWOBlock {
    override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}

data class IndexExprNode(val first: ExprNode, val second: ExprNode) : ExprNode {
    override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}

data class StructExprNode(val path: ExprNode, val fields: List<StructExprField>) : ExprNode {
    override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
    data class StructExprField(val id: String, val expr: ExprNode?)
}

data object UnderscoreExprNode : ExprWOBlock {
    override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}

data class UnaryExprNode(val op: TokenType, val hasMut: Boolean, val rhs: ExprNode) : ExprWOBlock {
    override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}

data class BinaryExprNode(val op: TokenType, val lhs: ExprNode, val rhs: ExprNode) : ExprWOBlock {
    override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}