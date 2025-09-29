package frontend.semantic

import frontend.Keyword
import frontend.Literal
import frontend.Punctuation
import frontend.TokenType
import frontend.assignOp
import frontend.ast.*
import frontend.binaryOp
import utils.CompileError
import java.nio.file.Path
import java.sql.Ref
import kotlin.math.PI
import kotlin.math.exp

open class RSemanticChecker(val gScope: Scope, val crate: CrateNode) : ASTVisitor<Type> {
    var currentScope: Scope? = gScope

    val breakStack: MutableList<Type> = mutableListOf()

    val returnStack: MutableList<Type> = mutableListOf()


    open fun process() = visit(crate)

    fun autoDeref(type: Type): Type {
        return if (type is RefType) type.baseType
        else type

    }

    fun assignable(from: Type, to: Type): Boolean {
        return from == to || isInt(from) && isInt(to) || from is ArrayType && to is ArrayType && assignable(
            from.elementType, to.elementType
        ) && from.size == to.size || from is RefType && to is RefType && (!to.isMutable || from.isMutable) && assignable(
            from.baseType, to.baseType
        )
    }

    //return : isMutable
    fun checkPlaceContext(node: ExprNode): Boolean {
        when (node) {
            is PathExprNode -> {
                if (node.seg2 != null || node.seg1.name == null) {
                    throw CompileError("Semantic: expect identifier, met path $node")
                }
                val variable = currentScope?.resolveVariable(node.seg1.name)
                if (variable != null) return variable.isMutable
                val item = currentScope?.resolve(node.seg1.name, Namespace.VALUE)
                if (item != null) throw CompileError("Semantic: encounter an item in checking place expression, name ${node.seg1.name}")
                throw CompileError("Semantic: missing path expr $node")
            }

            is FieldAccessExprNode -> {
                val baseIsMutable = checkPlaceContext(node.expr)
                var baseType = node.expr.accept(this)
                baseType = autoDeref(baseType)
                when (baseType) {
                    is StructType -> {
                        if (!baseType.fields.containsKey(node.id)) {
                            throw CompileError("Semantic: missing access field ${node.id} of type $node")
                        }
                        return baseIsMutable
                    }

                    else -> throw CompileError("Semantic: field access to an invalid type $baseType")
                }
            }

            is GroupedExprNode -> {
                return checkPlaceContext(node.expr)
            }

            is BorrowExprNode -> {
                val baseIsMutable = checkPlaceContext(node.expr)
                if (!baseIsMutable && node.isMut) {
                    throw CompileError("Semantic: cannot create mut reference to a non-mut value at $node")
                }
                return node.isMut
            }

            is DerefExprNode -> {
                checkPlaceContext(node.expr)
                val baseType = node.expr.accept(this)
                if (baseType !is RefType) {
                    throw CompileError("Semantic: pointer type is not allowed")
                }
                return baseType.isMutable
            }

            is IndexExprNode -> {
                val baseIsMutable = checkPlaceContext(node.base)
                val baseType = node.base.accept(this)
                if (autoDeref(baseType) !is ArrayType) {
                    throw CompileError("Semantic: index expression base type not array, but $baseType")
                }
                return baseIsMutable
            }

            else -> throw CompileError("Semantic: encountering an value expression $node")
        }
    }

    fun checkMutContext(node: ExprNode) {
        val isMutable = checkPlaceContext(node)
        if (!isMutable) {
            throw CompileError("Semantic: require mut context, met $node")
        }
    }

    fun getPatternBind(pattern: PatternNode, type: Type): Variable {
        when (pattern) {
            is IdentifierPatternNode -> {
                if (pattern.subPattern != null) {
                    throw CompileError("Semantic: subpattern is not allowed in pattern binding")
                }
                if (pattern.hasRef) {
                    throw CompileError("Semantic: ref in identifier pattern is not supported")
                }
                return Variable(pattern.id, type, pattern.hasMut)
            }

            else -> throw CompileError("Semantic: other pattern type is no longer supported")
        }
    }

    override fun visit(node: CrateNode): Type {
        currentScope = node.scope
        node.items.forEach { it.accept(this) }
        currentScope = currentScope?.parentScope()
        if (breakStack.isNotEmpty()) {
            throw CompileError("Semantic: break expression more than needed")
        }
        return UnitType
    }

