package backend.codegen.riscv

// Defines machine functions, blocks, stack slots, and CFG helpers.

data class StackSlotInfo(

    val name: String,

    val size: Int,

    val alignment: Int,

    var offset: Int = 0,
)

class RvMachineBlock(val label: String) {

    val instructions: MutableList<RvInst> = mutableListOf()

    val successors: MutableList<RvMachineBlock> = mutableListOf()

    val predecessors: MutableList<RvMachineBlock> = mutableListOf()

    fun append(inst: RvInst) {
        instructions.add(inst)
    }

    fun insertAt(index: Int, inst: RvInst) {
        instructions.add(index, inst)
    }

    fun insertBeforeTerminator(inst: RvInst) {
        var pos = instructions.size
        while (pos > 0 && instructions[pos - 1].isTerminator()) {
            pos--
        }
        instructions.add(pos, inst)
    }

    fun removeIf(predicate: (RvInst) -> Boolean): Int {
        val sizeBefore = instructions.size
        instructions.removeAll(predicate)
        return sizeBefore - instructions.size
    }

    override fun toString(): String = "RvMachineBlock($label, ${instructions.size} insts)"
}

class RvMachineFunction(

    val name: String,
) {

    val blocks: MutableList<RvMachineBlock> = mutableListOf()

    val stackSlots: MutableList<StackSlotInfo> = mutableListOf()

    var frameSize: Int = 0

    val usedCalleeSaved: MutableSet<RvPhysReg> = mutableSetOf()

    var hasCalls: Boolean = false

    var outgoingArgAreaSize: Int = 0

    val vregWidths: MutableMap<Int, Int> = mutableMapOf()

    private var nextVregId: Int = 0

    fun newVreg(width: Int = 4): RvOperand.Reg {
        val id = nextVregId++
        vregWidths[id] = width
        return RvOperand.Reg(id, width)
    }

    fun vregCount(): Int = nextVregId

    fun createBlock(label: String): RvMachineBlock {
        val block = RvMachineBlock(label)
        blocks.add(block)
        return block
    }

    fun appendBlock(block: RvMachineBlock) {
        blocks.add(block)
    }

    fun findBlock(label: String): RvMachineBlock? =
        blocks.find { it.label == label }

    fun entryBlock(): RvMachineBlock =
        blocks.firstOrNull() ?: error("RvMachineFunction '$name' has no blocks")

    fun allocateStackSlot(name: String, size: Int, alignment: Int): Int {
        val index = stackSlots.size
        stackSlots.add(StackSlotInfo(name, size, alignment))
        return index
    }

    fun getStackSlot(index: Int): StackSlotInfo = stackSlots[index]

    fun rebuildCfgEdges() {
        val labelToBlock = blocks.associateBy { it.label }

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

    override fun toString(): String = buildString {
        append("RvMachineFunction($name, ${blocks.size} blocks, ")
        append("${stackSlots.size} slots, frame=$frameSize)")
    }

    fun debugRender(): String = buildString {
        appendLine("# function: $name")
        appendLine("$name:")
        for (block in blocks) {

            if (block.label != name) {
                appendLine("${block.label}:")
            }
            for (inst in block.instructions) {
                appendLine("    ${inst.render()}")
            }
        }
    }
}

fun RvInst.branchTargets(): List<String> = when (this) {
    is RvInst.J -> listOf(target)
    is RvInst.Branch -> listOf(target)
    is RvInst.Call -> emptyList()
    else -> emptyList()
}

fun RvInst.isTerminator(): Boolean = when (this) {
    is RvInst.J -> true
    is RvInst.Branch -> true
    is RvInst.Ret -> true
    else -> false
}

fun alignUp(value: Int, alignment: Int): Int {
    require(alignment > 0 && (alignment and (alignment - 1)) == 0) {
        "alignment must be a positive power of 2, got $alignment"
    }
    return (value + alignment - 1) and (alignment - 1).inv()
}
