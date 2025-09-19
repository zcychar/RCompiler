package frontend.semantic

import com.sun.jdi.Value
import frontend.AST.*
import frontend.ErrorToken
import frontend.Identifier
import frontend.Keyword
import frontend.Literal
import frontend.Punctuation
import utils.CompileError


class RSymbolResolver(val gScope: Scope, val crate: CrateNode) : ASTVisitor<Unit> {
  var currentScope: Scope? = gScope
  var currentSelfType: Type? = null

  fun process() = visit(crate)

  //---------------Const Evaluation-----------------
  fun evaluateConstExpr(expr: ExprNode, expectType: Type): ConstValue {
    return when (expr) {
      is LiteralExprNode -> when (expr.type) {
        Keyword.TRUE -> ConstValue.Bool(true)
        Keyword.FALSE -> ConstValue.Bool(false)
        Literal.INTEGER -> {
          val value =
            expr.value?.replace("_", "")?.toLong()
              ?: throw CompileError("Semantic:Invalid interger in const expression")
          ConstValue.Int(value)
        }

        Literal.CHAR -> {
          val value = expr.value ?: ""
          if (value.length != 1) {
            throw CompileError("Semantic:Invalid Char $expr")
          }
          ConstValue.Char(value[0])
        }

        Literal.RAW_C_STRING, Literal.C_STRING, Literal.STRING, Literal.RAW_STRING -> ConstValue.Str(
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
            ConstValue.Struct(symbol.type, emptyMap())
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
        val fields = mutableMapOf<String, ConstValue>()
        expr.fields.forEach { fieldNode ->
          val fieldType = structType.fields[fieldNode.id] ?: throw CompileError("Semantic:")
          val fieldExpr = fieldNode.expr ?: PathExprNode(TypePathNode(fieldNode.id, null), null)
          val fieldNames = expr.fields.map { it.id }.toSet()
          structType.fields.keys.forEach {
            if (it !in fieldNames) throw CompileError("Semantic:Field  $it is missing in initializer for struct $structType")
          }
        }
        ConstValue.Struct(structType, fields)
      }

      is FieldAccessExprNode -> {
        val inner_expr = evaluateConstExpr(expr.expr, ErrorType)
        if (inner_expr !is ConstValue.Struct) throw CompileError("Semantic:Invalid field to a non-struct const value")
        inner_expr.fields[expr.id]
          ?: throw CompileError("Semantic:Const struct ${inner_expr.type.name} has no field ${expr.id}")
      }

      is ArrayExprNode -> {
        val arrayElementType = if (expectType is ArrayType) expectType.elementType else ErrorType
        if (expr.elements != null) {
          val elements = expr.elements.map { elementExpr ->
            evaluateConstExpr(elementExpr, arrayElementType)
          }
          val elementType = if (elements.isNotEmpty()) getTypeFromConst(elements[0]) else UnitType
          ConstValue.Array(elements, elementType)
        } else if (expr.repeatOp != null && expr.lengthOp != null) {
          val lengthValue = evaluateConstExpr(expr.lengthOp, UInt32Type)
          val length = (lengthValue as? ConstValue.Int)?.value?.toInt()
            ?: throw CompileError("Semantic:lengthOp of array $lengthValue, not const int")
          if (length < 0) {
            throw CompileError("Semantic:Array length cannot be negative")
          }
          val elemValue = evaluateConstExpr(expr.repeatOp, arrayElementType)
          val elements = List(length) { elemValue }
          val elementType = getTypeFromConst(elemValue)
          ConstValue.Array(elements, elementType)
        } else {
          ConstValue.Array(emptyList(), UnitType)
        }
      }

      is IndexExprNode -> {
        val baseValue = evaluateConstExpr(expr.base, ErrorType)
        val indexValue = evaluateConstExpr(expr.index, UInt32Type)
        if (baseValue !is ConstValue.Array) {
          throw CompileError("Semantic:Indexing of $expr, not a constant array")
        }
        val index = (indexValue as? ConstValue.Int)?.value?.toInt()
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
            val value = (innerValue as? ConstValue.Int)?.value
              ?: throw CompileError("Semantic:Unary minus followed by an non-int operand ${expr.rhs}")
            ConstValue.Int(-value)
          }

          Punctuation.BANG -> {
            val innerValue = evaluateConstExpr(expr.rhs, expectType)
            val value = (innerValue as? ConstValue.Bool)?.value
              ?: throw CompileError("Semantic:Unary bang followed by an non-bool operand ${expr.rhs}")
            ConstValue.Bool(!value)
          }

          else -> throw CompileError("Semantic:Unsupported unary $expr")
        }
      }

      is BinaryExprNode -> {
        if (expr.op == Punctuation.AND_AND) {
          val lhs = evaluateConstExpr(expr.lhs, BoolType)
          if (lhs !is ConstValue.Bool) throw CompileError("Semantic: ${expr.op} requires boolean operands.")
          return if (!lhs.value) ConstValue.Bool(false) else {
            val rhs = evaluateConstExpr(expr.rhs, BoolType)
            if (rhs !is ConstValue.Bool) throw CompileError("Semantic: ${expr.op} requires boolean operands.")
            rhs
          }
        }
        if (expr.op == Punctuation.OR_OR) {
          val lhs = evaluateConstExpr(expr.lhs, BoolType)
          if (lhs !is ConstValue.Bool) throw CompileError("Semantic: not boolean operand for ${expr.op}")
          return if (lhs.value) ConstValue.Bool(true) else {
            val rhs = evaluateConstExpr(expr.rhs, BoolType)
            if (rhs !is ConstValue.Bool) throw CompileError("Semantic: ${expr.op} requires boolean operands.")
            rhs
          }
        }
        val lhs = evaluateConstExpr(expr.lhs, expectType)
        val rhs = evaluateConstExpr(expr.rhs, expectType)
        when (lhs) {
          is ConstValue.Int if rhs is ConstValue.Int -> {
            val l = lhs.value
            val r = rhs.value
            when (expr.op) {
              Punctuation.PLUS -> ConstValue.Int(l + r)
              Punctuation.MINUS -> ConstValue.Int(l - r)
              Punctuation.STAR -> ConstValue.Int(l * r)
              Punctuation.SLASH -> {
                if (r == 0L) throw CompileError("Semantic: Div by zero in const expression")
                ConstValue.Int(l / r)
              }

              Punctuation.PERCENT -> {
                if (r == 0L) throw CompileError("Semantic: Div by zero in const expression")
                ConstValue.Int(l % r)
              }

              Punctuation.AMPERSAND -> ConstValue.Int(l and r)
              Punctuation.PIPE -> ConstValue.Int(l or r)
              Punctuation.CARET -> ConstValue.Int(l xor r)
              Punctuation.LESS_LESS -> ConstValue.Int(l shl r.toInt())
              Punctuation.GREATER_GREATER -> ConstValue.Int(l shr r.toInt())
              Punctuation.EQUAL_EQUAL -> ConstValue.Bool(l == r)
              Punctuation.NOT_EQUAL -> ConstValue.Bool(l != r)
              Punctuation.LESS -> ConstValue.Bool(l < r)
              Punctuation.LESS_EQUAL -> ConstValue.Bool(l <= r)
              Punctuation.GREATER -> ConstValue.Bool(l > r)
              Punctuation.GREATER_EQUAL -> ConstValue.Bool(l >= r)
              else -> throw CompileError("Semantic: Unsupported binary operator ${expr.op} for integers")
            }
          }

          is ConstValue.Bool if rhs is ConstValue.Bool -> {
            val l = lhs.value
            val r = rhs.value
            when (expr.op) {
              Punctuation.EQUAL_EQUAL -> ConstValue.Bool(l == r)
              Punctuation.NOT_EQUAL -> ConstValue.Bool(l != r)
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
    currentScope?.declare(symbol, Namespace.VALUE)
    symbol.resolutionState = ResolutionState.RESOLVED
    return symbol.type
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

  override fun visit(node: GroupedExprNode) {
    TODO("Not yet implemented")
  }

  override fun visit(node: CastExprNode) {
    TODO("Not yet implemented")
  }
}