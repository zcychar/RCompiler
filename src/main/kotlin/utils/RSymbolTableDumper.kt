package utils

import frontend.semantic.*
import frontend.ast.*

/**
 * SymbolTableDumper
 *
 * 一个用于调试的工具，用于在 RSymbolCollector Pass 执行后，以树形结构打印出整个符号表。
 * 它通过遍历 AST 节点，并在每个持有 Scope 的节点处打印该 Scope 的内容。
 *
 * 使用方法:
 * ```
 * // 在 RSymbolCollector.process() 执行完毕后
 * val symbolCollector = RSymbolCollector(preludeScope, crateNode)
 * symbolCollector.process()
 *
 * // 创建并运行 dumper
 * val dumper = SymbolTableDumper(crateNode)
 * dumper.dump()
 * ```
 */
class RSymbolTableDumper(private val crate: CrateNode) : ASTVisitor<Unit> {

  private var indentLevel = 0

  fun dump() {
    println("\n----- Symbol Table Dump (after RSymbolCollector) -----")
    visit(crate)
    println("----- End of Symbol Table Dump -----")
  }

  private fun printWithIndent(message: String) {
    println("  ".repeat(indentLevel) + message)
  }

  private fun dumpScope(scope: Scope) {
    printWithIndent("-> Scope (kind: ${scope.kind})")

    if (scope.typeNamespace.isNotEmpty()) {
      printWithIndent("  |-- Type Namespace:")
      scope.typeNamespace.forEach { (name, symbol) ->
        printWithIndent("  |   |-- '$name': ${symbol::class.simpleName}")
      }
    }

    if (scope.valueNamespace.isNotEmpty()) {
      printWithIndent("  |-- Value Namespace:")
      scope.valueNamespace.forEach { (name, symbol) ->
        // 在这个阶段，常量的值还未解析
        val details = if (symbol is Constant) "Constant (unresolved)" else symbol::class.simpleName
        printWithIndent("  |   |-- '$name': $details")
      }
    }
  }

  override fun visit(node: CrateNode) {
    node.scope?.let { dumpScope(it) } ?: printWithIndent("CrateNode has no scope!")

    indentLevel++
    node.items.forEach { it.accept(this) }
    indentLevel--
  }

  override fun visit(node: FunctionItemNode) {
    // 函数符号本身在父作用域中，这里我们只访问它的子作用域（函数体）
    node.body?.accept(this)
  }

  override fun visit(node: ImplItemNode) {
    node.scope?.let {
      indentLevel++
      dumpScope(it)
      // RSymbolCollector 不会深入 impl 的 items，所以我们也不深入
      indentLevel--
    }
  }

  override fun visit(node: BlockExprNode) {
    node.scope?.let {
      indentLevel++
      dumpScope(it)
      node.stmts.forEach { stmt -> stmt.accept(this) }
      indentLevel--
    }
  }

  override fun visit(node: LoopExprNode) {
    node.expr.accept(this)
  }

  override fun visit(node: WhileExprNode) {
    node.expr.accept(this)
  }

  override fun visit(node: ItemStmtNode) {
    node.item.accept(this)
  }

  // --- 其他 visit 方法 ---
  // 对于这个 Dumper，我们只关心创建新作用域的节点。
  // 其他节点我们或者不需要访问，或者只需要默认空实现以满足 ASTVisitor 接口。
  override fun visit(node: StructItemNode) { /* 结构体声明本身不创建新的子作用域 */
  }

  override fun visit(node: EnumItemNode) { /* 枚举声明本身不创建新的子作用域 */
  }

  override fun visit(node: TraitItemNode) { /* Trait 声明本身不创建新的子作用域 */
  }

  override fun visit(node: ConstItemNode) { /* 常量声明不创建新的子作用域 */
  }

  override fun visit(node: LetStmtNode) { /* Let 语句在
     SymbolCollector 阶段不处理 */
  }

  override fun visit(node: ExprStmtNode) { /* 表达式语句不创建新作用域 */
  }

  override fun visit(node: NullStmtNode) { /* 空语句无操作 */
  }

  override fun visit(node: BreakExprNode) {
    node.expr?.accept(this)
  }

  override fun visit(node: ReturnExprNode) {
    node.expr?.accept(this)
  }

  override fun visit(node: ContinueExprNode) { /* 无子节点 */
  }

  override fun visit(node: IfExprNode) { /* 暂不处理 */
  }

  override fun visit(node: FieldAccessExprNode) { /* 暂不处理 */
  }

  override fun visit(node: MethodCallExprNode) { /* 暂不处理 */
  }

  override fun visit(node: CallExprNode) { /* 暂不处理 */
  }

  override fun visit(node: CondExprNode) { /* 暂不处理 */
  }

  override fun visit(node: LiteralExprNode) { /* 暂不处理 */
  }

  override fun visit(node: IdentifierExprNode) { /* 暂不处理 */
  }

  override fun visit(node: PathExprNode) { /* 暂不处理 */
  }

  override fun visit(node: ArrayExprNode) { /* 暂不处理 */
  }

  override fun visit(node: IndexExprNode) { /* 暂不处理 */
  }

  override fun visit(node: StructExprNode) { /* 暂不处理 */
  }

  override fun visit(node: UnderscoreExprNode) { /* 暂不处理 */
  }

  override fun visit(node: UnaryExprNode) { /* 暂不处理 */
  }

  override fun visit(node: BinaryExprNode) { /* 暂不处理 */
  }

  override fun visit(node: GroupedExprNode) { /* 暂不处理 */
  }

  override fun visit(node: CastExprNode) { /* 暂不处理 */
  }

  override fun visit(node: BorrowExprNode) { /* 暂不处理 */
  }

  override fun visit(node: DerefExprNode) { /* 暂不处理 */
  }

  override fun visit(node: IdentifierPatternNode) { /* 暂不处理 */
  }

  override fun visit(node: RefPatternNode) { /* 暂不处理 */
  }

  override fun visit(node: WildcardPatternNode) { /* 暂不处理 */
  }

  override fun visit(node: TypePathNode) { /* 暂不处理 */
  }

  override fun visit(node: RefTypeNode) { /* 暂不处理 */
  }

  override fun visit(node: ArrayTypeNode) { /* 暂不处理 */
  }

  override fun visit(node: UnitTypeNode) { /* 暂不处理 */
  }
}