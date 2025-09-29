package utils

import frontend.semantic.*
import frontend.ast.*
import frontend.semantic.Function
import frontend.semantic.Enum

/**
 * ResolvedSymbolDumper
 *
 * 一个用于调试的工具，设计用于在 RSymbolResolver Pass 执行后，以详细的树形结构打印符号表。
 * 它清晰地展示了符号的已解析类型、常量值、函数签名、结构体字段等信息。
 *
 * 使用方法:
 * ```
 * // 在 RSymbolResolver.process() 执行完毕后
 * val symbolResolver = RSymbolResolver(crateNode.scope!!, crateNode)
 * symbolResolver.process()
 *
 * // 创建并运行 dumper
 * val dumper = ResolvedSymbolDumper(crateNode)
 * dumper.dump()
 * ```
 */
class RResolvedSymbolDumper(private val crate: CrateNode) : ASTVisitor<Unit> {

  private var indentLevel = 0

  fun dump() {
    println("\n----- Resolved Symbol Table Dump (after RSymbolResolver) -----")
    visit(crate)
    println("----- End of Resolved Symbol Table Dump -----")
  }

  private fun printWithIndent(message: String) {
    println("  ".repeat(indentLevel) + message)
  }

  /**
   * 格式化 ConstValue 为易于阅读的字符串。
   */
  private fun formatConstValue(value: ConstValue?): String {
    return when (value) {
      null -> "<unresolved>"
      is ConstValue.Int -> value.value.toString()
      is ConstValue.Bool -> value.value.toString()
      is ConstValue.Char -> "'${value.value}'"
      is ConstValue.Str -> "\"${value.value}\""
      is ConstValue.Array -> {
        val elementsStr = value.elements.joinToString(", ") { formatConstValue(it) }
        "[${elementsStr}]"
      }

      is ConstValue.Struct -> {
        val fieldsStr = value.fields.entries.joinToString(", ") { "${it.key}: ${formatConstValue(it.value)}" }
        "${value.type.name} { $fieldsStr }"
      }
    }
  }

  /**
   * 将 Symbol 格式化为包含解析后详细信息的字符串。
   */
  private fun formatSymbolDetails(symbol: Symbol): String {
    return when (symbol) {
      is Function -> {
        val paramsStr = symbol.params.joinToString(", ") { p ->
          val mutStr = if (p.isMutable) "mut " else ""
          "$mutStr${p.name}: ${p.type}"
        }
        val selfStr = symbol.selfParam?.let { "self: $it, " } ?: ""
        "Function: fn ${symbol.name}($selfStr$paramsStr) -> ${symbol.returnType}"
      }

      is Constant -> "Constant: const ${symbol.name}: ${symbol.type} = ${formatConstValue(symbol.value)} (${symbol.resolutionState})"
      is Variable -> {
        val mutStr = if (symbol.isMutable) "mut " else ""
        "Variable: let $mutStr${symbol.name}: ${symbol.type}"
      }

      is Struct -> {
        val fieldsStr = symbol.type.fields.entries.joinToString("; ") { "${it.key}: ${it.value}" }
        "Struct: struct ${symbol.name} { $fieldsStr } (${symbol.resolutionState})"
      }

      is Enum -> {
        val variantsStr = symbol.type.variants.joinToString(" | ")
        "Enum: enum ${symbol.name} { $variantsStr } (${symbol.resolutionState})"
      }

      is Trait -> "Trait: trait ${symbol.name}"
      is BuiltIn -> "BuiltIn Type: ${symbol.name}"
    }
  }

  private fun dumpScope(scope: Scope) {
    printWithIndent("-> Scope (kind: ${scope.kind})")
    indentLevel++

    // 反射访问 Scope 的私有字段。注意：这仅用于调试，不应在生产代码中使用。
    // 如果可以修改 Scope 类，最好为其添加公共的只读访问器。
    val typeNamespace =
      (scope.javaClass.getDeclaredField("typeNamespace").apply { isAccessible = true }.get(scope) as? Map<*, *>)
    val valueNamespace =
      (scope.javaClass.getDeclaredField("valueNamespace").apply { isAccessible = true }.get(scope) as? Map<*, *>)
    val variables =
      (scope.javaClass.getDeclaredField("variables").apply { isAccessible = true }.get(scope) as? Map<*, *>)

    if (typeNamespace?.isNotEmpty() == true) {
      printWithIndent("Type Namespace:")
      typeNamespace.values.forEach { symbol ->
        printWithIndent("  - ${formatSymbolDetails(symbol as Symbol)}")
      }
    }

    if (valueNamespace?.isNotEmpty() == true) {
      printWithIndent("Value Namespace:")
      valueNamespace.values.forEach { symbol ->
        printWithIndent("  - ${formatSymbolDetails(symbol as Symbol)}")
      }
    }

    if (variables?.isNotEmpty() == true) {
      printWithIndent("Variables:")
      variables.values.forEach { symbol ->
        printWithIndent("  - ${formatSymbolDetails(symbol as Symbol)}")
      }
    }

    indentLevel--
  }

  override fun visit(node: CrateNode) {
    node.scope?.let { dumpScope(it) } ?: printWithIndent("CrateNode has no scope!")

    // 遍历 AST 节点以递归打印嵌套作用域
    indentLevel++
    node.items.forEach { it.accept(this) }
    indentLevel--
  }

  override fun visit(node: FunctionItemNode) {
    node.body?.accept(this)
  }

  override fun visit(node: ImplItemNode) {
    node.scope?.let {
      indentLevel++
      dumpScope(it)
      // 此时可以深入 impl 的 items，因为它们也被解析了
      node.items.forEach { item -> item.accept(this) }
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

  // --- 其他 visit 方法保持与上一个 dumper 相似或为空 ---
  override fun visit(node: LoopExprNode) {
    node.expr.accept(this)
  }

  override fun visit(node: WhileExprNode) {
    node.expr.accept(this)
  }

  override fun visit(node: ItemStmtNode) {
    node.item.accept(this)
  }

  override fun visit(node: LetStmtNode) { /* 变量声明在作用域中已被打印，无需额外操作 */
  }

  override fun visit(node: StructItemNode) {}
  override fun visit(node: EnumItemNode) {}
  override fun visit(node: TraitItemNode) {}
  override fun visit(node: ConstItemNode) {}
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