    override fun visit(node: FunctionItemNode): Type {
        if (node.body == null) {
            throw CompileError("Semantic: invalid missing function body")
        }
        val function = currentScope?.resolve(node.name, Namespace.VALUE) as? Function
            ?: throw CompileError("Semantic: missing function declaration ${node.name}")
        currentScope = node.body.scope
        returnStack.add(function.returnType)
        node.funParams.forEach { it ->
            currentScope?.declareVariable(getPatternBind(it.pattern, it.type.accept(this)))
        }
        val returnType = node.body.accept(this)
        if (!assignable(returnType, returnStack.last())) {
            throw CompileError("Semantic: function return type mismatch, expect ${returnStack.last()}, met $returnType")
        }
        currentScope = currentScope?.parentScope()
        returnStack.removeLast()
        return UnitType
    }

    override fun visit(node: StructItemNode): Type = UnitType
    override fun visit(node: EnumItemNode): Type = UnitType

    override fun visit(node: TraitItemNode): Type = UnitType

    override fun visit(node: ImplItemNode): Type {
        currentScope = node.scope
        currentScope?.declareVariable(Variable("self", node.type.accept(this), false))
        node.items.forEach { it.accept(this) }
        currentScope = currentScope?.parentScope()
        return UnitType
    }

    override fun visit(node: ConstItemNode): Type = UnitType

    override fun visit(node: BlockExprNode): Type {
        currentScope = node.scope
        val types = node.stmts.map { it.accept(this) }
        val retType = if (node.hasFinal()) {
            types.last()
        } else if (node.stmts.last() is ExprStmtNode && (node.stmts.last() as ExprStmtNode).expr is ReturnExprNode) {
            NeverType
        } else UnitType
        if (currentScope?.kind == ScopeKind.BLOCK) {
            currentScope = currentScope?.parentScope()
        }
        return retType
    }

    override fun visit(node: LoopExprNode): Type {
        node.expr.accept(this)
        if (breakStack.isEmpty()) {
            throw CompileError("Semantic: encounter infinite loop")
        }
        return breakStack.removeLast()
    }

    override fun visit(node: WhileExprNode): Type {
        if (node.conds.isNotEmpty()) {
            if (node.conds.size != 1) {
                throw CompileError("Semantic: let binding in while condition is unsupported")
            }
            val condType = node.conds[0].accept(this)
            if (condType !is BoolType) {
                throw CompileError("Semantic: while condition require bool type, met $condType")
            }
        }
        node.expr.accept(this)
        return UnitType
    }

    override fun visit(node: BreakExprNode): Type {
        val type = node.expr?.accept(this) ?: NeverType
        breakStack.add(type)
        return type
    }

    override fun visit(node: ReturnExprNode): Type {
        val type = node.expr?.accept(this) ?: NeverType
        if (returnStack.isEmpty()) {
            throw CompileError("Semantic: encounter invalid return expression")
        }
        val expectType = returnStack.last()
        if (!assignable(type, expectType)) {
            throw CompileError("Semantic: return expression type mismatch, expect $expectType, met $type")
        }
        return NeverType
    }

    override fun visit(node: ContinueExprNode): Type = NeverType

    override fun visit(node: IfExprNode): Type {
        if (node.conds.isNotEmpty()) {
            if (node.conds.size > 1) {
                throw CompileError("Semantic: let bindings in condition is forbidden")
            }
            val condType = node.conds[0].accept(this)
            if (condType !is BoolType) {
                throw CompileError("Semantic: require bool type condition, met $condType")
            }
        }
        val bodyType = node.expr.accept(this)
        val elseType = node.elseExpr?.accept(this)
        if (elseType == null) {
            if (!assignable(bodyType, UnitType)) {
                throw CompileError("Semantic: non-unit if expression without else branch is not allowed")
            }
            return bodyType
        } else {
            if (bodyType != elseType) {
                throw CompileError("Semantic: different type for if-else branches, $bodyType and $elseType")
            }
            return bodyType
        }
    }

