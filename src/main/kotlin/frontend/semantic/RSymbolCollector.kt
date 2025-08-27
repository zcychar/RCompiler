package frontend.semantic

import frontend.AST.*

class RSymbolCollector(val gScope: Scope, val crate: CrateNode) : ASTVisitor<Unit> {

    var currentScope: Scope? = gScope

    fun process() = visit(crate)

    override fun visit(node: CrateNode) = node.items.forEach { it.accept(this) }

    override fun visit(node: FunctionItemNode) {
        val scope = Scope(currentScope)
        val symbol = FunctionSymbol(
            node.name, FunctionType(
                null, listOf(), UnitType
            )
        )
        node.body?.accept(this)
    }

    override fun visit(node: StructItemNode) {
        currentScope?.define(node.name, StructSymbol(node.name, StructType(node.name, listOf())))
    }

    override fun visit(node: EnumItemNode) {
        currentScope?.define(node.name, EnumSymbol(node.name, EnumType(node.name, node.variants)))
    }

    override fun visit(node: TraitItemNode) {
        currentScope?.define(node.name, TraitSymbol(node.name, TraitType, listOf()))
    }

    override fun visit(node: ImplItemNode) {
    }

    override fun visit(node: ConstItemNode) {
    }

    override fun visit(node: BlockExprNode) {
        currentScope = Scope(currentScope)
        node.stmts.forEach { it.accept(this) }
        node.scope = currentScope
        currentScope = currentScope?.parentScope()
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

//    override fun visit(node: MatchExprNode) {
//    }

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