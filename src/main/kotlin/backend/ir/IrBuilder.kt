package backend.ir

import utils.CompileError

class IrBuilder(
  private val module: IrModule,
) {
  private var currentFunction: IrFunction? = null
  private var currentBlock: IrBasicBlock? = null
  private var nextRegisterId: Int = 0
  private val nameCounters: MutableMap<String, Int> = mutableMapOf()

  fun positionAt(function: IrFunction, block: IrBasicBlock) {
    if (currentFunction !== function) {
      nextRegisterId = 0
      nameCounters.clear()
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

  fun currentBlockLabel(): String{
    return currentBlock?.label?:error("asking for current block label with no block")
  }

  fun freshFunctionName(): String {
    return ""
  }

  fun emit(instruction: IrInstruction, returnName: String? = null): IrLocal {
    val block = currentBlock ?: error("No current block")
    if (block.terminator != null) {
      CompileError.fail("", "block ${block.label} is already terminated")
    }
    return when (instruction) {
      is IrAlloca,
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
        val idInstruction = instruction.withId(returnName ?: (++nextRegisterId).toString())
        block.append(idInstruction)
        IrLocal(idInstruction.name, idInstruction.type)
      }

      is IrTerminator -> error("Use emitTerminator for terminators")
    }
  }

  fun emitTerminator(terminator: IrTerminator, returnName: String? = null) {
    val block = currentBlock ?: error("No current block")
    val termWithId = terminator.withId(returnName ?: (++nextRegisterId).toString())
    block.setTerminator(termWithId)
    currentBlock = null
  }

  fun hasInsertionPoint(): Boolean = currentBlock != null


  fun borrow( toName: String?, baseValue: IrValue ): IrLocal {
    val ret = emit(
      IrAlloca(toName?:"", IrPointer(baseValue.type), baseValue.type), toName
    )
    emit(
      IrStore("", IrPrimitive(PrimitiveKind.UNIT),ret,baseValue)
    )
    return ret
  }

  //naive
  fun copy(fromDst: IrValue,toDst: IrValue){
    if ( fromDst .type !is IrPointer )error("try loading from a non_pointer ptr!")
    val ret = emit(
      IrLoad("", (fromDst.type as IrPointer).pointee,fromDst)
    )
    emit(
      IrStore("", IrPrimitive(PrimitiveKind.UNIT),toDst,ret)
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
