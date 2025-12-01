package backend.ir

import utils.CompileError

class IrBasicBlock(val label: String) {
    val instructions: MutableList<IrInstruction> = mutableListOf()
    var terminator: IrTerminator? = null
        private set

    fun append(instruction: IrInstruction) {
        if (terminator != null) {
            CompileError.fail("", "block $label is already terminated")
        }
        instructions.add(instruction)
    }

    fun setTerminator(term: IrTerminator) {
        if (terminator != null) {
            CompileError.fail("", "terminator already set for block $label")
        }
        terminator = term
    }
}
