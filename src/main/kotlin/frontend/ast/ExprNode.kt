package frontend.ast

import frontend.TokenType
import frontend.semantic.Scope
import frontend.semantic.Symbol
import frontend.semantic.Type
import javax.sound.midi.Receiver

sealed interface ExprNode {
  var isLeft: Boolean
  fun <T> accept(visitor: ASTVisitor<T>): T
}

sealed interface ExprWIBlock : ExprNode

sealed interface ExprWOBlock : ExprNode

data class BlockExprNode(val hasConst: Boolean, val stmts: List<StmtNode>, override var isLeft: Boolean = false) :
  ExprWIBlock {
  override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
  fun hasFinal(): Boolean {
    if (stmts.isEmpty()) return false
    val last = stmts.last()
    return last is ExprStmtNode && (!last.hasSemiColon)
  }

  var scope: Scope? = null
}

data class LoopExprNode(val expr: BlockExprNode, override var isLeft: Boolean = false) : ExprWIBlock {
  override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}

data class WhileExprNode(val conds: List<CondExprNode>, val expr: BlockExprNode, override var isLeft: Boolean = false) :
  ExprWIBlock {
  override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}

data class BreakExprNode(val expr: ExprNode?, override var isLeft: Boolean = false) : ExprWOBlock {
  override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}

data class ReturnExprNode(val expr: ExprNode?, override var isLeft: Boolean = false) : ExprWOBlock {
  override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}

data object ContinueExprNode : ExprWOBlock {
  override var isLeft: Boolean = false
  override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}

data class IfExprNode(
  val conds: List<CondExprNode>, val expr: BlockExprNode, val elseExpr: ExprNode?, override var isLeft: Boolean = false
) : ExprWIBlock {
  override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}

data class FieldAccessExprNode(val expr: ExprNode, val id: String,var structSymbol:frontend.semantic.Struct?= null, override var isLeft: Boolean = false) : ExprWOBlock {
  override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}

data class MethodCallExprNode(
  val expr: ExprNode,
  val pathSeg: TypePathNode,
  val params: List<ExprNode>,
  var receiverType: Type? = null,
  var methodSymbol: frontend.semantic.Function? = null
) :
  ExprWOBlock {
  override var isLeft: Boolean = false
  override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}

//data class MatchExprNode(val scur: ExprNode, val arms: List<Pair<MatchArmNode, ExprNode>>) : ExprWIBlock {
//    override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
//    data class MatchArmNode(val pattern: PatternNode, val guard: ExprNode?)
//}

data class CallExprNode(val expr: ExprNode, val params: List<ExprNode>,var functionSymbol: frontend.semantic.Function? = null, override var isLeft: Boolean = false) :
  ExprWOBlock {
  override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}

data class CondExprNode(val pattern: PatternNode?, val expr: ExprNode, override var isLeft: Boolean = false) :
  ExprNode {
  override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}

data class LiteralExprNode(val value: String?, val type: TokenType, override var isLeft: Boolean = false) :
  ExprWOBlock {
  override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}

data class IdentifierExprNode(val value: String, override var isLeft: Boolean = false) : ExprWOBlock {
  override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}

data class PathExprNode(val seg1: TypePathNode, val seg2: TypePathNode?, override var isLeft: Boolean = false) :
  ExprWOBlock {
  override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}

data class ArrayExprNode(
  val elements: List<ExprNode> = listOf(),
  val repeatOp: ExprNode?,
  val lengthOp: ExprNode?,
  var evaluatedSize: Long = -1,
  var type: Type? = null,
  override var isLeft: Boolean = false
) :
  ExprWOBlock {
  override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}

data class IndexExprNode(val base: ExprNode, val index: ExprNode, override var isLeft: Boolean = false) : ExprNode {
  override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}

data class CastExprNode(val expr: ExprNode, val targetType: TypeNode, override var isLeft: Boolean = false) :
  ExprWOBlock {
  override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}

data class StructExprNode(
  val path: ExprNode,
  val fields: List<StructExprField>,
  var type: Type? = null,
  override var isLeft: Boolean = false
) :
  ExprNode {
  override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
  data class StructExprField(val id: String, val expr: ExprNode?, var fieldType: Type? = null)
}

data object UnderscoreExprNode : ExprWOBlock {
  override var isLeft: Boolean = false
  override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}

data class UnaryExprNode(val op: TokenType, val rhs: ExprNode, override var isLeft: Boolean = false) : ExprWOBlock {
  override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}

data class BinaryExprNode(
  val op: TokenType,
  val lhs: ExprNode,
  val rhs: ExprNode,
  override var isLeft: Boolean = false
) :
  ExprWOBlock {
  override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}

data class GroupedExprNode(val expr: ExprNode, override var isLeft: Boolean = false) : ExprWOBlock {
  override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}

data class BorrowExprNode(
  val expr: ExprNode,
  val isMut: Boolean,
  var type: Type? = null,
  override var isLeft: Boolean = false
) : ExprWOBlock {
  override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}

data class DerefExprNode(val expr: ExprNode, override var isLeft: Boolean = false) : ExprWOBlock {
  override fun <T> accept(visitor: ASTVisitor<T>): T = visitor.visit(this)
}