    override fun visit(node: FieldAccessExprNode): Type {
        val receiverType = autoDeref(node.expr.accept(this))
        if (receiverType !is StructType) {
            throw CompileError("Semantic: field access to a non-struct type $receiverType")
        }
        val symbol = currentScope?.resolve(receiverType.name, Namespace.TYPE) as? Struct
            ?: throw CompileError("Semantic: field access to a missing struct ${receiverType.name}")
        return symbol.type.fields[node.id]
            ?: throw CompileError("Semantic: field access to a missing field ${node.id} in ${receiverType.name}")
    }

    override fun visit(node: MethodCallExprNode): Type {
        var receiverType = node.expr.accept(this)
        receiverType = autoDeref(receiverType)
        if (node.pathSeg.name == null) {
            throw CompileError("Semantic: invalid 'self' in method call")
        }
        val methodName = node.pathSeg.name
        BuiltInMethods.findMethod(receiverType, methodName)?.let {
            if (node.params.isNotEmpty()) {
                throw CompileError("Semantic: method call of $methodName on $receiverType param size mismatch")
            }
            return it.returnType
        }
        val symbol = when (receiverType) {
            is StructType -> {
                currentScope?.resolve(receiverType.name, Namespace.TYPE) as? Struct
                    ?: throw CompileError("Semantic: cannot find struct ${receiverType.name} in method call")
            }

            is EnumType -> {
                currentScope?.resolve(receiverType.name, Namespace.TYPE) as? Enum
                    ?: throw CompileError("Semantic: cannot find enum ${receiverType.name} in method call")
            }

            else -> throw CompileError("Semantic: invalid receiver type $receiverType in method call")
        }
        val method = symbol.methods[methodName]
            ?: throw CompileError("Semantic: cannot find method $methodName in symbol ${symbol.name}")
        if (method.selfParam != null && !assignable(receiverType, method.self!!)) {
            throw CompileError("Semantic: self param of method $methodName in ${symbol.name} type mismatch, met $receiverType")
        }
        node.params.zip(method.params).forEach { (argument, expect) ->
            val type = argument.accept(this)
            if (!assignable(type, expect.type)) {
                throw CompileError("Semantic: method call of $methodName param type mismatch on $expect")
            }
        }
        return method.returnType
    }

    override fun visit(node: CallExprNode): Type {
        if (node.expr !is PathExprNode) {
            throw CompileError("Semantic: leading expression for call must be path expression")
        }
        if (node.expr.seg2 == null) {
            if (node.expr.seg1.name == null) {
                throw CompileError("Semantic: direct call of 'self' is not possible")
            } else {
                val identifier = node.expr.seg1.name
                val function = currentScope?.resolve(identifier, Namespace.VALUE) as? Function
                    ?: throw CompileError("Semantic: cannot find function $identifier but called")
                if (node.params.size != function.params.size) {
                    throw CompileError("Semantic: call of function $identifier param size mismatch")
                }
                node.params.zip(function.params).forEach { (argument, expect) ->
                    val type = argument.accept(this)
                    if (!assignable(type, expect.type)) {
                        throw CompileError("Semantic: call of function $identifier param type mismatch on $expect")
                    }
                }
                return function.returnType
            }
        } else {
            val symbol = if (node.expr.seg1.name == null) {
                if (node.expr.seg1.type == Keyword.SELF || currentScope?.kind != ScopeKind.IMPL) {
                    throw CompileError("Semantic: invalid 'self' in call expression")
                }
                val name = currentScope?.resolveVariable("self")?.name
                    ?: throw CompileError("Semantic: can not find type for self")
                currentScope?.resolve(name, Namespace.TYPE)
                    ?: throw CompileError("Semantic: can not find type for ${name}")
            } else {
                currentScope?.resolve(node.expr.seg1.name, Namespace.TYPE)
                    ?: throw CompileError("Semantic: can not find type for ${node.expr.seg1.name}")
            }
            if (node.expr.seg2.type != null) throw CompileError("Semantic: encounter unexpected self")
            val identifier = node.expr.seg2.name
            when (symbol) {
                is Enum, is Struct -> {
                    if (symbol.associateItems[identifier] is Function && !symbol.methods.containsKey(identifier)) {
                        val function = symbol.associateItems[identifier] as Function
                        if (node.params.size != function.params.size) {
                            throw CompileError("Semantic: call of function $identifier param size mismatch")
                        }
                        node.params.zip(function.params).forEach { (argument, expect) ->
                            val type = argument.accept(this)
                            if (!assignable(type, expect.type)) {
                                throw CompileError("Semantic: call of function $identifier param type mismatch on $expect")
                            }
                        }
                        return function.returnType
                    } else {
                        throw CompileError("Semantic: can not find associate function $identifier in type ${symbol.name}")
                    }

                }

                else -> throw CompileError("Semantic: unexpected base type for  path $node")
            }
        }
    }

