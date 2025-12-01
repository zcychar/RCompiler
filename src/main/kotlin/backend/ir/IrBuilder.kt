package backend.ir

/**
 * Minimal IR builder for sequencing instructions within a function.
 * No PHI or CFG metadata; enforces single terminator per block and monotonic IDs.
 */
class IrBuilder {
    private var currentFunction: IrFunction? = null
    private var currentBlock: IrBasicBlock? = null
    private var nextRegisterId: Int = 0

    fun positionAt(function: IrFunction, block: IrBasicBlock) {
        currentFunction = function
        currentBlock = block
    }

    fun ensureBlock(name: String): IrBasicBlock {
        val fn = currentFunction ?: error("No current function")
        return fn.blocks.find { it.label == name } ?: fn.createBlock(name)
    }

    fun emit(instruction: IrInstruction): IrValue {
        val block = currentBlock ?: error("No current block")
        when (instruction) {
            is IrAlloca,
            is IrConst,
            is IrLoad,
            is IrStore,
            is IrBinary,
            is IrUnary,
            is IrCmp,
            is IrCall,
            is IrGep,
            is IrCast -> {
                val idInstruction = instruction.withId(nextRegisterId++)
                block.append(idInstruction)
                return IrRegister(idInstruction.id, idInstruction.type)
            }
            is IrTerminator -> error("Use emitTerminator for terminators")
        }
    }

    fun emitTerminator(terminator: IrTerminator) {
        val block = currentBlock ?: error("No current block")
        val termWithId = terminator.withId(nextRegisterId++)
        block.setTerminator(termWithId)
        currentBlock = null
    }
}

// Extension to produce a copy with a new id for convenience.
@Suppress("UNCHECKED_CAST")
private fun <T : IrInstruction> T.withId(id: Int): T = when (this) {
    is IrConst -> copy(id = id)
    is IrAlloca -> copy(id = id)
    is IrLoad -> copy(id = id)
    is IrStore -> copy(id = id)
    is IrBinary -> copy(id = id)
    is IrUnary -> copy(id = id)
    is IrCmp -> copy(id = id)
    is IrCall -> copy(id = id)
    is IrGep -> copy(id = id)
    is IrCast -> copy(id = id)
    is IrReturn -> copy(id = id)
    is IrBranch -> copy(id = id)
    is IrJump -> copy(id = id)
} as T
