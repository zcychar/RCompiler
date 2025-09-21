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
    fun visit(node: WhileExprNode): T
    fun visit(node: BreakExprNode): T
    fun visit(node: ReturnExprNode): T
    fun visit(node: ContinueExprNode): T
    fun visit(node: IfExprNode): T
    fun visit(node: FieldAccessExprNode): T
    fun visit(node: MethodCallExprNode): T

    //    fun visit(node: MatchExprNode): T
    fun visit(node: CallExprNode): T
    fun visit(node: CondExprNode): T
    fun visit(node: LiteralExprNode): T
    fun visit(node: IdentifierExprNode): T
    fun visit(node: PathExprNode): T
    fun visit(node: ArrayExprNode): T
    fun visit(node: IndexExprNode): T
    fun visit(node: StructExprNode): T
    fun visit(node: UnderscoreExprNode): T
    fun visit(node: UnaryExprNode): T
    fun visit(node: BinaryExprNode): T
    fun visit(node: ItemStmtNode): T
    fun visit(node: LetStmtNode): T
    fun visit(node: ExprStmtNode): T
    fun visit(node: NullStmtNode): T
    fun visit(node: IdentifierPatternNode): T
    fun visit(node: RefPatternNode): T
    fun visit(node: WildcardPatternNode): T
    fun visit(node: TypePathNode): T
    fun visit(node: RefTypeNode): T
    fun visit(node: ArrayTypeNode): T
    fun visit(node: UnitTypeNode): T
    fun visit(node: GroupedExprNode): T
    fun visit(node: CastExprNode): T
}