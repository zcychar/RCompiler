package backend.ir

import utils.CompileError

class IrBuilder(
  private val module: IrModule,
) {
  private var currentFunction: IrFunction? = null
  private var currentBlock: IrBasicBlock? = null
  private var nextRegisterId: Int = 0
  private val nameCounters: MutableMap<String, Int> = mutableMapOf()
  private var allocaInsertPos: Int = 0
  private var entryBlock: IrBasicBlock? = null

  fun positionAt(function: IrFunction, block: IrBasicBlock) {
    if (currentFunction !== function) {
      nextRegisterId = 0
      nameCounters.clear()
      entryBlock = function.entryBlock("entry")
      allocaInsertPos = entryBlock?.instructions?.size ?: 0
    }
    currentFunction = function
    currentBlock = block
    if (!function.blocks.contains(block)) {
      function.appendBlock(block)
    }
  }

  fun ensureBlock(name: String): IrBasicBlock {
    val fn = currentFunction ?: error("No current function")
    return fn.blocks.find { it.label == name } ?: fn.createBlock(name)
  }

  fun freshLocalName(hint: String): String {
    val count = (nameCounters[hint] ?: 0) + 1
    nameCounters[hint] = count
    return if (count == 1) hint else "$hint.$count"
  }

  fun currentBlockLabel(): String {
    return currentBlock?.label ?: error("asking for current block label with no block")
  }


  private fun freshTempName(): String = "t${++nextRegisterId}"

  fun emit(instruction: IrInstruction, returnName: String? = null): IrLocal {
    val block = currentBlock ?: error("No current block")
    if (block.terminator != null) {
      CompileError.fail("", "block ${block.label} is already terminated")
    }
    return when (instruction) {
      is IrAlloca -> {
        val idInstruction = instruction.withId(returnName ?: freshTempName())
        val entry = entryBlock ?: currentFunction?.entryBlock("entry")
        if (entry == null) error("No entry block for current function")
        val insertAt = allocaInsertPos.coerceAtMost(entry.instructions.size)
        entry.instructions.add(insertAt, idInstruction)
        allocaInsertPos = insertAt + 1
        IrLocal(idInstruction.name, idInstruction.type)
      }

      is IrConst,
      is IrLoad,
      is IrStore,
      is IrBinary,
      is IrUnary,
      is IrCmp,
      is IrCall,
      is IrGep,
      is IrPhi,
      is IrCast -> {
        val idInstruction = instruction.withId(returnName ?: freshTempName())
        block.append(idInstruction)
        IrLocal(idInstruction.name, idInstruction.type)
      }

      is IrTerminator -> error("Use emitTerminator for terminators")
    }
  }

  fun emitTerminator(terminator: IrTerminator, returnName: String? = null) {
    val block = currentBlock ?: error("No current block")
    val termWithId = terminator.withId(returnName ?: freshTempName())
    block.setTerminator(termWithId)
    currentBlock = null
  }

  fun hasInsertionPoint(): Boolean = currentBlock != null


  fun borrow(toName: String?, baseValue: IrValue): IrLocal {
    val ret = emit(
      IrAlloca(toName ?: "", IrPointer(baseValue.type), baseValue.type), toName
    )
    emit(
      IrStore("", IrPrimitive(PrimitiveKind.UNIT), ret, baseValue)
    )
    return ret
  }

  /**
   * Copy an aggregate value from [srcPtr] to [destPtr] field-by-field.
   * For structs, emits a GEP+load+store per field (recursively for nested aggregates).
   * For arrays, emits element-wise copies.
   * For scalars, emits a single load+store.
   * For very large aggregates (> 256 bytes), falls back to a memcpy call.
   */
  fun emitAggregateCopy(destPtr: IrValue, srcPtr: IrValue, type: IrType) {
    val size = typeSize(type)
    if (size == 0) return

    // For very large aggregates, use memcpy call
    if (size > 256) {
      emitMemcpyCall(destPtr, srcPtr, size.toLong())
      return
    }

    when (type) {
      is IrStruct -> {
        type.fields.forEachIndexed { index, fieldType ->
          val i32 = IrPrimitive(PrimitiveKind.I32)
          val srcField = emit(
            IrGep(
              "", IrPointer(fieldType), srcPtr,
              listOf(IrConstant(0, i32), IrConstant(index.toLong(), i32))
            )
          )
          val dstField = emit(
            IrGep(
              "", IrPointer(fieldType), destPtr,
              listOf(IrConstant(0, i32), IrConstant(index.toLong(), i32))
            )
          )
          emitAggregateCopy(dstField, srcField, fieldType)
        }
      }

      is IrArray -> {
        val i32 = IrPrimitive(PrimitiveKind.I32)
        for (i in 0 until type.length) {
          val srcElem = emit(
            IrGep(
              "", IrPointer(type.element), srcPtr,
              listOf(IrConstant(0, i32), IrConstant(i.toLong(), i32))
            )
          )
          val dstElem = emit(
            IrGep(
              "", IrPointer(type.element), destPtr,
              listOf(IrConstant(0, i32), IrConstant(i.toLong(), i32))
            )
          )
          emitAggregateCopy(dstElem, srcElem, type.element)
        }
      }

      else -> {
        // Scalar: simple load + store
        val v = emit(IrLoad("", type, srcPtr))
        emit(IrStore("", IrPrimitive(PrimitiveKind.UNIT), destPtr, v))
      }
    }
  }

  /**
   * Emit an @llvm.memcpy call for large copies.
   */
  private fun emitMemcpyCall(destPtr: IrValue, srcPtr: IrValue, size: Long) {
    val i8Ptr = IrPointer(IrPrimitive(PrimitiveKind.CHAR))
    val i32 = IrPrimitive(PrimitiveKind.I32)
    val i1 = IrPrimitive(PrimitiveKind.BOOL)
    val memcpyType = IrFunctionType(listOf(i8Ptr, i8Ptr, i32, i1), IrPrimitive(PrimitiveKind.UNIT))
    val destCast = emit(IrCast("", i8Ptr, destPtr, CastKind.BITCAST))
    val srcCast = emit(IrCast("", i8Ptr, srcPtr, CastKind.BITCAST))
    val sizeConst = IrConstant(size, i32)
    val volatileConst = IrConstant(0, i1)
    emit(
      IrCall(
        "",
        IrPrimitive(PrimitiveKind.UNIT),
        IrFunctionRef("llvm.memcpy.p0.p0.i32", memcpyType),
        listOf(destCast, srcCast, sizeConst, volatileConst),
      )
    )
  }
}

// Extension to produce a copy with a new id for convenience.
@Suppress("UNCHECKED_CAST")
private fun <T : IrInstruction> T.withId(name: String): T = when (this) {
  is IrConst -> copy(name = name)
  is IrAlloca -> copy(name = name)
  is IrLoad -> copy(name = name)
  is IrStore -> copy(name = name)
  is IrBinary -> copy(name = name)
  is IrUnary -> copy(name = name)
  is IrCmp -> copy(name = name)
  is IrCall -> copy(name = name)
  is IrGep -> copy(name = name)
  is IrPhi -> copy(name = name)
  is IrCast -> copy(name = name)
  is IrReturn -> copy(name = name)
  is IrBranch -> copy(name = name)
  is IrJump -> copy(name = name)
} as T