    override fun visit(node: CondExprNode): Type {
        return node.expr.accept(this)
    }

    override fun visit(node: LiteralExprNode): Type {
        return when (node.type) {
            Literal.CHAR -> CharType
            Literal.STRING,
            Literal.C_STRING,
            Literal.RAW_STRING,
            Literal.RAW_C_STRING -> StrType

            Literal.INTEGER -> UInt32Type
            Keyword.TRUE, Keyword.FALSE -> BoolType
            else -> throw CompileError("Semantic: encounter invalid literal expression $node")
        }
    }

    override fun visit(node: IdentifierExprNode): Type {
        throw CompileError("Semantic: identifier type is not supported")
    }

    override fun visit(node: PathExprNode): Type {
        if (node.seg2 == null) {
            if (node.seg1.name == null) {
                return currentScope?.resolveVariable("self")?.type
                    ?: throw CompileError("Semantic: can not find type for self")
            } else {
                val identifier = node.seg1.name
                val variable = currentScope?.resolveVariable(identifier)
                if (variable != null) return variable.type
                val symbol = currentScope?.resolve(identifier, Namespace.VALUE) as? Constant
                    ?: throw CompileError("Semantic: cannot find type for $identifier")
                return symbol.type
            }
        } else {
            val symbol = if (node.seg1.name == null) {
                val name = currentScope?.resolveVariable("self")?.name
                    ?: throw CompileError("Semantic: can not find type for self")
                currentScope?.resolve(name, Namespace.TYPE)
                    ?: throw CompileError("Semantic: can not find type for ${name}")
            } else {
                currentScope?.resolve(node.seg1.name, Namespace.TYPE)
                    ?: throw CompileError("Semantic: can not find type for ${node.seg1.name}")
            }
            if (node.seg2.type != null) throw CompileError("Semantic: encounter unexpected self")
            val identifier = node.seg2.name
            return when (symbol) {
                is Enum -> {
                    if (symbol.type.variants.contains(identifier)) symbol.type
                    else throw CompileError("Semantic: cannot find variant $identifier in enum ${symbol.name}")
                }

                is Struct -> {
                    (symbol.associateItems[identifier] as? Constant)?.type
                        ?: throw CompileError("Semantic: cannot find constant associate item $identifier for struct ${symbol.name}")
                }

                else -> throw CompileError("Semantic: unexpected base type for  path $node")
            }
        }
    }

    override fun visit(node: ArrayExprNode): Type {
        if (node.lengthOp != null && node.repeatOp != null) {
            val elementType = node.repeatOp.accept(this)
            if (node.evaluatedSize < 0) {
                throw CompileError("Semantic: array $node size not resolved")
            }
            return ArrayType(elementType, node.evaluatedSize.toInt())
        } else {
            if (node.elements.isEmpty()) {
                return ArrayType(UnitType, 0)
            }
            val elementType = node.elements.first().accept(this)
            node.elements.forEach {
                if (it.accept(this) != elementType) {
                    throw CompileError("Semantic: different types in array expression")
                }
            }
            return ArrayType(elementType, node.elements.size)
        }
    }

    override fun visit(node: IndexExprNode): Type {
        val baseType = node.base.accept(this)
        val indexType = node.index.accept(this)
        if (autoDeref(baseType) !is ArrayType) {
            throw CompileError("Semantic: non-array type $baseType in index expression")
        }
        if (!isInt(indexType)) {
            throw CompileError("Semantic: non-int index type $indexType in index expression")
        }
        return (autoDeref(baseType) as ArrayType).elementType
    }

