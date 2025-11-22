package backend.ir

import kotlin.text.buildString

data class IrFunctionSignature(
    val parameters: List<IrType>,
    val returnType: IrType,
) {
    fun render(): String = buildString {
        append(returnType.render())
        append(" (")
        parameters.forEachIndexed { index, param ->
            if (index > 0) append(", ")
            append(param.render())
        }
        append(')')
    }

    fun toFunctionPointer(): IrType = IrPointer(IrFunctionType(parameters, returnType))
}

class IrFunction(
    val name: String,
    val signature: IrFunctionSignature,
) {
    val blocks: MutableList<IrBasicBlock> = mutableListOf()

    fun createBlock(label: String): IrBasicBlock {
        val block = IrBasicBlock(label)
        blocks.add(block)
        return block
    }

    fun appendBlock(block: IrBasicBlock) {
        blocks.add(block)
    }

    fun entryBlock(): IrBasicBlock {
        if (blocks.isEmpty()) {
            createBlock("entry")
        }
        return blocks.first()
    }
}
