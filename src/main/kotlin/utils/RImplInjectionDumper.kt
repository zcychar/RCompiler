package utils

import frontend.ast.*
import frontend.semantic.*
import frontend.semantic.Function

/**
 * ImplInjectionDumper
 *
 * 一个调试工具，用于在 RImplInjector Pass 执行后，清晰地展示哪些方法(methods)和
 * 关联项(associateItems)被注入到了 Structs 和 Enums 中。
 *
 * 它只输出那些确实发生了注入的类型，保持输出的简洁性。
 *
 * 使用方法:
 * ```
 * // 在 RImplInjector.process() 执行完毕后
 * val implInjector = RImplInjector(crateNode.scope!!, crateNode)
 * implInjector.process()
 *
 * // 创建并运行 dumper
 * val dumper = ImplInjectionDumper(crateNode)
 * dumper.dump()
 * ```
 */
class RImplInjectionDumper(private val crate: CrateNode) : ASTVisitor<Unit> {

  private var currentScope: Scope? = null

  // 使用一个集合来避免重复打印同一个符号
  private val processedSymbols = mutableSetOf<Symbol>()

  fun dump() {
    println("\n----- Impl Injection Dump (after RImplInjector) -----")
    visit(crate)
    println("----- End of Impl Injection Dump -----")
  }

  /**
   * 重用自 ResolvedSymbolDumper 的格式化工具，以保持输出风格一致。
   */
  private fun formatSymbolDetails(symbol: Symbol): String {
    return when (symbol) {
      is Function -> {
        val paramsStr = symbol.params.joinToString(", ") { p ->
          val mutStr = if (p.isMutable) "mut " else ""
          "${p.name}: ${p.type}"
        }
        val selfStr = symbol.self?.let { "self: $it, " } ?: ""
        "fn ${symbol.name}($selfStr$paramsStr) -> ${symbol.returnType}"
      }

      is Constant -> "const ${symbol.name}: ${symbol.type} = ..." // 在此阶段值可能不关键，故简化
      else -> symbol::class.simpleName ?: "Unknown"
    }
  }

  private fun processHolder(holder: AssociateHolder) {
    val symbol = holder as? Symbol ?: return
    if (symbol in processedSymbols) return // 如果已经处理过，则跳过

    // 只打印那些真正被注入了内容的类型
    if (holder.methods.isNotEmpty() || holder.associateItems.isNotEmpty()) {
      println("Injected items for ${symbol::class.simpleName} '${symbol.name}':")

      // 明确区分 Methods (带 self) 和 Associated Items (不带 self)
      val nonMethodAssociatedItems = holder.associateItems.filterKeys { it !in holder.methods.keys }

      if (holder.methods.isNotEmpty()) {
        println("  Methods (with self):")
        holder.methods.values.forEach { func ->
          println("    - ${formatSymbolDetails(func)}")
        }
      }

      if (nonMethodAssociatedItems.isNotEmpty()) {
        println("  Associated Items (without self):")
        nonMethodAssociatedItems.values.forEach { item ->
          println("    - ${formatSymbolDetails(item)}")
        }
      }
      println() // 添加空行以分隔不同的类型
      processedSymbols.add(symbol)
    }
  }

  override fun visit(node: CrateNode) {
    currentScope = node.scope
    node.items.forEach { it.accept(this) }
  }

  override fun visit(node: StructItemNode) {
    val symbol = currentScope?.resolve(node.name, Namespace.TYPE) as? AssociateHolder
    symbol?.let { processHolder(it) }
    // 结构体内部没有新的作用域，所以不需要深入
  }

  override fun visit(node: EnumItemNode) {
    val symbol = currentScope?.resolve(node.name, Namespace.TYPE) as? AssociateHolder
    symbol?.let { processHolder(it) }
  }

  // --- 其他 visit 方法主要用于正确地遍历作用域 ---
  override fun visit(node: FunctionItemNode) {
    node.body?.let {
      val prevScope = currentScope
      currentScope = it.scope
      it.accept(this)
      currentScope = prevScope
    }
  }

  override fun visit(node: BlockExprNode) {
    val prevScope = currentScope
    currentScope = node.scope
    node.stmts.forEach { it.accept(this) }
    currentScope = prevScope
  }

  override fun visit(node: ItemStmtNode) {
    node.item.accept(this)
  }

  // 其他节点无需特殊处理，保持为空或递归调用即可
  override fun visit(node: ImplItemNode) { /* Dumper本身不关心impl节点，只关心其结果 */
  }

  override fun visit(node: TraitItemNode) {}
  override fun visit(node: ConstItemNode) {}
  override fun visit(node: LoopExprNode) {
    node.expr.accept(this)
  }

  override fun visit(node: WhileExprNode) {
    node.expr.accept(this)
  }

  override fun visit(node: LetStmtNode) {}
  override fun visit(node: ExprStmtNode) {}
  override fun visit(node: NullStmtNode) {}
  override fun visit(node: BreakExprNode) {}
  override fun visit(node: ReturnExprNode) {}
  override fun visit(node: ContinueExprNode) {}
  override fun visit(node: IfExprNode) {}
  override fun visit(node: FieldAccessExprNode) {}
  override fun visit(node: MethodCallExprNode) {}
  override fun visit(node: CallExprNode) {}
  override fun visit(node: CondExprNode) {}
  override fun visit(node: LiteralExprNode) {}
  override fun visit(node: IdentifierExprNode) {}
  override fun visit(node: PathExprNode) {}
  override fun visit(node: ArrayExprNode) {}
  override fun visit(node: IndexExprNode) {}
  override fun visit(node: StructExprNode) {}
  override fun visit(node: UnderscoreExprNode) {}
  override fun visit(node: UnaryExprNode) {}
  override fun visit(node: BinaryExprNode) {}
  override fun visit(node: GroupedExprNode) {}
  override fun visit(node: CastExprNode) {}
  override fun visit(node: BorrowExprNode) {}
  override fun visit(node: DerefExprNode) {}
  override fun visit(node: IdentifierPatternNode) {}
  override fun visit(node: RefPatternNode) {}
  override fun visit(node: WildcardPatternNode) {}
  override fun visit(node: TypePathNode) {}
  override fun visit(node: RefTypeNode) {}
  override fun visit(node: ArrayTypeNode) {}
  override fun visit(node: UnitTypeNode) {}
}