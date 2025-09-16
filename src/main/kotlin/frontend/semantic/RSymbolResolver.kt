package frontend.semantic

import frontend.AST.*
import frontend.Keyword
import utils.CompileError


class RSymbolResolver(val gScope: Scope, val crate: CrateNode) : ASTVisitor<Unit> {
  var currentScope: Scope? = gScope
  var currentSelfType: Type? = null

  fun process() = visit(crate)

  //---------------Type Resolution------------------
  fun resolveType(node: TypeNode):Type{
    when(node){
      is ArrayTypeNode -> TODO()
      is RefTypeNode -> TODO()
      is TypePathNode -> {
        if(node.type == Keyword.SELF_UPPER){
          return currentSelfType?:throw CompileError("Semantic:Invalid usage of 'Self'")
        }
        val name = node.name?:throw CompileError("Semantic:TypePathNode has no ID and is not Self")
        when(val symbol = currentScope?.resolve(name, Namespace.TYPE)){
          is Struct -> resolveStruct(symbol.name)
          else ->throw CompileError("TODO")
        }
      }
      UnitTypeNode -> TODO()
    }
    throw CompileError("TODO")
  }


  //-----------------Resolvers----------------------
  fun resolveStruct(name:String): StructType{
    throw CompileError("TODO")
  }

  fun resolveEnum(name:String): EnumType{
    throw CompileError("TODO")
  }

  fun resolveFunction(name:String){
    throw CompileError("TODO")
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
}