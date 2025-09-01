package frontend.semantic

import frontend.AST.*

class RSemanticChecker: ASTVisitor<Unit> {
    override fun visit(node: CrateNode) {
        node.items.forEach { it.accept(this) }
    }

    override fun visit(node: FunctionItemNode) {
        TODO("Not yet implemented")
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