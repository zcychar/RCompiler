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
import kotlin.coroutines.Continuation
import kotlin.math.PI
import kotlin.math.exp

open class RSemanticChecker(val gScope: Scope, val crate: CrateNode) : ASTVisitor<Type> {
    var currentScope: Scope? = gScope
    var currentSelfType: Type = UnitType
    var currentSelfName: String = ""
    val breakStack: MutableList<Type> = mutableListOf()
    val returnStack: MutableList<Type> = mutableListOf()
    open fun process() = visit(crate)
    fun autoDeref(type: Type): Type {
        return if (type is RefType) type.baseType
        else type
    }

    fun assignable(from: Type, to: Type): Boolean {
        return from == to || isInt(from) && isInt(to) && canUnifyInt(
            from, to
        ) || from is ArrayType && to is ArrayType && assignable(
            from.elementType, to.elementType
        ) && from.size == to.size || from is RefType && to is RefType && (!to.isMutable || from.isMutable) && assignable(
            from.baseType, to.baseType
        ) || from is NeverType//TODO:this is not safe
    }

    fun checkPlaceContext(node: ExprNode): Boolean {
        when (node) {
            is PathExprNode -> {
                if (node.seg2 != null) {
                    throw CompileError("Semantic: expected a variable, but found a path expression `$node`")
                }
                if (node.seg1.type == Keyword.SELF_UPPER) {
                    throw CompileError("Semantic: met unexpected `Self`")
                }
                val identifier = node.seg1.name ?: "self"
                val variable = currentScope?.resolveVariable(identifier)
                if (variable != null) return variable.isMutable
                val item = currentScope?.resolve(identifier, Namespace.VALUE)
                if (item != null) throw CompileError("Semantic: cannot assign to `${identifier}` because it is an item (like a function or constant), not a variable")
                throw CompileError("Semantic: cannot find value `${identifier}` in this scope")
            }

            is FieldAccessExprNode -> {
                var baseIsMutable = checkPlaceContext(node.expr)
                var baseType = node.expr.accept(this)
                if (baseType is RefType) {
                    baseIsMutable = baseType.isMutable
                }
                baseType = autoDeref(baseType)
                when (baseType) {
                    is StructType -> {
                        if (!baseType.fields.containsKey(node.id)) {
                            throw CompileError("Semantic: no field `${node.id}` on type `$baseType`")
                        }
                        return baseIsMutable
                    }

                    else -> throw CompileError("Semantic: cannot access field on a non-struct type, found type `$baseType`")
                }
            }

            is GroupedExprNode -> {
                return checkPlaceContext(node.expr)
            }

            is BorrowExprNode -> {
                val baseIsMutable = checkPlaceContext(node.expr)
                if (!baseIsMutable && node.isMut) {
                    throw CompileError("Semantic: cannot create a mutable reference to an immutable value")
                }
                return node.isMut
            }

            is DerefExprNode -> {
                checkPlaceContext(node.expr)
                val baseType = node.expr.accept(this)
                if (baseType !is RefType) {
                    throw CompileError("Semantic: type `$baseType` cannot be dereferenced")
                }
                return baseType.isMutable
            }

            is IndexExprNode -> {
                var baseIsMutable = checkPlaceContext(node.base)
                val baseType = node.base.accept(this)
                if (baseType is RefType) {
                    baseIsMutable = baseType.isMutable
                }
                if (autoDeref(baseType) !is ArrayType) {
                    throw CompileError("Semantic: type `$baseType` cannot be indexed")
                }
                return baseIsMutable
            }

            is LiteralExprNode -> {
                return false
            }

            else -> throw CompileError("Semantic: expression is not assignable")
        }
    }

    fun checkMutContext(node: ExprNode) {
        val isMutable = checkPlaceContext(node)
        if (!isMutable) {
            throw CompileError("Semantic: cannot assign to immutable variable or field")
        }
    }

    fun isMut(node: ExprNode): Boolean {
        try {
            checkPlaceContext(node)
            return true
        } catch (e: Exception) {
            return false
        }
    }

