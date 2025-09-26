package frontend.semantic

import frontend.ast.*
import utils.CompileError

class RSemanticChecker(val gScope: Scope, val crate: CrateNode) : ASTVisitor<Type> {
    var currentScope: Scope? = gScope
    var currentSelf: Type = UnitType

    val breakStack: MutableList<Type> = mutableListOf()


    fun process() = visit(crate)

    fun autoDeref(type: Type): Type {
        return if (type is RefType) type.baseType
        else type
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
        TODO("Not yet implemented")
    }

    override fun visit(node: StructItemNode): Type {
        TODO("Not yet implemented")
    }

    override fun visit(node: EnumItemNode): Type {
        TODO("Not yet implemented")
    }

    override fun visit(node: TraitItemNode): Type {
        TODO("Not yet implemented")
    }

    override fun visit(node: ImplItemNode): Type {
        TODO("Not yet implemented")
    }

    override fun visit(node: ConstItemNode): Type {
        return NeverType
    }

    override fun visit(node: BlockExprNode): Type {
        currentScope = node.scope
        val types = node.stmts.map { it.accept(this) }
        val retType = if (node.hasFinal()) {

        } else {

        }
        if (currentScope?.kind == ScopeKind.BLOCK) {
            currentScope = currentScope?.parentScope()
        }
        return UnitType
    }

    override fun visit(node: LoopExprNode): Type {
        TODO("Not yet implemented")
    }

    override fun visit(node: WhileExprNode): Type {
        TODO("Not yet implemented")
    }

    override fun visit(node: BreakExprNode): Type {
        TODO("Not yet implemented")
    }

    override fun visit(node: ReturnExprNode): Type {
        TODO("Not yet implemented")
    }

    override fun visit(node: ContinueExprNode): Type {
        TODO("Not yet implemented")
    }

    override fun visit(node: IfExprNode): Type {
        TODO("Not yet implemented")
    }

    override fun visit(node: FieldAccessExprNode): Type {
        TODO("Not yet implemented")
    }

    override fun visit(node: MethodCallExprNode): Type {
        TODO("Not yet implemented")
    }

    override fun visit(node: CallExprNode): Type {
        TODO("Not yet implemented")
    }

    override fun visit(node: CondExprNode): Type {
        TODO("Not yet implemented")
    }

    override fun visit(node: LiteralExprNode): Type {
        TODO("Not yet implemented")
    }

    override fun visit(node: IdentifierExprNode): Type {
        TODO("Not yet implemented")
    }

    override fun visit(node: PathExprNode): Type {
        TODO("Not yet implemented")
    }

    override fun visit(node: ArrayExprNode): Type {
        TODO("Not yet implemented")
    }

    override fun visit(node: IndexExprNode): Type {
        TODO("Not yet implemented")
    }

    override fun visit(node: StructExprNode): Type {
        TODO("Not yet implemented")
    }

    override fun visit(node: UnderscoreExprNode): Type {
        TODO("Not yet implemented")
    }

    override fun visit(node: UnaryExprNode): Type {
        TODO("Not yet implemented")
    }

    override fun visit(node: BinaryExprNode): Type {
        TODO("Not yet implemented")
    }

    override fun visit(node: ItemStmtNode): Type {
        return node.item.accept(this)
    }

    override fun visit(node: LetStmtNode): Type {
        val type = node.type?.accept(this) ?: throw CompileError("Semantic: Invalid missing type of $node")
        val exprType =
            node.expr?.accept(this)     ?: throw CompileError("Semantic: Invalid un-initilized let statement of $node")
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
        } else currentSelf
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
}