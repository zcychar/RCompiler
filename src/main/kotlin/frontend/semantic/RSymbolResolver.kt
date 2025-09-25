package frontend.semantic

import frontend.ast.*
import frontend.Keyword
import frontend.Literal
import frontend.Punctuation
import utils.CompileError


class RSymbolResolver(val gScope: Scope, val crate: CrateNode) : ASTVisitor<Unit> {
  var currentScope: Scope? = gScope

  fun process() = visit(crate)

  //---------------Const Evaluation-----------------
  fun evaluateConstExpr(expr: ExprNode, expectType: Type): Value {
    return when (expr) {
      is LiteralExprNode -> when (expr.type) {
        Keyword.TRUE -> Value.Bool(true)
        Keyword.FALSE -> Value.Bool(false)
        Literal.INTEGER -> {
          val value = expr.value?.replace("_", "")?.toLong()
            ?: throw CompileError("Semantic:Invalid interger in const expression")
          Value.Int(value)
        }

        Literal.CHAR -> {
          val value = expr.value ?: ""
          if (value.length != 1) {
            throw CompileError("Semantic:Invalid Char $expr")
          }
          Value.Char(value[0])
        }

        Literal.RAW_C_STRING, Literal.C_STRING, Literal.STRING, Literal.RAW_STRING -> Value.Str(
          expr.value ?: ""
        )

        else -> throw CompileError("Semantic:Unsupported literal type $expr")
      }

      is PathExprNode -> {
        val name = expr.seg1.name ?: throw CompileError("Semantic:Const path with no identified found")
        val symbol = currentScope?.resolve(name, Namespace.VALUE)
          ?: throw CompileError("Semantic: cannot find value $name in this scope")
        when (symbol) {
          is Constant -> {
            val resolvedConst = resolveConst(name)
            resolvedConst.value ?: throw CompileError("Semantic:constant $name has no value")
          }

          is Struct -> {
            if (symbol.type.fields.isNotEmpty()) {
              throw CompileError("Semantic:paths to non-unit struct is not allowed to be const")
            }
            Value.Struct(symbol.type, emptyMap())
          }

          else -> throw CompileError("Semantic:Invalid path $name in const expression")
        }
      }

      is GroupedExprNode -> evaluateConstExpr(expr.expr, expectType)
      is StructExprNode -> {
        (expr.path as PathExprNode).seg2
          ?: throw CompileError("Semantic:${expr.path} is not a SIMPLE type path")
        val structType = resolveType((expr.path).seg1) as? StructType
          ?: throw CompileError("Semantic:Path ${expr.path.seg1.name} does not resolve to a struct type")
        val fields = mutableMapOf<String, Value>()
        expr.fields.forEach { fieldNode ->
          val fieldType = structType.fields[fieldNode.id] ?: throw CompileError("Semantic:")
          val fieldExpr = fieldNode.expr ?: PathExprNode(TypePathNode(fieldNode.id, null), null)
          val fieldNames = expr.fields.map { it.id }.toSet()
          structType.fields.keys.forEach {
            if (it !in fieldNames) throw CompileError("Semantic:Field  $it is missing in initializer for struct $structType")
          }
        }
        Value.Struct(structType, fields)
      }

      is FieldAccessExprNode -> {
        val inner_expr = evaluateConstExpr(expr.expr, ErrorType)
        if (inner_expr !is Value.Struct) throw CompileError("Semantic:Invalid field to a non-struct const value")
        inner_expr.fields[expr.id]
          ?: throw CompileError("Semantic:Const struct ${inner_expr.type.name} has no field ${expr.id}")
      }

      is ArrayExprNode -> {
        val arrayElementType = if (expectType is ArrayType) expectType.elementType else ErrorType
        if (expr.elements != null) {
          val elements = expr.elements.map { elementExpr ->
            evaluateConstExpr(elementExpr, arrayElementType)
          }
          val elementType = if (elements.isNotEmpty()) getTypeFromValue(elements[0]) else UnitType
          Value.Array(elements, elementType)
        } else if (expr.repeatOp != null && expr.lengthOp != null) {
          val lengthValue = evaluateConstExpr(expr.lengthOp, UInt32Type)
          val length = (lengthValue as? Value.Int)?.value?.toInt()
            ?: throw CompileError("Semantic:lengthOp of array $lengthValue, not const int")
          if (length < 0) {
            throw CompileError("Semantic:Array length cannot be negative")
          }
          val elemValue = evaluateConstExpr(expr.repeatOp, arrayElementType)
          val elements = List(length) { elemValue }
          val elementType = getTypeFromValue(elemValue)
          Value.Array(elements, elementType)
        } else {
          Value.Array(emptyList(), UnitType)
        }
      }

      is IndexExprNode -> {
        val baseValue = evaluateConstExpr(expr.base, ErrorType)
        val indexValue = evaluateConstExpr(expr.index, UInt32Type)
        if (baseValue !is Value.Array) {
          throw CompileError("Semantic:Indexing of $expr, not a constant array")
        }
        val index = (indexValue as? Value.Int)?.value?.toInt()
          ?: throw CompileError("Semantic:Array index must be an constant int")
        if (index !in 0..baseValue.elements.size) {
          throw CompileError("Semantic:const index $index out of bound for $baseValue")
        }
        baseValue.elements[index]
      }

      is UnaryExprNode -> {
        when (expr.op) {
          Punctuation.MINUS -> {
            val innerValue = evaluateConstExpr(expr.rhs, expectType)
            val value = (innerValue as? Value.Int)?.value
              ?: throw CompileError("Semantic:Unary minus followed by an non-int operand ${expr.rhs}")
            Value.Int(-value)
          }

          Punctuation.BANG -> {
            val innerValue = evaluateConstExpr(expr.rhs, expectType)
            val value = (innerValue as? Value.Bool)?.value
              ?: throw CompileError("Semantic:Unary bang followed by an non-bool operand ${expr.rhs}")
            Value.Bool(!value)
          }

          else -> throw CompileError("Semantic:Unsupported unary $expr")
        }
      }

      is BinaryExprNode -> {
        if (expr.op == Punctuation.AND_AND) {
          val lhs = evaluateConstExpr(expr.lhs, BoolType)
          if (lhs !is Value.Bool) throw CompileError("Semantic: ${expr.op} requires boolean operands.")
          return if (!lhs.value) Value.Bool(false) else {
            val rhs = evaluateConstExpr(expr.rhs, BoolType)
            if (rhs !is Value.Bool) throw CompileError("Semantic: ${expr.op} requires boolean operands.")
            rhs
          }
        }
        if (expr.op == Punctuation.OR_OR) {
          val lhs = evaluateConstExpr(expr.lhs, BoolType)
          if (lhs !is Value.Bool) throw CompileError("Semantic: not boolean operand for ${expr.op}")
          return if (lhs.value) Value.Bool(true) else {
            val rhs = evaluateConstExpr(expr.rhs, BoolType)
            if (rhs !is Value.Bool) throw CompileError("Semantic: ${expr.op} requires boolean operands.")
            rhs
          }
        }
        val lhs = evaluateConstExpr(expr.lhs, expectType)
        val rhs = evaluateConstExpr(expr.rhs, expectType)
        when (lhs) {
          is Value.Int if rhs is Value.Int -> {
            val l = lhs.value
            val r = rhs.value
            when (expr.op) {
              Punctuation.PLUS -> Value.Int(l + r)
              Punctuation.MINUS -> Value.Int(l - r)
              Punctuation.STAR -> Value.Int(l * r)
              Punctuation.SLASH -> {
                if (r == 0L) throw CompileError("Semantic: Div by zero in const expression")
                Value.Int(l / r)
              }

              Punctuation.PERCENT -> {
                if (r == 0L) throw CompileError("Semantic: Div by zero in const expression")
                Value.Int(l % r)
              }

              Punctuation.AMPERSAND -> Value.Int(l and r)
              Punctuation.PIPE -> Value.Int(l or r)
              Punctuation.CARET -> Value.Int(l xor r)
              Punctuation.LESS_LESS -> Value.Int(l shl r.toInt())
              Punctuation.GREATER_GREATER -> Value.Int(l shr r.toInt())
              Punctuation.EQUAL_EQUAL -> Value.Bool(l == r)
              Punctuation.NOT_EQUAL -> Value.Bool(l != r)
              Punctuation.LESS -> Value.Bool(l < r)
              Punctuation.LESS_EQUAL -> Value.Bool(l <= r)
              Punctuation.GREATER -> Value.Bool(l > r)
              Punctuation.GREATER_EQUAL -> Value.Bool(l >= r)
              else -> throw CompileError("Semantic: Unsupported binary operator ${expr.op} for integers")
            }
          }

          is Value.Bool if rhs is Value.Bool -> {
            val l = lhs.value
            val r = rhs.value
            when (expr.op) {
              Punctuation.EQUAL_EQUAL -> Value.Bool(l == r)
              Punctuation.NOT_EQUAL -> Value.Bool(l != r)
              else -> throw CompileError("Semantic: Unsupported binary operator '${expr.op}' for booleans")
            }
          }

          else -> throw CompileError("Semantic: Type mismatch for binary const expression $expr")
        }
      }

      else -> throw CompileError("Semantic:Invalid type of expr ${expr.toString()} in const expression")
    }
  }

