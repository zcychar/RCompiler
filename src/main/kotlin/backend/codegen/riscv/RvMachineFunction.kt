package backend.codegen.riscv

/**
 * Information about a single stack slot allocated within a function's frame.
 *
 * Stack slots arise from two sources:
 * 1. **IR allocas** — local variables explicitly allocated on the stack by the IR.
 * 2. **Spill slots** — created by the register allocator when it cannot color a
 *    virtual register and must evict it to memory.
 *
 * During instruction selection, [offset] is left as 0. It is resolved during
 * frame finalization once all slots are known and the total frame size is computed.
 * After finalization, [offset] is the displacement from `sp` to the start of
 * this slot (i.e., the slot lives at address `sp + offset`).
 */
data class StackSlotInfo(
    /** Identifier for this slot (typically the original IR alloca name, or a generated spill name). */
    val name: String,

    /** Size of this slot in bytes. */
    val size: Int,

    /** Required alignment in bytes (must be a power of 2). */
    val alignment: Int,

    /** Offset from `sp` after frame finalization. 0 before finalization. */
    var offset: Int = 0,
)

/**
 * A machine-level basic block within an [RvMachineFunction].
 *
 * Corresponds 1:1 to an IR basic block. The [label] is emitted as an assembly
 * label (`<label>:`) at the start of the block. Instructions are ordered;
 * the last instruction(s) should be a terminator (branch / jump / ret).
 *
 * [predecessors] and [successors] form the machine-level CFG and are built
 * during instruction selection by inspecting branch/jump targets.
 */
class RvMachineBlock(val label: String) {

    /** Ordered list of machine instructions in this block. */
    val instructions: MutableList<RvInst> = mutableListOf()

    /** CFG successor blocks (targets of branches / jumps). */
    val successors: MutableList<RvMachineBlock> = mutableListOf()

    /** CFG predecessor blocks (blocks that branch / jump to this one). */
    val predecessors: MutableList<RvMachineBlock> = mutableListOf()

    /** Append an instruction to the end of this block. */
    fun append(inst: RvInst) {
        instructions.add(inst)
    }

    /** Insert an instruction at the given [index]. */
    fun insertAt(index: Int, inst: RvInst) {
        instructions.add(index, inst)
    }

    /**
     * Insert an instruction **before** the terminator(s) at the end of the block.
     *
     * A "terminator" is any [RvInst.J], [RvInst.Branch], or [RvInst.Ret].
     * If the block has no terminator, the instruction is appended at the end.
     * This is useful for inserting spill stores or callee-saved register
     * restores right before a branch/return.
     */
    fun insertBeforeTerminator(inst: RvInst) {
        var pos = instructions.size
        while (pos > 0 && instructions[pos - 1].isTerminator()) {
            pos--
        }
        instructions.add(pos, inst)
    }

    /**
     * Remove all instructions matching the given [predicate].
     * Returns the number of instructions removed.
     */
    fun removeIf(predicate: (RvInst) -> Boolean): Int {
        val sizeBefore = instructions.size
        instructions.removeAll(predicate)
        return sizeBefore - instructions.size
    }

    override fun toString(): String = "RvMachineBlock($label, ${instructions.size} insts)"
}

/**
 * A complete machine-level function, ready for register allocation and emission.
 *
 * Created by the instruction selector from an [backend.ir.IrFunction].
 * Contains an ordered list of [RvMachineBlock]s (the first block is the entry),
 * stack slot information, and metadata needed for frame layout and emission.
 */