    override fun visit(node: StructExprNode): Type {
        if (node.path !is PathExprNode || node.path.seg2 != null || node.path.seg1.name == null) {
            throw CompileError("Semantic: unsupported path in struct expression ${node.path}")
        }
        val symbol = currentScope?.resolve(node.path.seg1.name, Namespace.TYPE) as? Struct
            ?: throw CompileError("Semantic: missing struct declaration ${node.path}")
        node.fields.forEach {
            val expectType = symbol.type.fields[it.id]
            if (expectType == null) {
                throw CompileError("Semantic: missing field ${it.id} in struct ${node.path.seg1.name}")
            }
            if (it.expr == null) {
                throw CompileError("Semantic: missing value of field ${it.id} in struct ${node.path.seg1.name}")
            }
            val type = it.expr.accept(this)
            if (!assignable(type, expectType)) {
                throw CompileError("Semantic: type mismatch of field ${it.id} in struct ${node.path.seg1.name}, expect $expectType, met $type")
            }
        }
        symbol.type.fields.forEach { it ->
            if (node.fields.find { field ->
                    field.id == it.key
                } == null) {
                throw CompileError("Semantic: missing field ${it.key} in struct expression ${node.path.seg1.name}")
            }
        }
        return symbol.type
    }

    override fun visit(node: UnderscoreExprNode): Type {
        throw CompileError("Semantic: underscore expression is not supported")
    }

    override fun visit(node: UnaryExprNode): Type {
        val rightType = node.rhs.accept(this)
        when (node.op) {
            Punctuation.MINUS -> {
                if (rightType !is Int32Type) {
                    throw CompileError("Semantic: arithmetic type mismatch,$rightType, operator ${node.op}")
                }
                return rightType
            }

            Punctuation.BANG -> {
                if (rightType !is BoolType) {
                    throw CompileError("Semantic: arithmetic type mismatch,$rightType, operator ${node.op}")
                }
                return rightType
            }

            else -> throw CompileError("Semantic: invalid unary operator ${node.op}")
        }
    }

    override fun visit(node: BinaryExprNode): Type {
        when (node.op) {
            in assignOp -> {
                checkMutContext(node.lhs)
                val leftType = node.lhs.accept(this)
                val rightType = node.rhs.accept(this)
                when (node.op) {
                    Punctuation.EQUAL -> {
                        if (!assignable(rightType, leftType)) {
                            throw CompileError("Semantic: assignment type mismatch, $leftType and $rightType")
                        }
                    }

                    Punctuation.PLUS_EQUAL, Punctuation.MINUS_EQUAL, Punctuation.STAR_EQUAL, Punctuation.SLASH_EQUAL, Punctuation.PERCENT_EQUAL, Punctuation.CARET_EQUAL, Punctuation.AND_EQUAL, Punctuation.OR_EQUAL -> {
                        if (!isInt(leftType) || !isInt(rightType) || rightType != leftType) {
                            throw CompileError("Semantic: assignment type mismatch, $leftType and $rightType")
                        }
                    }

                    Punctuation.LESS_LESS_EQUAL, Punctuation.GREATER_GREATER_EQUAL -> {
                        if (!isInt(leftType) || !isInt(rightType)) {
                            throw CompileError("Semantic: assignment type mismatch, $leftType and $rightType")
                        }
                    }

                    else -> throw CompileError("Semantic: expect assignment expression, met $node")
                }
                return UnitType
            }

            in binaryOp -> {
                val leftType = node.lhs.accept(this)
                val rightType = node.rhs.accept(this)
                when (node.op) {
                    Punctuation.PLUS, Punctuation.MINUS, Punctuation.STAR, Punctuation.SLASH, Punctuation.PERCENT -> {
                        if (!isInt(leftType) || !isInt(rightType) || rightType != leftType) {
                            throw CompileError("Semantic: arithmetic type mismatch, $leftType and $rightType, operator ${node.op}")
                        }
                        return leftType
                    }

                    Punctuation.EQUAL_EQUAL, Punctuation.NOT_EQUAL -> {
                        if (leftType != rightType || (!isInt(leftType) && leftType !is BoolType && leftType !is CharType && leftType !is EnumType)) {
                            throw CompileError("Semantic: arithmetic type mismatch, $leftType and $rightType, operator ${node.op}")
                        }
                        return BoolType
                    }

                    Punctuation.LESS, Punctuation.LESS_EQUAL, Punctuation.GREATER, Punctuation.GREATER_EQUAL -> {
                        if (leftType != rightType || (!isInt(leftType) && leftType !is BoolType && leftType !is CharType)) {
                            throw CompileError("Semantic: arithmetic type mismatch, $leftType and $rightType, operator ${node.op}")
                        }
                        return BoolType
                    }

                    Punctuation.AND_AND, Punctuation.OR_OR -> {
                        if ((!isInt(leftType) && leftType !is BoolType && leftType !is CharType && leftType !is EnumType)) {
                            throw CompileError("Semantic: arithmetic type mismatch, $leftType and $rightType, operator ${node.op}")
                        }
                        return leftType
                    }

                    Punctuation.AMPERSAND, Punctuation.PIPE, Punctuation.CARET -> {
                        if (!isInt(leftType) || !isInt(rightType) || rightType != leftType) {
                            throw CompileError("Semantic: arithmetic type mismatch, $leftType and $rightType, operator ${node.op}")
                        }
                        return leftType
                    }

                    Punctuation.LESS_LESS, Punctuation.GREATER_GREATER -> {
                        if (!isInt(leftType) || !isInt(rightType)) {
                            throw CompileError("Semantic: arithmetic type mismatch, $leftType and $rightType, operator ${node.op}")
                        }
                        return leftType
                    }

                    else -> throw CompileError("Semantic: invalid binary operator ${node.op}")
                }
            }

            else -> throw CompileError("Semantic: invalid binary operator ${node.op}")
        }
    }