    fun getPatternBind(pattern: PatternNode, type: Type): Variable {
        when (pattern) {
            is IdentifierPatternNode -> {
                if (pattern.subPattern != null) {
                    throw CompileError("Semantic: subpatterns in bindings are not supported (e.g., `let a @ pat = ...`)")
                }
                if (pattern.hasRef) {
                    throw CompileError("Semantic: the `ref` keyword in bindings is not supported")
                }
                return Variable(pattern.id, type, pattern.hasMut)
            }

            else -> throw CompileError("Semantic: unsupported pattern type, only identifier patterns are allowed here")
        }
    }

    override fun visit(node: CrateNode): Type {
        currentScope = node.scope
        node.items.forEach { it.accept(this) }
        currentScope = currentScope?.parentScope()
        if (breakStack.isNotEmpty()) {
            throw CompileError("Semantic: `break` cannot be used outside of a loop")
        }
        return UnitType
    }

    override fun visit(node: FunctionItemNode): Type {
        if (node.body == null) {
            throw CompileError("Semantic: missing function body for `${node.name}`")
        }
        val function = currentScope?.resolve(node.name, Namespace.VALUE) as? Function
            ?: throw CompileError("Semantic: internal error: cannot find function declaration for `${node.name}`")
        currentScope = node.body.scope
        returnStack.add(function.returnType)
        node.funParams.forEach { it ->
            currentScope?.declareVariable(getPatternBind(it.pattern, it.type.accept(this)))
        }
        if (function.selfParam != null) {
            val self = if (function.selfParam!!.isRef) RefType(
                currentSelfType, function.selfParam!!.isMut
            ) else currentSelfType
            currentScope?.declareVariable(Variable("self", self, false))
        }
        val returnType = node.body.accept(this)
        if (returnType !is NeverType && !assignable(returnType, returnStack.last())) {
            throw CompileError("Semantic: mismatched return type for function `${node.name}`. Expected `${returnStack.last()}`, but found `$returnType`")
        }
        currentScope = currentScope?.parentScope()
        returnStack.removeLast()
        node.body.stmts.forEach {
            if (it is ExprStmtNode) {
                if (it.expr is CallExprNode) {
                    if (it.expr.expr is PathExprNode && it.expr.expr.seg2 == null && it.expr.expr.seg1.name == "exit") {
                        if (node.body.stmts.last() != it) {
                            throw CompileError("Semantic: invalid exit() usage in function ${node.name}")
                        }
                        if (node.name != "main" || currentScope?.kind != ScopeKind.GLOBAL) {
                            throw CompileError("Semantic: invalid exit() usage in main function")
                        }
                    }
                }
            }
        }
        if (node.name == "main" && currentScope?.kind == ScopeKind.GLOBAL) {
            if (node.body.stmts.isEmpty()) {
                throw CompileError("Semantic: missing exit() in main function")
            }
            val last = node.body.stmts.last()
            if (last !is ExprStmtNode || last.expr !is CallExprNode || last.expr.expr !is PathExprNode || last.expr.expr.seg1.name != "exit") {
                throw CompileError("Semantic: missing exit() in main function")
            }
            if (returnType !is UnitType) {
                throw CompileError("Semantic: main function should always return UnitType, but found $returnType")
            }
        }
        return UnitType
    }

