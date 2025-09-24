package frontend.semantic

import frontend.ast.*
import frontend.Keyword
import frontend.Literal
import frontend.Punctuation
import utils.CompileError


class RImplInjector(val gScope: Scope, val crate: CrateNode) : ASTVisitor<Unit> {
  var currentScope: Scope? = gScope

  fun process() = visit(crate)


  //------------------Visitors----------------------
  override fun visit(node: CrateNode) {
    currentScope = node.scope
    node.items.forEach { it.accept(this) }
    currentScope = currentScope?.parentScope()
  }

  override fun visit(node: FunctionItemNode) {
    currentScope = node.body?.scope ?: return
    node.body.stmts.forEach { it.accept(this) }
    currentScope = currentScope?.parentScope()
  }

  override fun visit(node: StructItemNode) {}

  override fun visit(node: EnumItemNode) {}

  override fun visit(node: TraitItemNode) {}

  override fun visit(node: ImplItemNode) {
    currentScope = node.scope
    val trait = if (node.name != null) {
      currentScope?.resolve(node.name, Namespace.TYPE) as? Trait
        ?: throw CompileError("Semantic: implementing an invalid trait ${node.name}")
    } else null
    trait?.type?.associatedItems?.forEach {
      when (it.value) {
        is Constant -> {
          if ((it.value as Constant).value == null) {
            if (currentScope?.resolve(it.value.name, Namespace.VALUE) !is Constant) {
              throw CompileError("Semantic: impl of trait missing associate item ${it.value}")
            }
          }
        }

        is Function -> {
          if (currentScope?.resolve(it.value.name, Namespace.VALUE) !is Function) {
            throw CompileError("Semantic: impl of trait missing associate item ${it.value}")
          }
        }

        else -> throw CompileError("Semantic: invalid trait associate item for ${it.value}")
      }
    }
    if (node.type is TypePathNode && node.type.name != null) {
      val symbol = currentScope?.resolve(node.type.name, Namespace.TYPE)
      when (symbol) {
        is Enum -> {
          val typedSelf = symbol.type
          node.items.forEach { it ->

          }

        }

        is Struct -> {

        }

        else -> throw CompileError("Semantic: impl target type ${node.type} not found")
      }
    } else throw CompileError("Semantic: impl target type ${node.type} not found")
    currentScope = currentScope?.parentScope()
  }

  override fun visit(node: ConstItemNode) {
    node.expr?.accept(this)
  }

  override fun visit(node: BlockExprNode) {
    currentScope = node.scope
    node.stmts.forEach { it.accept(this) }
    currentScope = currentScope?.parentScope()
  }

  override fun visit(node: LoopExprNode) {
    node.expr.accept(this)
  }

  override fun visit(node: WhileExprNode) {
    node.conds.forEach { it.accept(this) }
    node.expr.accept(this)
  }

  override fun visit(node: BreakExprNode) {
    node.expr?.accept(this)
  }

  override fun visit(node: ReturnExprNode) {
    node.expr?.accept(this)
  }

  override fun visit(node: ContinueExprNode) {}

  override fun visit(node: IfExprNode) {
    node.conds.forEach { it.accept(this) }
    node.expr.accept(this)
    node.elseIf?.accept(this)
    node.elseExpr?.accept(this)
  }

  override fun visit(node: FieldAccessExprNode) {
    node.expr.accept(this)
  }

  override fun visit(node: MethodCallExprNode) {
    node.expr.accept(this)
    node.pathSeg.accept(this)
    node.params.forEach { it.accept(this) }
  }

  override fun visit(node: CallExprNode) {
    node.expr.accept(this)
    node.params.forEach { it.accept(this) }
  }

  override fun visit(node: CondExprNode) {
    node.expr.accept(this)
    node.pattern?.accept(this)
  }

  override fun visit(node: LiteralExprNode) {}

  override fun visit(node: IdentifierExprNode) {}

  override fun visit(node: PathExprNode) {
    node.seg1.accept(this)
    node.seg2?.accept(this)
  }

  override fun visit(node: ArrayExprNode) {
    node.elements?.forEach { it.accept(this) }
    node.lengthOp?.accept(this)
    node.repeatOp?.accept(this)
  }

  override fun visit(node: IndexExprNode) {
    node.index.accept(this)
    node.base.accept(this)
  }

  override fun visit(node: StructExprNode) {
    node.path.accept(this)
    node.fields.forEach { it.expr?.accept(this) }
  }

  override fun visit(node: UnderscoreExprNode) {}

  override fun visit(node: UnaryExprNode) {
    node.rhs.accept(this)
  }

  override fun visit(node: BinaryExprNode) {
    node.lhs.accept(this)
    node.rhs.accept(this)
  }

  override fun visit(node: ItemStmtNode) {
    node.item.accept(this)
  }

  override fun visit(node: LetStmtNode) {
    node.expr?.accept(this)
  }

  override fun visit(node: ExprStmtNode) {
    node.expr.accept(this)
  }

  override fun visit(node: NullStmtNode) {}

  override fun visit(node: IdentifierPatternNode) {
    node.subPattern?.accept(this)
  }

  override fun visit(node: RefPatternNode) {
    node.pattern.accept(this)
  }

  override fun visit(node: WildcardPatternNode) {
  }

  override fun visit(node: TypePathNode) {}

  override fun visit(node: RefTypeNode) {
    node.type.accept(this)
  }

  override fun visit(node: ArrayTypeNode) {
    node.expr.accept(this)
  }

  override fun visit(node: UnitTypeNode) {}

  override fun visit(node: GroupedExprNode) {
    node.expr.accept(this)
  }

  override fun visit(node: CastExprNode) {
    node.expr.accept(this)
  }
}