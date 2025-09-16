package frontend.semantic

import frontend.AST.*

class RSymbolCollector(val preludeScope: Scope, val crate: CrateNode) : ASTVisitor<Unit> {

    var currentScope: Scope? = preludeScope

    fun process() = visit(crate)

    override fun visit(node: CrateNode) {
        currentScope = Scope(currentScope, "global")
        node.scope = currentScope
        node.items.forEach { it.accept(this) }
        currentScope = currentScope?.parentScope()
    }

    override fun visit(node: FunctionItemNode) {
        val function = Function(
            name = node.name,
            params = listOf(),
            returnType = UnitType,
            selfParam = null
        )
        currentScope?.declare(function, Namespace.VALUE)
        currentScope = Scope(currentScope, currentScope?.description + "::FUN-${node.name}")
        node.body?.scope = currentScope
        node.body?.accept(this)
        currentScope = currentScope?.parentScope()
    }

    override fun visit(node: StructItemNode) {
        val struct = Struct(
            name = node.name,
            type = StructType(name = node.name, fields = mapOf())
        )
        currentScope?.declare(struct, Namespace.TYPE)
    }

    override fun visit(node: EnumItemNode) {
        val enum = Enum(
            name = node.name,
            type = EnumType(name = node.name, variants = setOf())
        )
        currentScope?.declare(enum, Namespace.TYPE)
    }

    override fun visit(node: TraitItemNode) {
        val trait = Trait(
            name = node.name,
            type = TraitType(name = node.name, associatedItems = mapOf())
        )
        currentScope?.declare(trait, Namespace.TYPE)
    }

    override fun visit(node: ImplItemNode) {}

    override fun visit(node: ConstItemNode) {
        val constItem = Constant(
            name = node.name,
            type = UnitType,
            value = null
        )
        currentScope?.declare(constItem, Namespace.VALUE)
    }

    override fun visit(node: BlockExprNode) {
        node.scope?:{
            currentScope= Scope(currentScope,currentScope?.description+"::BLK")
            node.scope=currentScope
        }
        node.stmts.forEach { it.accept(this) }
        currentScope=currentScope?.parentScope()
    }

    override fun visit(node: LoopExprNode) {
        node.expr.accept(this)
    }

    override fun visit(node: WhileExprNode) {
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
        node.elseIf?.accept(this)
    }

    override fun visit(node: FieldAccessExprNode) {
        node.expr.accept(this)
    }

    override fun visit(node: MethodCallExprNode) {
        node.expr.accept(this)
        node.params.forEach { it.accept(this) }
    }


    override fun visit(node: CallExprNode) {
        node.expr.accept(this)
        node.params.forEach { it.accept(this) }
    }

    override fun visit(node: CondExprNode) {
        node.expr.accept(this)
    }

    override fun visit(node: LiteralExprNode) {}

    override fun visit(node: IdentifierExprNode) {}

    override fun visit(node: PathExprNode) {}

    override fun visit(node: ArrayExprNode) {
        node.elements?.forEach { it.accept(this) }
        node.lengthOp?.accept(this)
        node.repeatOp?.accept(this)
    }

    override fun visit(node: IndexExprNode) {
        node.first.accept(this)
        node.second.accept(this)
    }

    override fun visit(node: StructExprNode) {
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

    override fun visit(node: LiteralPatternNode) {}

    override fun visit(node: IdentifierPatternNode) {}

    override fun visit(node: RefPatternNode) {}

    override fun visit(node: PathPatternNode) {}

    override fun visit(node: WildcardPatternNode) {}

    override fun visit(node: TypePathNode) {}

    override fun visit(node: RefTypeNode) {
        node.type.accept(this)
    }

    override fun visit(node: ArrayTypeNode) {
        node.type.accept(this)
        node.expr.accept(this)
    }

    override fun visit(node: UnitTypeNode) {}

}