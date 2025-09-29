package utils

import frontend.semantic.*

import frontend.ast.*

/**
 * 修正后的辅助对象，用于追踪语义检查过程。
 */
private object SemanticTracer {
  var indentLevel = 0
  var enableTracing = true // 全局开关

  fun log(message: String) {
    if (enableTracing) {
      println("  ".repeat(indentLevel) + message)
    }
  }

  /**
   * 核心追踪函数，修正了对 TokenType 的处理。
   */
  fun <T> traceVisit(node: Any, originalVisitCall: () -> T): T {
    val nodeName = node::class.simpleName

    // 修正：不再假设 .id 存在，直接使用 TokenType 对象的 toString() 方法，这对于所有类型都是安全的。
    val extraInfo = when (node) {
      is BinaryExprNode -> "op=${node.op}"
      is UnaryExprNode -> "op=${node.op}"
      is LiteralExprNode -> "type=${node.type}, value='${node.value}'"
      is PathExprNode -> "path='${node.seg1.name ?: "self"}${node.seg2?.let { "::" + it.name } ?: ""}'"
      is FieldAccessExprNode -> "field='${node.id}'"
      is MethodCallExprNode -> "method='${node.pathSeg.name}'"
      is CallExprNode -> "callee='${(node.expr as? PathExprNode)?.seg1?.name ?: "..."}'"
      is LetStmtNode -> "pattern='${(node.pattern as? IdentifierPatternNode)?.id ?: "..."}'"
      else -> ""
    }

    log("-> Enter $nodeName($extraInfo)")
    indentLevel++

    val result = try {
      originalVisitCall() // 执行原始逻辑
    } catch (e: Exception) {
      indentLevel--
      // 截断过长的错误信息，保持日志整洁
      val shortMessage = e.message?.let { it.substring(0, 100.coerceAtMost(it.length)) + "..." } ?: "No message"
      log("<- Exception in $nodeName: $shortMessage")
      throw e
    }

    indentLevel--

    if (result is Type) {
      log("<- Exit $nodeName, inferred type: $result")
    } else {
      log("<- Exit $nodeName")
    }
    return result
  }
}

/**
 * TracedSemanticChecker (修正版)
 *
 * 这个装潢器类现在可以与您项目中的 token.kt 无缝协作。
 */
class TracedSemanticChecker(gScope: Scope, crate: CrateNode) : RSemanticChecker(gScope, crate) {

  override fun process(): Type {
    SemanticTracer.indentLevel = 0 // 重置
    return super.process()
  }

  // --- 所有 visit 方法的覆盖保持不变，它们都委托给修正后的 tracer ---

  override fun visit(node: CrateNode): Type = SemanticTracer.traceVisit(node) { super.visit(node) }
  override fun visit(node: FunctionItemNode): Type = SemanticTracer.traceVisit(node) { super.visit(node) }
  override fun visit(node: StructItemNode): Type = SemanticTracer.traceVisit(node) { super.visit(node) }
  override fun visit(node: EnumItemNode): Type = SemanticTracer.traceVisit(node) { super.visit(node) }
  override fun visit(node: TraitItemNode): Type = SemanticTracer.traceVisit(node) { super.visit(node) }
  override fun visit(node: ImplItemNode): Type = SemanticTracer.traceVisit(node) { super.visit(node) }
  override fun visit(node: ConstItemNode): Type = SemanticTracer.traceVisit(node) { super.visit(node) }
  override fun visit(node: BlockExprNode): Type = SemanticTracer.traceVisit(node) { super.visit(node) }
  override fun visit(node: LoopExprNode): Type = SemanticTracer.traceVisit(node) { super.visit(node) }
  override fun visit(node: WhileExprNode): Type = SemanticTracer.traceVisit(node) { super.visit(node) }
  override fun visit(node: BreakExprNode): Type = SemanticTracer.traceVisit(node) { super.visit(node) }
  override fun visit(node: ReturnExprNode): Type = SemanticTracer.traceVisit(node) { super.visit(node) }
  override fun visit(node: ContinueExprNode): Type = SemanticTracer.traceVisit(node) { super.visit(node) }
  override fun visit(node: IfExprNode): Type = SemanticTracer.traceVisit(node) { super.visit(node) }
  override fun visit(node: FieldAccessExprNode): Type = SemanticTracer.traceVisit(node) { super.visit(node) }
  override fun visit(node: MethodCallExprNode): Type = SemanticTracer.traceVisit(node) { super.visit(node) }
  override fun visit(node: CallExprNode): Type = SemanticTracer.traceVisit(node) { super.visit(node) }
  override fun visit(node: CondExprNode): Type = SemanticTracer.traceVisit(node) { super.visit(node) }
  override fun visit(node: LiteralExprNode): Type = SemanticTracer.traceVisit(node) { super.visit(node) }
  override fun visit(node: IdentifierExprNode): Type = SemanticTracer.traceVisit(node) { super.visit(node) }
  override fun visit(node: PathExprNode): Type = SemanticTracer.traceVisit(node) { super.visit(node) }
  override fun visit(node: ArrayExprNode): Type = SemanticTracer.traceVisit(node) { super.visit(node) }
  override fun visit(node: IndexExprNode): Type = SemanticTracer.traceVisit(node) { super.visit(node) }
  override fun visit(node: StructExprNode): Type = SemanticTracer.traceVisit(node) { super.visit(node) }
  override fun visit(node: UnderscoreExprNode): Type = SemanticTracer.traceVisit(node) { super.visit(node) }
  override fun visit(node: UnaryExprNode): Type = SemanticTracer.traceVisit(node) { super.visit(node) }
  override fun visit(node: BinaryExprNode): Type = SemanticTracer.traceVisit(node) { super.visit(node) }
  override fun visit(node: ItemStmtNode): Type = SemanticTracer.traceVisit(node) { super.visit(node) }
  override fun visit(node: LetStmtNode): Type = SemanticTracer.traceVisit(node) { super.visit(node) }
  override fun visit(node: ExprStmtNode): Type = SemanticTracer.traceVisit(node) { super.visit(node) }
  override fun visit(node: NullStmtNode): Type = SemanticTracer.traceVisit(node) { super.visit(node) }
  override fun visit(node: IdentifierPatternNode): Type = SemanticTracer.traceVisit(node) { super.visit(node) }
  override fun visit(node: RefPatternNode): Type = SemanticTracer.traceVisit(node) { super.visit(node) }
  override fun visit(node: WildcardPatternNode): Type = SemanticTracer.traceVisit(node) { super.visit(node) }
  override fun visit(node: TypePathNode): Type = SemanticTracer.traceVisit(node) { super.visit(node) }
  override fun visit(node: RefTypeNode): Type = SemanticTracer.traceVisit(node) { super.visit(node) }
  override fun visit(node: ArrayTypeNode): Type = SemanticTracer.traceVisit(node) { super.visit(node) }
  override fun visit(node: UnitTypeNode): Type = SemanticTracer.traceVisit(node) { super.visit(node) }
  override fun visit(node: GroupedExprNode): Type = SemanticTracer.traceVisit(node) { super.visit(node) }
  override fun visit(node: CastExprNode): Type = SemanticTracer.traceVisit(node) { super.visit(node) }
  override fun visit(node: BorrowExprNode): Type = SemanticTracer.traceVisit(node) { super.visit(node) }
  override fun visit(node: DerefExprNode): Type = SemanticTracer.traceVisit(node) { super.visit(node) }
}