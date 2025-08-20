package frontend.AST

interface ASTVisitor<T> {
    fun visit(node: CrateNode): T
    fun visit(node: FunctionItemNode): T
    fun visit(node: StructItemNode): T
    fun visit(node: EnumItemNode): T
    fun visit(node: TraitItemNode): T
    fun visit(node: ImplItemNode): T
    fun visit(node: ConstItemNode): T

    fun visit(node: BlockExprNode): T
    fun visit(node: LoopExprNode): T
    fun visit(node: LiteralExprNode): T
    fun visit(node: IdentifierExprNode): T
    fun visit(node: CallExprNode): T
    fun visit(node: BinaryExprNode): T
    fun visit(node: UnaryExprNode): T

    // 语句
    fun visit(node: ExprStmtNode): T
    fun visit(node: LetStmtNode): T

    // 类型
    fun visit(node: TypeNode): T
    fun visit(node: ArrayTypeNode): T

    fun visit(node: PatternNode): T

}