class RvMachineFunction(
    /** The function's assembly symbol name (e.g., `"main"`, `"foo."`, `"Point.len."`). */
    val name: String,
) {
    /** Ordered list of basic blocks. The first block is the function entry point. */
    val blocks: MutableList<RvMachineBlock> = mutableListOf()

    /**
     * Stack slots for local variables and spills.
     * Populated during instruction selection (for IR allocas) and during
     * register allocation (for spill slots).
     */
    val stackSlots: MutableList<StackSlotInfo> = mutableListOf()

    /**
     * Total stack frame size in bytes (including saved registers, locals, spills,
     * and outgoing argument overflow area). Computed during frame finalization.
     * Must be a multiple of 16 for ABI compliance.
     */
    var frameSize: Int = 0

    /**
     * Set of callee-saved physical registers that this function uses and therefore
     * must save in the prologue and restore in the epilogue.
     * Populated after register allocation.
     */
    val usedCalleeSaved: MutableSet<RvPhysReg> = mutableSetOf()

    /**
     * Whether this function contains any `call` instructions.
     * If true, `ra` must be saved/restored and caller-saved registers are
     * clobbered at call sites.
     */
    var hasCalls: Boolean = false

    /**
     * The maximum number of outgoing arguments that overflow beyond `a0–a7`
     * (i.e., arguments that must be passed on the stack). Determines the
     * size of the outgoing argument area at the bottom of the frame.
     * Measured in **bytes** (each overflow arg occupies 4 bytes on RV32).
     */
    var outgoingArgAreaSize: Int = 0

    // -------------------------------------------------------------------
    //  Virtual-register ID allocator
    // -------------------------------------------------------------------

    private var nextVregId: Int = 0

    /**
     * Allocate a fresh virtual register ID.
     * [width] is the data width in bytes (1 for byte, 4 for word/pointer).
     */
    fun newVreg(width: Int = 4): RvOperand.Reg {
        return RvOperand.Reg(nextVregId++, width)
    }

    /** Return the total number of virtual registers allocated so far. */
    fun vregCount(): Int = nextVregId

    // -------------------------------------------------------------------
    //  Block management
    // -------------------------------------------------------------------

    /** Create a new block with the given [label], append it, and return it. */
    fun createBlock(label: String): RvMachineBlock {
        val block = RvMachineBlock(label)
        blocks.add(block)
        return block
    }

    /** Append a pre-existing block to this function. */
    fun appendBlock(block: RvMachineBlock) {
        blocks.add(block)
    }

    /** Look up a block by its label, or `null` if not found. */
    fun findBlock(label: String): RvMachineBlock? =
        blocks.find { it.label == label }

    /** The entry block (first block in the function). Throws if no blocks exist. */
    fun entryBlock(): RvMachineBlock =
        blocks.firstOrNull() ?: error("RvMachineFunction '$name' has no blocks")

    // -------------------------------------------------------------------
    //  Stack slot management
    // -------------------------------------------------------------------

    /**
     * Allocate a new stack slot and return its index in [stackSlots].
     * The [StackSlotInfo.offset] is left at 0; it will be resolved during
     * frame finalization.
     */
    fun allocateStackSlot(name: String, size: Int, alignment: Int): Int {
        val index = stackSlots.size
        stackSlots.add(StackSlotInfo(name, size, alignment))
        return index
    }

    /** Return the [StackSlotInfo] at the given [index]. */
    fun getStackSlot(index: Int): StackSlotInfo = stackSlots[index]

    // -------------------------------------------------------------------
    //  CFG edge helpers
    // -------------------------------------------------------------------

    /**
     * Build or rebuild the [RvMachineBlock.predecessors] and [RvMachineBlock.successors]
     * lists for every block by inspecting the terminator instructions.
     *
     * This should be called after instruction selection is complete, and again
     * after any transformation that modifies control flow (e.g., critical edge
     * splitting during spill code insertion).
     */
    fun rebuildCfgEdges() {
        val labelToBlock = blocks.associateBy { it.label }

        // Clear existing edges.
        for (block in blocks) {
            block.successors.clear()
            block.predecessors.clear()
        }

        for (block in blocks) {
            for (inst in block.instructions) {
                val targets = inst.branchTargets()
                for (target in targets) {
                    val succ = labelToBlock[target] ?: continue
                    if (succ !in block.successors) {
                        block.successors.add(succ)
                    }
                    if (block !in succ.predecessors) {
                        succ.predecessors.add(block)
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------
    //  Debug / display
    // -------------------------------------------------------------------

    override fun toString(): String = buildString {
        append("RvMachineFunction($name, ${blocks.size} blocks, ")
        append("${stackSlots.size} slots, frame=$frameSize)")
    }

    /**
     * Render the entire function as assembly text (for debugging before
     * the final emission pass). Virtual registers appear as `v0`, `v1`, etc.
     */
    fun debugRender(): String = buildString {
        appendLine("# function: $name")
        appendLine("$name:")
        for (block in blocks) {
            // Don't re-emit the entry label if it matches the function name.
            if (block.label != name) {
                appendLine("${block.label}:")
            }
            for (inst in block.instructions) {
                appendLine("    ${inst.render()}")
            }
        }
    }
}

// ===========================================================================
//  Extension helpers
// ===========================================================================

/**
 * Return the list of branch/jump target labels for this instruction.
 * Empty for non-control-flow instructions.
 */
fun RvInst.branchTargets(): List<String> = when (this) {
    is RvInst.J -> listOf(target)
    is RvInst.Branch -> listOf(target)
    is RvInst.Call -> emptyList()   // call returns; not a branch
    else -> emptyList()
}

/**
 * Return `true` if this instruction is a block terminator
 * (unconditional jump, conditional branch, or return).
 */
fun RvInst.isTerminator(): Boolean = when (this) {
    is RvInst.J -> true
    is RvInst.Branch -> true
    is RvInst.Ret -> true
    else -> false
}

/**
 * Align [value] upward to the nearest multiple of [alignment].
 * [alignment] must be a positive power of 2.
 */
fun alignUp(value: Int, alignment: Int): Int {
    require(alignment > 0 && (alignment and (alignment - 1)) == 0) {
        "alignment must be a positive power of 2, got $alignment"
    }
    return (value + alignment - 1) and (alignment - 1).inv()
}
