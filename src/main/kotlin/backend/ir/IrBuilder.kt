package backend.ir

import utils.CompileError

/**
 * Mutable façade for building SSA instructions. Tracks the current insertion point,
 * generates register ids per function, and offers deterministic name generation.
 */
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

    fun emit(instruction: IrInstruction): IrValue {
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
            is IrCast -> {
                val idInstruction = instruction.withId(nextRegisterId++)
                block.append(idInstruction)
                IrRegister(idInstruction.id, idInstruction.type)
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

    fun hasInsertionPoint(): Boolean = currentBlock != null
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