  //---------------Type Resolution------------------
  fun resolveType(node: TypeNode): Type = when (node) {
    is ArrayTypeNode -> {
      val elementType = resolveType(node.type)
      val sizeValue = evaluateConstExpr(node.expr, UInt32Type)
      val size = (sizeValue as? Value.Int)?.value?.toInt()
        ?: throw CompileError("Semantic: Array size must be a constant integer.")
      if (size < 0) throw CompileError("Semantic: Array size cannot be negative.")
      ArrayType(elementType, size)
    }

    is RefTypeNode -> RefType(resolveType(node.type), node.hasMut)
    is TypePathNode -> {
      if (node.type != null) {
        SelfType(isMut = false, isRef = false)
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
  //These functions resolve a name to corresponding formatted type
  fun resolveStruct(name: String): StructType {
    val symbol = currentScope?.resolve(name, Namespace.TYPE) as? Struct
      ?: throw CompileError("Semantic:undefined struct type $name")
    when (symbol.resolutionState) {
      ResolutionState.UNRESOLVED -> {}
      ResolutionState.RESOLVING -> throw CompileError("Semantic:recursive dependency of struct $name")
      ResolutionState.RESOLVED -> return symbol.type
    }
    symbol.resolutionState = ResolutionState.RESOLVING
    val node = symbol.node as? StructItemNode ?: throw CompileError("Semantic:invalid prelude usage")
    val fieldsMap = mutableMapOf<String, Type>()
    node.fields.forEach { fieldNode ->
      if (fieldsMap.containsKey(fieldNode.name)) {
        throw CompileError("Semantic: struct node $name have duplicated field ${fieldNode.name}")
      }
      fieldsMap[fieldNode.name] = resolveType(fieldNode.type)
    }
    symbol.type.fields = fieldsMap
    symbol.resolutionState = ResolutionState.RESOLVED
    return symbol.type
  }

  fun resolveEnum(name: String): EnumType {
    val symbol = currentScope?.resolve(name, Namespace.TYPE) as? Enum
      ?: throw CompileError("Semantic:undefined enumeration type $name")
    when (symbol.resolutionState) {
      ResolutionState.UNRESOLVED -> {}
      ResolutionState.RESOLVING -> throw CompileError("Semantic:recursive dependency of enum $name")
      ResolutionState.RESOLVED -> return symbol.type
    }
    symbol.resolutionState = ResolutionState.RESOLVING
    val node = symbol.node as? EnumItemNode ?: throw CompileError("Semantic:invalid prelude usage")
    val variantSet = node.variants.toSet()
    if (variantSet.size != node.variants.size) {
      throw CompileError("Semantic: senum node $name have duplicated variant")
    }
    symbol.type.variants = variantSet
    node.variants.forEach { variantName ->
      currentScope?.declare(
        Constant(
          name = variantName, node = node, type = symbol.type, resolutionState = ResolutionState.RESOLVED
        ), Namespace.VALUE
      )
    }
    symbol.resolutionState = ResolutionState.RESOLVED
    return symbol.type
  }


  fun resolveConst(name: String): Constant {
    val symbol = currentScope?.resolve(name, Namespace.VALUE) as? Constant
      ?: throw CompileError("Semantic:undefined const type $name")
    when (symbol.resolutionState) {
      ResolutionState.UNRESOLVED -> {}
      ResolutionState.RESOLVING -> throw CompileError("Semantic:recursive dependency of constant $name")
      ResolutionState.RESOLVED -> return symbol
    }
    symbol.resolutionState = ResolutionState.RESOLVING
    val node =
      symbol.node as? ConstItemNode ?: throw CompileError("Semantic:invalid prelude usage")//TODO:CHECK PRELUDE
    symbol.type = resolveType(node.type)
    if (node.expr != null) {
      symbol.value = evaluateConstExpr(node.expr, symbol.type)
    } else {
      throw CompileError("Semantic: Constant $name does not have an initializer")
    }
    symbol.resolutionState = ResolutionState.RESOLVED
    return symbol
  }

  fun resolveAssociates(items: List<ItemNode>): Map<String, Symbol> {
    val associates = mutableMapOf<String, Symbol>()
    items.forEach { it ->
      when (it) {
        is FunctionItemNode -> {
          val function = Function(
            name = it.name,
            node = it,
            selfParam = if (it.selfParam != null) SelfType(
              it.selfParam.hasMut,
              it.selfParam.hasBorrow
            ) else null,
            params = it.funParams.map {
              when (it.pattern) {
                is IdentifierPatternNode -> {
                  if (it.pattern.hasRef) {
                    throw CompileError("Semantic:ref in pattern is forbidden")
                  }
                  if (it.pattern.subPattern != null) {
                    throw CompileError("Semantic:invalid function pattern $it")
                  }
                  Variable(it.pattern.id, resolveType(it.type), it.pattern.hasMut)
                }

                else -> throw CompileError("Semantic:invalid function pattern $it")
              }
            },
            returnType = resolveType(it.returnType)
          )
          associates.put(function.name, function)
            ?: throw CompileError("Semantic: duplicated associated item $it in trait/implementation")
        }

        is ConstItemNode -> {
          val const = Constant(
            name = it.name,
            node = it,
            type = resolveType(it.type),
            resolutionState = ResolutionState.RESOLVED
          )
          if (it.expr != null) const.value = evaluateConstExpr(it.expr, const.type)
          associates.put(const.name, const)
            ?: throw CompileError("Semantic: duplicated associated item $it in trait/implementation")
        }

        else -> throw CompileError("Semantic: invalid associate item $it of trait/implementation")
      }
    }
    return associates
  }

  //------------------Visitors----------------------
  override fun visit(node: CrateNode) {
    currentScope = node.scope
    node.items.forEach { it.accept(this) }
    currentScope = currentScope?.parentScope()
  }

  override fun visit(node: FunctionItemNode) {
    val symbol = (currentScope?.resolve(node.name, Namespace.VALUE)) as? Function
      ?: throw CompileError("Semantic:undefined function $node.name")
    symbol.params = node.funParams.map {
      when (it.pattern) {
        is IdentifierPatternNode -> {
          if (it.pattern.hasRef) {
            throw CompileError("Semantic:ref in pattern is forbidden")
          }
          if (it.pattern.subPattern != null) {
            throw CompileError("Semantic:invalid function pattern $it")
          }
          Variable(it.pattern.id, resolveType(it.type), it.pattern.hasMut)
        }

        else -> throw CompileError("Semantic:invalid function pattern $it")
      }
    }
    symbol.returnType = resolveType(node.returnType)
    if (node.selfParam != null) {
      throw CompileError("Semantic: 'self' parameter used outside of an impl or trait block.")
    }
    currentScope = node.body?.scope ?: return
    node.body.stmts.forEach { it.accept(this) }
    currentScope = currentScope?.parentScope()
  }

  override fun visit(node: StructItemNode) {
    resolveStruct(node.name)
  }

  override fun visit(node: EnumItemNode) {
    resolveEnum(node.name)
  }

  override fun visit(node: TraitItemNode) {
    val symbol = (currentScope?.resolve(node.name, Namespace.TYPE)) as? Trait
      ?: throw CompileError("Semantic: undefined trait $node")
    symbol.type.associatedItems = resolveAssociates(node.items)
  }

  override fun visit(node: ImplItemNode) {
    if (node.name != null) {
      currentScope?.resolve(node.name, Namespace.TYPE) as? Trait
        ?: throw CompileError("Semantic: implementing an invalid trait ${node.name}")
    }
    val type = resolveType(node.type)
    if (type !is StructType && type !is EnumType) {
      throw CompileError("Semantic: implementing an invalid type ${node.type}")
    }
    currentScope = node.scope
    resolveAssociates(node.items).forEach { it ->
      if (it.value is Constant && (it.value as Constant).value == null) {
        throw CompileError("Semantic: Invalid null constant in impl $node")
      }
      currentScope?.declare(it.value, Namespace.VALUE)
    }
    currentScope = currentScope?.parentScope()
  }

  override fun visit(node: ConstItemNode) {
    resolveConst(node.name)
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