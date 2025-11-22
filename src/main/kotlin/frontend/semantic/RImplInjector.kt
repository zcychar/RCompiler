package frontend.semantic

import frontend.ast.*
import frontend.Keyword
import frontend.Literal
import frontend.Punctuation


class RImplInjector(val gScope: Scope, val crate: CrateNode) : ASTVisitor<Unit> {
  var currentScope: Scope? = gScope

  fun process() = visit(crate)

  fun injectFunctionImpl(target: Symbol, function: Function) {
    when (target) {
      is Enum -> {
        val typedSelf = target.type
        function.self = typedSelf
        if (function.returnType is SelfType) function.returnType = typedSelf
        target.associateItems.put(function.name, function)
          ?.let { semanticError("duplicated associate item for enum ${target.name}, name ${function.name}") }
        if (function.selfParam != null) {
          target.methods.put(function.name, function)
        }
      }

      is Struct -> {
        val typedSelf = target.type
        function.self = typedSelf
        if (function.returnType is SelfType) function.returnType = typedSelf
        target.associateItems.put(function.name, function)
          ?.let { semanticError("duplicated associate item for struct ${target.name}, name ${function.name}") }
        if (function.selfParam != null) {
          target.methods.put(function.name, function)
        }
      }

      else -> {
        semanticError("impl target type ${target.name} not found")
      }
    }
  }

  fun injectConstImpl(target: Symbol, const: Constant) {
    when (target) {
      is Enum, is Struct -> {
        target.associateItems.put(const.name, const) ?.let { semanticError("duplicated associate item for struct/enum $target, name $const") }
      }

      else -> {
        semanticError("impl target type ${target.name} not found")
      }
    }
  }

  fun checkTraitConstant(const: Constant, trait: Trait) {
    val constInTrait = trait.type.associatedItems[const.name] as? Constant
      ?: semanticError("implementing a trait with wrong constant ${const.name}")
    if (constInTrait.type != const.type) {
      semanticError("implementing a trait with wrong constant ${const.name}, expect ${constInTrait.name}")
    }
  }

  fun checkTraitFunction(function: Function, trait: Trait) {
    val functionInTrait = trait.type.associatedItems[function.name] as? Function
      ?: semanticError("implementing a trait with wrong function ${function.name}")
    val isSame = functionInTrait.params == function.params
        && function.returnType == functionInTrait.returnType
        && function.selfParam == functionInTrait.selfParam
    if (!isSame) {
      semanticError("implementing a trait with wrong function ${function.name}, expect ${functionInTrait.name}")
    }
  }

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
        ?: semanticError("implementing an invalid trait ${node.name}")
    } else null
    val symbol = if (node.type is TypePathNode && node.type.name != null) {
      currentScope?.resolve(node.type.name, Namespace.TYPE)
        ?: semanticError("impl target type ${node.type.name} not found")
    } else semanticError("impl target type ${node.type} not found")
    val addedAssociates = mutableMapOf<String, Symbol>()
    node.items.forEach {
      when (it) {
        is ConstItemNode -> {
          val const = currentScope?.resolve(it.name, Namespace.VALUE) as? Constant
            ?: semanticError("impl associate does not found in ${node.name}")
          injectConstImpl(symbol, const)
          if (trait != null) checkTraitConstant(const, trait)
          addedAssociates.put(const.name, const)
        }

        is FunctionItemNode -> {
          val function = currentScope?.resolve(it.name, Namespace.VALUE) as? Function
            ?: semanticError("impl associate does not found in ${node.name}")
          injectFunctionImpl(symbol, function)
          if (trait != null) checkTraitFunction(function, trait)
          addedAssociates.put(function.name, function)
        }

        else -> semanticError("invalid impl ${node.name}")
      }
    }
    trait?.type?.associatedItems?.forEach {
      when (it.value) {
        is Constant -> {
          if ((it.value as Constant).value == null) {
            currentScope?.resolve(it.value.name, Namespace.VALUE) as? Constant
              ?: semanticError("impl for trait ${trait.name}, associate ${it.value.name} does not found in ${node.name}")
          }
        }

        is Function -> {
          currentScope?.resolve(it.value.name, Namespace.VALUE) as? Function
            ?: semanticError("impl for trait ${trait.name}, associate ${it.value.name} does not found in ${node.name}")
        }

        else -> semanticError("invalid associate item ${it.value.name} of trait")
      }
    }
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

  override fun visit(node: BorrowExprNode) {
    node.expr.accept(this)
  }

  override fun visit(node: DerefExprNode) {
    node.expr.accept(this)
  }
}