    override fun visit(node: ItemStmtNode): Type {
        return node.item.accept(this)
    }

    override fun visit(node: LetStmtNode): Type {
        val type = node.type?.accept(this) ?: throw CompileError("Semantic: Invalid missing type of $node")
        val exprType =
            node.expr?.accept(this) ?: throw CompileError("Semantic: Invalid un-initialized let statement of $node")
        if (!assignable(exprType, type)) {
            throw CompileError("Semantic: let statement type mismatch , expect $type, met $exprType")
        }
        currentScope?.declareVariable(getPatternBind(node.pattern, type))
        return type
    }

    override fun visit(node: ExprStmtNode): Type {
        return node.expr.accept(this)
    }

    override fun visit(node: NullStmtNode): Type {
        return UnitType
    }

    override fun visit(node: IdentifierPatternNode): Type {
        return UnitType
    }

    override fun visit(node: RefPatternNode): Type {
        throw CompileError("Semantic: ref pattern is forbidden")
    }

    override fun visit(node: WildcardPatternNode): Type {
        throw CompileError("Semantic: wildcard pattern is forbidden")
    }

    override fun visit(node: TypePathNode): Type {
        return if (node.name != null) {
            when (val type = currentScope?.resolve(node.name, Namespace.TYPE)) {
                is BuiltIn -> type.type
                is Enum -> type.type
                is Struct -> type.type
                else -> throw CompileError("Semantic: can not find type for $node")
            }
        } else {
            return currentScope?.resolveVariable("self")?.type
                ?: throw CompileError("Semantic: can not find type for self")
        }
    }

    override fun visit(node: RefTypeNode): Type {
        return RefType(node.type.accept(this), node.hasMut)
    }

    override fun visit(node: ArrayTypeNode): Type {
        val type = node.type.accept(this)
        if (node.evaluatedSize < 0) throw CompileError("Semantic: encounter unresolved array type $node")
        return ArrayType(type, node.evaluatedSize.toInt())
    }

    override fun visit(node: UnitTypeNode): Type {
        return UnitType
    }

    override fun visit(node: GroupedExprNode): Type {
        return node.expr.accept(this)
    }

    override fun visit(node: CastExprNode): Type {
        val expr = node.expr.accept(this)
        val target = node.targetType.accept(this)
        if ((expr == target) || (isInt(expr) && isInt(target))) {
            return target
        } else {
            throw CompileError("Semantic: Invalid typecast $node")
        }
    }

    override fun visit(node: BorrowExprNode): Type {
        return RefType(node.expr.accept(this), node.isMut)
    }

    override fun visit(node: DerefExprNode): Type {
        val innerType = node.expr.accept(this)
        if (innerType !is RefType) throw CompileError("Semantic: pointer type is not allowed")
        return innerType.baseType
    }
}