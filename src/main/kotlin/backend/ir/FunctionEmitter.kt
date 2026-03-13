package backend.ir

import frontend.ast.*
import frontend.semantic.Function
import frontend.semantic.RefType
import frontend.semantic.Type

/**
 * &self also store self copy too
 * other param's name is changed to param.tmp (ssa value) would be stored immediately at entry block with true names.
 *
 * ## Aggregate Strategy
 *
 * Aggregates flow through [destPtr] parameters. When the caller knows the final
 * destination for an aggregate (e.g. a `let` binding's alloca, an sret slot),
 * it passes that pointer so the expression stores directly — no temporary, no
 * whole-aggregate load/store.
 */
class FunctionEmitter(
  private val context: CodegenContext,
  private val builder: IrBuilder = context.builder,
  private val exprEmitter: ExprEmitter = ExprEmitter(context),
  private val valueEnv: ValueEnv = context.valueEnv,
) {

  fun emitFunction(fnSymbol: Function, node: FunctionItemNode, owner: Type? = null): IrFunction {
    val signature = irFunctionSignature(fnSymbol)
    val ownerName = when (val resolvedOwner = owner ?: fnSymbol.self) {
      is frontend.semantic.StructType -> resolvedOwner.name
      is frontend.semantic.EnumType -> resolvedOwner.name
      else -> null
    }
    val irName = if(fnSymbol.name=="main") "main" else ownerName?.let { "$it.${fnSymbol.name}." } ?: (fnSymbol.name+".")
    val parameterNames = buildList {
      signature.sretType?.let { add("ret.slot") }
      fnSymbol.selfParam?.let { add("self.tmp") }
      fnSymbol.params.forEach { add(it.name + ".tmp") }
    }

    node.body!!.stmts.forEach {
      if (it is ItemStmtNode) {
        when (it.item) {
          is ConstItemNode -> context.emitConst(node.body.scope!!, it.item)
          else -> Unit
        }
      }
    }
    node.body.stmts.forEach {
      if (it is ItemStmtNode) {
        when (it.item) {
          is StructItemNode -> context.emitStruct(node.body.scope!!, it.item, this)
          else -> Unit
        }
      }
    }
    node.body.stmts.forEach {
      if (it is ItemStmtNode) {
        when (it.item) {
          is FunctionItemNode -> context.emitFunction(node.body.scope!!, this, it.item)
          else -> Unit
        }
      }
    }
    val function = IrFunction(irName, signature, parameterNames)
    context.module.declareFunction(function)
    context.currentFunction = function
    val previousScope = context.currentScope
    context.currentScope = node.body?.scope ?: previousScope

    builder.positionAt(function, function.entryBlock("entry"))
    valueEnv.pushFunction(signature.returnType)
    valueEnv.enterScope()
    builder.freshLocalName("entry")
    bindParameters(fnSymbol, signature)
    val expectsValue = signature.returnType !is IrPrimitive || signature.returnType.kind != PrimitiveKind.UNIT
    val sretPtr = valueEnv.resolve(SRET_BINDING) as? Bind.Pointer

    // For aggregate-returning functions, pass the sret pointer as the destination
    // for the block's final expression so it stores directly without temporaries.
    val blockDestPtr = if (sretPtr != null && expectsValue) sretPtr.addr else null
    val blockResult = node.body?.let {
      emitBlock(it, expectValue = expectsValue, expectedType = signature.returnType, destPtr = blockDestPtr)
    }

    if (builder.hasInsertionPoint()) {
      if (sretPtr != null) {
        if (blockResult != null &&
          ((blockResult.type !is IrPrimitive) || (blockResult.type as IrPrimitive).kind != PrimitiveKind.NEVER)
        ) {
          // If blockResult is the sret pointer itself (destination-passing worked),
          // nothing to do — the value is already there.
          // If blockResult is something else (a scalar value, or a different pointer),
          // we need to store it.
          if (blockResult !== blockDestPtr && blockResult != blockDestPtr) {
            val retType = signature.returnType
            if (isAggregate(retType) && blockResult.type is IrPointer) {
              // blockResult is a pointer to the aggregate — field-wise copy to sret.
              builder.emitAggregateCopy(sretPtr.addr, blockResult, retType)
            } else if (blockResult.type == retType) {
              builder.emit(IrStore("", IrPrimitive(PrimitiveKind.UNIT), sretPtr.addr, blockResult))
            }
          }
        }
        builder.emitTerminator(
          IrReturn(
            name = "",
            type = signature.actualReturnType,
            value = null,
          )
        )
      } else {
        val returnValue = when {
          signature.returnType is IrPrimitive && signature.returnType.kind == PrimitiveKind.UNIT -> null
          blockResult != null &&
              ((blockResult.type !is IrPrimitive) || (blockResult.type as IrPrimitive).kind != PrimitiveKind.NEVER) &&
              blockResult.type == signature.returnType -> blockResult

          else -> IrUndef(signature.returnType)
        }
        builder.emitTerminator(
          IrReturn(
            name = "",
            type = signature.returnType,
            value = returnValue,
          )
        )
      }
    }

    valueEnv.leaveScope()
    valueEnv.popFunction()
    context.currentFunction = null
    context.currentScope = previousScope
    return function
  }

  fun emitMethod(fnSymbol: Function, implType: Type, node: frontend.ast.FunctionItemNode): IrFunction {
    return emitFunction(fnSymbol, node, implType)
  }

  /**
   * Emit a block expression.
   *
   * @param destPtr  If non-null and the block's final expression produces an aggregate,
   *                 pass this destination through so the value is stored directly.
   */
  fun emitBlock(block: BlockExprNode, expectValue: Boolean, expectedType: IrType? = null, destPtr: IrValue? = null): IrValue? {
    valueEnv.enterScope()

    var result: IrValue? = null
    for ((index, stmt) in block.stmts.withIndex()) {
      val isLastExpr = expectValue && index == block.stmts.lastIndex && stmt is ExprStmtNode && !stmt.hasSemiColon
      result = emitStmt(stmt, isLastExpr, expectedType, if (isLastExpr) destPtr else null)
      if (!builder.hasInsertionPoint()) break
    }
    valueEnv.leaveScope()
    return result
  }

  /**
   * Emit a statement.
   *
   * @param destPtr  Passed through to the expression emitter for the final
   *                 expression of a block (aggregate destination-passing).
   */
  private fun emitStmt(stmt: StmtNode, expectValue: Boolean, expectedType: IrType?, destPtr: IrValue? = null): IrValue? = when (stmt) {
    is LetStmtNode -> {
      emitLet(stmt)
      null
    }

    is ExprStmtNode -> {
      val value = exprEmitter.emitExpr(stmt.expr, expectedType.takeIf { expectValue }, destPtr = if (expectValue) destPtr else null)
      if (expectValue) value else null
    }

    is ItemStmtNode -> null// items are handled by higher-level drivers
    NullStmtNode -> null
  }

  /**
   * Emit a let binding.
   *
   * For aggregate types, uses destination-passing: the alloca for the binding is
   * passed as [destPtr] to [ExprEmitter.emitExpr], so the initializer stores
   * directly into the binding's storage — no temporary, no whole-aggregate copy.
   */
  private fun emitLet(stmt: LetStmtNode) {
    val pattern = stmt.pattern as? IdentifierPatternNode
      ?: error("Only identifier patterns are supported in codegen")
    val initializer = stmt.expr ?: error("let without initializer is not supported in codegen")
    val exprType = toIrType(stmt.realType!!)

    val patternName = builder.freshLocalName(pattern.id)
    val patternAddr = builder.emit(
      IrAlloca(
        name = patternName,
        type = IrPointer(exprType),
        allocatedType = exprType,
      ), patternName
    )

    if (isAggregate(exprType)) {
      // Aggregate: use destination-passing. The expression will store directly
      // into patternAddr (either via storeStructToPointer, emitAggregateCopy,
      // or by passing it as an sret pointer to a call).
      exprEmitter.emitExpr(initializer, destPtr = patternAddr)
    } else {
      // Scalar: evaluate the expression and store the resulting value.
      val exprValue = exprEmitter.emitExpr(initializer)
      builder.emit(
        IrStore("", IrPrimitive(PrimitiveKind.UNIT), patternAddr, exprValue)
      )
    }
    valueEnv.bind(pattern.id, Bind.Pointer(patternAddr))
  }

  private fun bindParameters(fnSymbol: Function, signature: IrFunctionSignature) {
    var index = 0
    signature.sretType?.let {
      val irParam = IrParameter(index, "ret.slot", signature.parameters[index])
      valueEnv.bind(SRET_BINDING, Bind.Pointer(irParam))
      index++
    }
    fnSymbol.selfParam?.let {
      val irParam = IrParameter(index, "self.tmp", signature.parameters[index])
      // Check the *semantic* self type to decide if this was an aggregate we wrapped.
      // If selfParam.isRef is true, the parameter was already a pointer (a reference)
      // — NOT an aggregate we wrapped. Only non-ref aggregate self gets the copy treatment.
      val rawSelf = fnSymbol.self ?: error("method missing self target")
      val selfSemantic = if (it.isRef) RefType(rawSelf, it.isMut) else rawSelf
      val originalIr = toIrType(selfSemantic)
      if (isAggregate(originalIr)) {
        // Aggregate self passed by pointer (our new convention).
        // Copy into a local alloca field-wise so the callee can mutate its own
        // copy without affecting the caller's storage.
        val localAddr = builder.emit(
          IrAlloca("self..local", IrPointer(originalIr), originalIr), "self..local"
        )
        builder.emitAggregateCopy(localAddr, irParam, originalIr)
        valueEnv.bind("self", Bind.Pointer(localAddr))
      } else {
        val parmAddr = builder.borrow("self..tmp", irParam)
        valueEnv.bind("self", Bind.Pointer(parmAddr))
      }
      index++
    }
    fnSymbol.params.forEach { param ->
      val irParam = IrParameter(index, param.name + ".tmp", signature.parameters[index])
      // Check the *semantic* param type to decide if this was an aggregate we wrapped.
      // RefType params (e.g. &Food, &mut [SegT; N]) are already pointers in the
      // original IR — they must NOT be treated as aggregate-by-pointer.
      val originalIr = toIrType(param.type)
      if (isAggregate(originalIr)) {
        // Aggregate param passed by pointer (our new convention).
        // Copy into a local alloca field-wise so the callee can mutate its own
        // copy without affecting the caller's storage.
        val localAddr = builder.emit(
          IrAlloca(param.name + "..local", IrPointer(originalIr), originalIr), param.name + "..local"
        )
        builder.emitAggregateCopy(localAddr, irParam, originalIr)
        valueEnv.bind(param.name, Bind.Pointer(localAddr))
      } else {
        val parmAddr = builder.borrow(param.name + "..tmp", irParam)
        valueEnv.bind(param.name, Bind.Pointer(parmAddr))
      }
      index++
    }
  }

}


fun irFunctionSignature(function: Function): IrFunctionSignature {
  val params = mutableListOf<IrType>()
  function.selfParam?.let {
    val rawSelf = function.self ?: error("method missing self target")
    val selfSemantic = if (it.isRef) RefType(rawSelf, it.isMut) else rawSelf
    val selfIr = toIrType(selfSemantic)
    // Aggregate self params are passed by pointer so the callee has an address
    // for field access and no whole-aggregate value needs to exist in the IR.
    params += if (isAggregate(selfIr)) IrPointer(selfIr) else selfIr
  }
  params += function.params.map { param ->
    val irType = toIrType(param.type)
    // Aggregate params are passed by pointer — same rationale as self.
    if (isAggregate(irType)) IrPointer(irType) else irType
  }
  val ret = toIrType(function.returnType)
  return if (isAggregate(ret)) {
    val sretParam = mutableListOf<IrType>()
    sretParam += IrPointer(ret)
    sretParam.addAll(params)
    IrFunctionSignature(sretParam, ret, ret)
  } else {
    IrFunctionSignature(params, ret)
  }
}
