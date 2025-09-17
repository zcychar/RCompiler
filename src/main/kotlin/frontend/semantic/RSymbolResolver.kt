package frontend.semantic

import frontend.AST.*
import frontend.Keyword
import frontend.Literal
import utils.CompileError


class RSymbolResolver(val gScope: Scope, val crate: CrateNode) : ASTVisitor<Unit> {
  var currentScope: Scope? = gScope
  var currentSelfType: Type? = null

  fun process() = visit(crate)

  //---------------Const Evaluation-----------------
  fun evaluateConstExpr(expr: ExprNode, expectType: Type): ConstValue = when (expr) {
    is LiteralExprNode -> when (expr.type) {
      Keyword.TRUE -> ConstValue.Bool(true)
      Keyword.FALSE -> ConstValue.Bool(false)
      Literal.INTEGER -> {
        val value =
          expr.value?.replace("_", "")?.toLong() ?: throw CompileError("Semantic:Invalid interger in const expression")
        ConstValue.Int(value)
      }

      Literal.CHAR -> {
        val value = expr.value ?: ""
        if (value.length != 1) {
          throw CompileError("Semantic:Invalid Char $expr")
        }
        ConstValue.Char(value[0])
      }

      Literal.RAW_C_STRING, Literal.C_STRING, Literal.STRING, Literal.RAW_STRING -> ConstValue.Str(expr.value ?: "")
      else -> throw CompileError("Semantic:Unsupported literal type $expr")
    }

    is PathExprNode -> {
      val name = expr.seg1.id ?: throw CompileError("Semantic:Const path with no identified found")
      val symbol = currentScope?.resolve(name, Namespace.VALUE)
        ?: throw CompileError("Semantic: cannot find value $name in this scope")
      when (symbol) {
        is Constant -> {
          val resolvedConst = resolveConst(name)
        }

        else -> TODO()
      }

      TODO()
    }

    else -> throw CompileError("Semantic:Invalid type of expr ${expr.toString()} in const expression")
  }

  //---------------Type Resolution------------------
  fun resolveType(node: TypeNode): Type = when (node) {
    is ArrayTypeNode -> {
      val elementType = resolveType(node.type)
      val sizeValue = evaluateConstExpr(node.expr, UInt32Type)
      val size = (sizeValue as? ConstValue.Int)?.value?.toInt()
        ?: throw CompileError("Semantic: Array size must be a constant integer.")
      if (size < 0) throw CompileError("Semantic: Array size cannot be negative.")
      ArrayType(elementType, size)
    }

    is RefTypeNode -> RefType(resolveType(node.type), node.hasMut)
    is TypePathNode -> {
      if (node.type == Keyword.SELF_UPPER) {
        currentSelfType ?: throw CompileError("Semantic:Invalid usage of 'Self'")
      }
      val name = node.name ?: throw CompileError("Semantic:TypePathNode has no ID and is not Self")
      when (val symbol = currentScope?.resolve(name, Namespace.TYPE)) {
        is Struct -> resolveStruct(symbol.name)
        is Enum -> resolveEnum(symbol.name)
        is BuiltIn -> symbol.type
        null -> throw CompileError("Semantic:Type $name not found")
        else -> throw CompileError("Semantic:'$name is not a type")
      }
    }

    UnitTypeNode -> UnitType
  }

  //-----------------Resolvers----------------------
  fun resolveStruct(name: String): StructType {
    TODO()
  }

  fun resolveEnum(name: String): EnumType {
    TODO()
  }

  fun resolveFunction(name: String) {
    TODO()
  }

  fun resolveConst(name: String): Constant {
    TODO()
  }

  //------------------Visitors----------------------
  override fun visit(node: CrateNode) {
    currentScope = node.scope
    node.items.forEach { it.accept(this) }
    currentScope = currentScope?.parentScope()
  }

  override fun visit(node: FunctionItemNode) {

  }

  override fun visit(node: StructItemNode) {
    TODO("Not yet implemented")
  }

  override fun visit(node: EnumItemNode) {
    TODO("Not yet implemented")
  }

  override fun visit(node: TraitItemNode) {
    TODO("Not yet implemented")
  }

  override fun visit(node: ImplItemNode) {
    TODO("Not yet implemented")
  }

  override fun visit(node: ConstItemNode) {
    TODO("Not yet implemented")
  }

  override fun visit(node: BlockExprNode) {
    TODO("Not yet implemented")
  }

  override fun visit(node: LoopExprNode) {
    TODO("Not yet implemented")
  }

  override fun visit(node: WhileExprNode) {
    TODO("Not yet implemented")
  }

  override fun visit(node: BreakExprNode) {
    TODO("Not yet implemented")
  }

  override fun visit(node: ReturnExprNode) {
    TODO("Not yet implemented")
  }

  override fun visit(node: ContinueExprNode) {
    TODO("Not yet implemented")
  }

  override fun visit(node: IfExprNode) {
    TODO("Not yet implemented")
  }

  override fun visit(node: FieldAccessExprNode) {
    TODO("Not yet implemented")
  }

  override fun visit(node: MethodCallExprNode) {
    TODO("Not yet implemented")
  }

  override fun visit(node: CallExprNode) {
    TODO("Not yet implemented")
  }

  override fun visit(node: CondExprNode) {
    TODO("Not yet implemented")
  }

  override fun visit(node: LiteralExprNode) {
    TODO("Not yet implemented")
  }

  override fun visit(node: IdentifierExprNode) {
    TODO("Not yet implemented")
  }

  override fun visit(node: PathExprNode) {
    TODO("Not yet implemented")
  }

  override fun visit(node: ArrayExprNode) {
    TODO("Not yet implemented")
  }

  override fun visit(node: IndexExprNode) {
    TODO("Not yet implemented")
  }

  override fun visit(node: StructExprNode) {
    TODO("Not yet implemented")
  }

  override fun visit(node: UnderscoreExprNode) {
    TODO("Not yet implemented")
  }

  override fun visit(node: UnaryExprNode) {
    TODO("Not yet implemented")
  }

  override fun visit(node: BinaryExprNode) {
    TODO("Not yet implemented")
  }

  override fun visit(node: ItemStmtNode) {
    TODO("Not yet implemented")
  }

  override fun visit(node: LetStmtNode) {
    TODO("Not yet implemented")
  }

  override fun visit(node: ExprStmtNode) {
    TODO("Not yet implemented")
  }

  override fun visit(node: NullStmtNode) {
    TODO("Not yet implemented")
  }

  override fun visit(node: LiteralPatternNode) {
    TODO("Not yet implemented")
  }

  override fun visit(node: IdentifierPatternNode) {
    TODO("Not yet implemented")
  }

  override fun visit(node: RefPatternNode) {
    TODO("Not yet implemented")
  }

  override fun visit(node: PathPatternNode) {
    TODO("Not yet implemented")
  }

  override fun visit(node: WildcardPatternNode) {
    TODO("Not yet implemented")
  }

  override fun visit(node: TypePathNode) {
    TODO("Not yet implemented")
  }

  override fun visit(node: RefTypeNode) {
    TODO("Not yet implemented")
  }

  override fun visit(node: ArrayTypeNode) {
    TODO("Not yet implemented")
  }

  override fun visit(node: UnitTypeNode) {
    TODO("Not yet implemented")
  }
}