    override fun visit(node: StructItemNode): Type = UnitType
    override fun visit(node: EnumItemNode): Type = UnitType
    override fun visit(node: TraitItemNode): Type = UnitType
    override fun visit(node: ImplItemNode): Type {
        currentScope = node.scope
        currentSelfType = node.type.accept(this)
        currentSelfName = (node.type as? TypePathNode)?.name
            ?: throw CompileError("Semantic: encounter invalid impl type ${node.type}")
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
        } else if (node.stmts.isNotEmpty() && node.stmts.last() is ExprStmtNode && (node.stmts.last() as ExprStmtNode).expr is ReturnExprNode) {
            NeverType
        } else UnitType
        if (currentScope?.kind == ScopeKind.BLOCK) {
            currentScope = currentScope?.parentScope()
        }
        return retType
    }

    override fun visit(node: LoopExprNode): Type {
        val breakNum = breakStack.size
        val type = node.expr.accept(this)
        if (breakStack.isEmpty()) {
//            throw CompileError("Semantic: this `loop` has no `break` and will never exit")
            return NeverType
        }
        val expectType = breakStack.last()
        for (i in breakNum..(breakStack.size - 1)) {
            if (breakStack[i] != expectType) {
                throw CompileError("Semantic: type mismatch in `loop`, expect $expectType, met ${breakStack[i]}")
            }
        }
        while (breakStack.size > breakNum) breakStack.removeLast()
        return expectType
    }

    override fun visit(node: WhileExprNode): Type {
        if (node.conds.isNotEmpty()) {
            if (node.conds.size != 1) {
                throw CompileError("Semantic: `let` bindings in `while` conditions are not supported")
            }
            val condType = node.conds[0].accept(this)
            if (condType !is BoolType) {
                throw CompileError("Semantic: `while` condition must be a boolean. Expected `bool`, found `$condType`")
            }
        }
        node.expr.accept(this)
        if (breakStack.isNotEmpty() && breakStack.last() is NeverType) breakStack.removeLast()
        return UnitType
    }

    override fun visit(node: BreakExprNode): Type {
        val type = node.expr?.accept(this) ?: NeverType
        breakStack.add(type)
        return NeverType
    }

    override fun visit(node: ReturnExprNode): Type {
        val type = node.expr?.accept(this) ?: UnitType
        if (returnStack.isEmpty()) {
            throw CompileError("Semantic: `return` can only be used inside a function")
        }
        val expectType = returnStack.last()
        if (!assignable(type, expectType)) {
            throw CompileError("Semantic: mismatched types in return expression. Expected `$expectType`, but found `$type`")
        }
        return NeverType
    }

    override fun visit(node: ContinueExprNode): Type = NeverType
    override fun visit(node: IfExprNode): Type {
        if (node.conds.isNotEmpty()) {
            if (node.conds.size > 1) {
                throw CompileError("Semantic: `let` bindings in `if` conditions are not supported")
            }
            val condType = node.conds[0].accept(this)
            if (condType !is BoolType) {
                throw CompileError("Semantic: `if` condition must be a boolean. Expected `bool`, found `$condType`")
            }
        }
        val bodyType = node.expr.accept(this)
        val elseType = node.elseExpr?.accept(this)
        if (elseType == null) {
            if (!assignable(bodyType, UnitType) && !assignable(bodyType, NeverType)) {
                throw CompileError("Semantic: `if` expressions without an `else` block must return `()` (unit type)")
            }
            return UnitType
        } else {
            if (bodyType == elseType || (isInt(bodyType) && isInt(elseType) && canUnifyInt(
                    bodyType, elseType
                )) || bodyType is NeverType || elseType is NeverType
            ) {
                return if (bodyType is NeverType) elseType else bodyType
            }
            throw CompileError("Semantic: `if` and `else` have incompatible types. Expected `$bodyType`, found `$elseType`")
        }
    }

    override fun visit(node: FieldAccessExprNode): Type {
        val receiverType = autoDeref(node.expr.accept(this))
        if (receiverType !is StructType) {
            throw CompileError("Semantic: field access on a non-struct type `$receiverType`")
        }
        val symbol = currentScope?.resolve(receiverType.name, Namespace.TYPE) as? Struct
            ?: throw CompileError("Semantic: internal error: cannot find struct definition for `${receiverType.name}`")
        return symbol.type.fields[node.id]
            ?: throw CompileError("Semantic: no field `${node.id}` on type `$receiverType`")
    }

    override fun visit(node: MethodCallExprNode): Type {
        var receiverType = node.expr.accept(this)
        val isMutable = isMut(node.expr)
        if (receiverType is IntType) receiverType = UInt32Type
        val derefReceiverType = autoDeref(receiverType)
        if (node.pathSeg.name == null) {
            throw CompileError("Semantic: method calls must use a named identifier, not `self`")
        }
        val methodName = node.pathSeg.name
        val method = BuiltInMethods.findMethod(derefReceiverType, methodName) ?: {
            val symbol = when (derefReceiverType) {
                is StructType -> currentScope?.resolve(derefReceiverType.name, Namespace.TYPE) as? Struct
                    ?: throw CompileError("Semantic: cannot find definition for struct `${derefReceiverType.name}`")

                is EnumType -> currentScope?.resolve(derefReceiverType.name, Namespace.TYPE) as? Enum
                    ?: throw CompileError("Semantic: cannot find definition for enum `${derefReceiverType.name}`")

                else -> throw CompileError("Semantic: non-builtin methods can only be called on structs and enums, not on type `$derefReceiverType`")
            }
            symbol.methods[methodName]
                ?: throw CompileError("Semantic: no method named `${methodName}` found for type `${symbol.name}`")
        }()
        if (method.selfParam != null) {
            val self = if (method.selfParam!!.isRef) RefType(method.self!!, method.selfParam!!.isMut) else method.self!!
            if (!assignable(
                    receiverType, self
                ) && !(self is RefType && self.baseType == receiverType && (isMutable || !self.isMutable))
            ) {
                throw CompileError("Semantic: type mismatch for `self` parameter in method `${methodName}`. Expected `${self}`, found `$receiverType`")
            }
        }
        if (node.params.size != method.params.size) {
            throw CompileError("Semantic: method `${methodName}` called with wrong number of arguments. Expected ${method.params.size}, found ${node.params.size}")
        }
        node.params.zip(method.params).forEach { (argument, expect) ->
            val type = argument.accept(this)
            if (!assignable(type, expect.type)) {
                throw CompileError("Semantic: mismatched types for argument `${expect.name}` in call to method `${methodName}`. Expected `${expect.type}`, found `$type`")
            }
        }
        return method.returnType
    }

    override fun visit(node: CallExprNode): Type {
        if (node.expr !is PathExprNode) {
            throw CompileError("Semantic: function calls can only be made on path expressions")
        }
        if (node.expr.seg2 == null) {
            if (node.expr.seg1.name == null) {
                throw CompileError("Semantic: cannot directly call `self` as a function")
            }
            val identifier = node.expr.seg1.name
            val function = currentScope?.resolve(identifier, Namespace.VALUE) as? Function
                ?: throw CompileError("Semantic: cannot find function `${identifier}` in this scope")
            if (node.params.size != function.params.size) {
                throw CompileError("Semantic: function `${identifier}` called with wrong number of arguments. Expected ${function.params.size}, but found ${node.params.size}")
            }
            node.params.zip(function.params).forEach { (argument, expect) ->
                val type = argument.accept(this)
                if (!assignable(type, expect.type)) {
                    throw CompileError("Semantic: mismatched types for argument `${expect.name}` in call to function `${identifier}`. Expected `${expect.type}`, found `$type`")
                }
            }
            return function.returnType
        } else {
            val symbol = if (node.expr.seg1.name == null) {
                if (node.expr.seg1.type == Keyword.SELF) {
                    throw CompileError("Semantic: cannot use `self` as a type path qualifier")
                }
                currentScope?.resolve(currentSelfName, Namespace.TYPE)
                    ?: throw CompileError("Semantic: cannot use `SELF` outside an impl")
            } else {
                currentScope?.resolve(node.expr.seg1.name, Namespace.TYPE)
                    ?: throw CompileError("Semantic: cannot find type `${node.expr.seg1.name}` in this scope")
            }
            if (node.expr.seg2.type != null) throw CompileError("Semantic: unexpected `self` in type path")

            val identifier = node.expr.seg2.name
            when (symbol) {
                is Enum, is Struct -> {
                    val associateFunction = symbol.associateItems[identifier] as? Function
                        ?: throw CompileError("Semantic: no associated function named `${identifier}` found for type `${symbol.name}`")

                    if (!symbol.methods.containsKey(identifier)) {
                        if (node.params.size != associateFunction.params.size) {
                            throw CompileError("Semantic: associated function `${identifier}` called with wrong number of arguments. Expected ${associateFunction.params.size}, found ${node.params.size}")
                        }
                        node.params.zip(associateFunction.params).forEach { (argument, expect) ->
                            val type = argument.accept(this)
                            if (!assignable(type, expect.type)) {
                                throw CompileError("Semantic: mismatched types for argument `${expect.name}` in call to associated function `${identifier}`. Expected `${expect.type}`, found `$type`")
                            }
                        }
                        return associateFunction.returnType
                    } else {
                        throw CompileError("Semantic: `${identifier}` is a method, not an associated function. Call it using method-call syntax (e.g., `value.${identifier}()`)")
                    }
                }

                else -> throw CompileError("Semantic: invalid base type for path expression in function call")
            }
        }
    }

    override fun visit(node: CondExprNode): Type {
        return node.expr.accept(this)
    }

    override fun visit(node: LiteralExprNode): Type {
        return when (node.type) {
            Literal.CHAR -> CharType
            Literal.STRING, Literal.C_STRING, Literal.RAW_STRING, Literal.RAW_C_STRING -> RefType(StrType, false)

            Literal.INTEGER -> {
                getInt(node).actualType
            }

            Keyword.TRUE, Keyword.FALSE -> BoolType
            else -> throw CompileError("Semantic: invalid literal expression type")
        }
    }

    override fun visit(node: IdentifierExprNode): Type {
        throw CompileError("Semantic: standalone identifier expressions are not supported")
    }

    override fun visit(node: PathExprNode): Type {
        if (node.seg2 == null) {
            if (node.seg1.name == null) {
                return if (node.seg1.type == Keyword.SELF) {
                    currentScope?.resolveVariable("self")?.type
                        ?: throw CompileError("Semantic: cannot find `self` in this scope")
                } else {
                    currentSelfType
                }
            } else {
                val identifier = node.seg1.name
                val variable = currentScope?.resolveVariable(identifier)
                if (variable != null) return variable.type
                val symbol = currentScope?.resolve(identifier, Namespace.VALUE) as? Constant
                    ?: throw CompileError("Semantic: cannot find value `${identifier}` in this scope")
                return symbol.type
            }
        } else {
            val symbol = if (node.seg1.name == null) {
                if (node.seg1.type == Keyword.SELF) {
                    throw CompileError("Semantic: cannot use `self` as a type path qualifier")
                }
                currentScope?.resolve(currentSelfName, Namespace.TYPE)
                    ?: throw CompileError("Semantic: cannot use `SELF` outside an impl")
            } else {
                currentScope?.resolve(node.seg1.name, Namespace.TYPE)
                    ?: throw CompileError("Semantic: cannot find type `${node.seg1.name}` in this scope")
            }
            if (node.seg2.type != null) throw CompileError("Semantic: unexpected `self` in type path")
            val identifier = node.seg2.name
            return when (symbol) {
                is Enum -> {
                    if (symbol.type.variants.contains(identifier)) symbol.type
                    else throw CompileError("Semantic: no variant named `${identifier}` found for enum `${symbol.name}`")
                }

                is Struct -> {
                    (symbol.associateItems[identifier] as? Constant)?.type
                        ?: throw CompileError("Semantic: no associated constant named `${identifier}` found for struct `${symbol.name}`")
                }

                else -> throw CompileError("Semantic: invalid base type for path expression")
            }
        }
    }

    override fun visit(node: ArrayExprNode): Type {
        if (node.lengthOp != null && node.repeatOp != null) {
            val elementType = node.repeatOp.accept(this)
            if (node.evaluatedSize < 0) {
                throw CompileError("Semantic: aarray size could not be determined at compile time")
            }
            return ArrayType(elementType, node.evaluatedSize.toInt())
        } else {
            if (node.elements.isEmpty()) {
                throw CompileError("Semantic: cannot infer type of empty array literal")
            }
            val firstElementType = node.elements.first().accept(this)
            for (i in 1 until node.elements.size) {
                val currentElementType = node.elements[i].accept(this)
                if ((isInt(currentElementType) && isInt(firstElementType) && canUnifyInt(
                        currentElementType, firstElementType
                    ))
                ) continue
                if (currentElementType != firstElementType) {
                    throw CompileError("Semantic: mismatched types in array literal. Expected `${firstElementType}`, but found `${currentElementType}` at index $i")
                }
            }
            return ArrayType(firstElementType, node.elements.size)
        }
    }

    override fun visit(node: IndexExprNode): Type {
        val baseType = node.base.accept(this)
        val indexType = node.index.accept(this)
        if (autoDeref(baseType) !is ArrayType) {
            throw CompileError("Semantic: type `$baseType` cannot be indexed")
        }
        if (!isInt(indexType)) {
            throw CompileError("Semantic: array index must be an integer. Expected integer, found `$indexType`")
        }
        return (autoDeref(baseType) as ArrayType).elementType
    }

    override fun visit(node: StructExprNode): Type {
        if (node.path !is PathExprNode || node.path.seg2 != null || node.path.seg1.name == null) {
            throw CompileError("Semantic: struct literals must be created with a simple type name")
        }
        val structName = node.path.seg1.name
        val symbol = currentScope?.resolve(structName, Namespace.TYPE) as? Struct
            ?: throw CompileError("Semantic: cannot find struct `${structName}` in this scope")

        val providedFields = node.fields.map { it.id }.toSet()
        val expectedFields = symbol.type.fields.keys

        node.fields.forEach {
            val expectType = symbol.type.fields[it.id]
            if (expectType == null) {
                throw CompileError("Semantic: struct `${structName}` has no field named `${it.id}`")
            }
            if (it.expr == null) {
                throw CompileError("Semantic: field `${it.id}` must be initialized in struct literal for `${structName}`")
            }
            val type = it.expr.accept(this)
            if (!assignable(type, expectType)) {
                throw CompileError("Semantic: mismatched type for field `${it.id}` in struct `${structName}`. Expected `$expectType`, found `$type`")
            }
        }

        val missingFields = expectedFields - providedFields
        if (missingFields.isNotEmpty()) {
            throw CompileError(
                "Semantic: missing field(s) in struct literal for `${structName}`: ${
                    missingFields.joinToString(
                        ", "
                    )
                }"
            )
        }

        return symbol.type
    }

    override fun visit(node: UnderscoreExprNode): Type {
        throw CompileError("Semantic: underscore expressions are not yet supported")
    }

    override fun visit(node: UnaryExprNode): Type {
        val rightType = node.rhs.accept(this)
        when (node.op) {
            Punctuation.MINUS -> {
                if (!isInt(rightType)) {
                    throw CompileError("Semantic: cannot apply unary operator `-` to type `$rightType`")
                }
                if (rightType is UInt32Type) return Int32Type
                if (rightType is USizeType) return ISizeType
                return rightType
            }

            Punctuation.BANG -> {
                if (rightType !is BoolType && !isInt(rightType)) {
                    throw CompileError("Semantic: cannot apply unary operator `!` to type `$rightType`")
                }
                return rightType
            }

            else -> throw CompileError("Semantic: invalid unary operator `${node.op}`")
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
                            throw CompileError("Semantic: mismatched types in assignment. Expected `$leftType`, found `$rightType`")
                        }
                    }

                    else -> {
                        if (!isInt(leftType) || !isInt(rightType)) {
                            throw CompileError("Semantic: cannot apply assignment operator `${node.op}` to non-integer types `$leftType` and `$rightType`")
                        }
                        unifyInt(leftType, rightType)
                    }
                }
                return UnitType
            }

            in binaryOp -> {
                val leftType = node.lhs.accept(this)
                val rightType = node.rhs.accept(this)
                when (node.op) {
                    Punctuation.PLUS, Punctuation.MINUS, Punctuation.STAR, Punctuation.SLASH, Punctuation.PERCENT -> {
                        if (!isInt(leftType) || !isInt(rightType)) {
                            throw CompileError("Semantic: cannot apply binary operator `${node.op}` to non-integer types `$leftType` and `$rightType`")
                        }
                        if (!canUnifyInt(leftType, rightType)) {
                            throw CompileError("Semantic: cannot apply binary operator `${node.op}` to different integer types `$leftType` and `$rightType`")
                        }
                        return leftType
                    }

                    Punctuation.EQUAL_EQUAL, Punctuation.NOT_EQUAL -> {
                        if (leftType == rightType || (isInt(leftType) && isInt(rightType) && canUnifyInt(
                                leftType, rightType
                            ))
                        ) {
                            return BoolType
                        } else throw CompileError("Semantic: cannot compare two different types: `$leftType` and `$rightType`")
                    }

                    Punctuation.LESS, Punctuation.LESS_EQUAL, Punctuation.GREATER, Punctuation.GREATER_EQUAL -> {
                        if ((leftType is CharType && rightType is CharType) || (isInt(leftType) && isInt(rightType) && canUnifyInt(
                                leftType, rightType
                            ))
                        ) {
                            return BoolType
                        } else throw CompileError("Semantic: binary operator `${node.op}` cannot be applied to types `$leftType` and `$rightType`")
                    }

                    Punctuation.AND_AND, Punctuation.OR_OR -> {
                        if (leftType !is BoolType || rightType !is BoolType) {
                            throw CompileError("Semantic: logical operator `${node.op}` requires boolean operands, but found `$leftType` and `$rightType`")
                        }
                        return BoolType
                    }

                    Punctuation.AMPERSAND, Punctuation.PIPE, Punctuation.CARET -> {
                        if (!isInt(leftType) || !isInt(rightType) || !canUnifyInt(leftType, rightType)) {
                            throw CompileError("Semantic: bitwise operator `${node.op}` cannot be applied to types `$leftType` and `$rightType`")
                        }
                        return leftType
                    }

                    Punctuation.LESS_LESS, Punctuation.GREATER_GREATER -> {
                        if (!isInt(leftType) || !isInt(rightType)) {
                            throw CompileError("Semantic: bitwise shift operator `${node.op}` requires an integer left-hand side and an integer right-hand side")
                        }
                        return leftType
                    }

                    else -> throw CompileError("Semantic: invalid binary operator `${node.op}`")
                }
            }

            else -> throw CompileError("Semantic: invalid binary operator `${node.op}`")
        }
    }

    override fun visit(node: ItemStmtNode): Type {
        return node.item.accept(this)
    }

    override fun visit(node: LetStmtNode): Type {
        val type = node.type?.accept(this)
            ?: throw CompileError("Semantic: type annotations are required in `let` statements and cannot be inferred")
        val exprType = node.expr?.accept(this) ?: throw CompileError("Semantic: `let` statements must be initialized")
        if (!assignable(exprType, type)) {
            throw CompileError("Semantic: mismatched types in `let` statement. Expected `$type`, but found `$exprType`")
        }
        currentScope?.declareVariable(getPatternBind(node.pattern, type))
        return UnitType
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
        throw CompileError("Semantic: `ref` patterns are not supported")
    }

    override fun visit(node: WildcardPatternNode): Type {
        throw CompileError("Semantic: `_` patterns are not supported in this context")
    }

    override fun visit(node: TypePathNode): Type {
        return if (node.name != null) {
            when (val type = currentScope?.resolve(node.name, Namespace.TYPE)) {
                is BuiltIn -> type.type
                is Enum -> type.type
                is Struct -> type.type
                else -> throw CompileError("Semantic: cannot find type `${node.name}` in this scope, found $type")
            }
        } else {
            if (node.type == Keyword.SELF) {
                currentScope?.resolveVariable("self")?.type
                    ?: throw CompileError("Semantic: cannot find `self` type in this scope")

            } else currentSelfType
        }
    }

    override fun visit(node: RefTypeNode): Type {
        return RefType(node.type.accept(this), node.hasMut)
    }

    override fun visit(node: ArrayTypeNode): Type {
        val type = node.type.accept(this)
        if (node.evaluatedSize < 0) throw CompileError("Semantic: array size could not be determined at compile time")
        return ArrayType(type, node.evaluatedSize.toInt())
    }

    override fun visit(node: UnitTypeNode): Type {
        return UnitType
    }

    override fun visit(node: GroupedExprNode): Type {
        return node.expr.accept(this)
    }

    override fun visit(node: CastExprNode): Type {
        val exprType = node.expr.accept(this)
        val targetType = node.targetType.accept(this)
        if ((isInt(exprType) && isInt(targetType)) || (exprType is BoolType && isInt(targetType))) {
            return targetType
        } else {
            throw CompileError("Semantic: only integer types can be cast. Cannot cast from `$exprType` to `$targetType`")
        }
    }

    override fun visit(node: BorrowExprNode): Type {
        return RefType(node.expr.accept(this), node.isMut)
    }

    override fun visit(node: DerefExprNode): Type {
        val innerType = node.expr.accept(this)
        if (innerType !is RefType) throw CompileError("Semantic: type `$innerType` cannot be dereferenced")
        return innerType.baseType
    }
}