package backend.ir

import frontend.semantic.Function
import frontend.semantic.RefType


data class IrFunctionSignature(
    val parameters: List<IrType>,
    /**
     * Logical return type from the language. If [sretType] is set, the physical
     * return type becomes void and the value is written to the hidden sret pointer.
     */
    val returnType: IrType,
    val sretType: IrType? = null,
) {
    val actualReturnType: IrType
        get() = if (sretType != null) IrPrimitive(PrimitiveKind.UNIT) else returnType

    fun render(): String = buildString {
        append(actualReturnType.render())
        append(" (")
        parameters.forEachIndexed { index, param ->
            if (index > 0) append(", ")
            append(param.render())
        }
        append(')')
    }

    fun toFunctionPointer(): IrType = IrPointer(IrFunctionType(parameters, actualReturnType))
}

class IrFunction(
    val name: String,
    val signature: IrFunctionSignature,
    val parameterNames: List<String> = emptyList(),
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

    fun entryBlock(label:String): IrBasicBlock {
        if (blocks.isEmpty()) {
            createBlock(label)
        }
        return blocks.first()
    }

    fun render(): String = buildString {
        append("define ")
        append(signature.actualReturnType.render())
        append(" @")
        append(name)
        append('(')
        append(
            signature.parameters.mapIndexed { index, param ->
                val paramName = parameterNames.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: "arg$index"
                if (index == 0 && signature.sretType != null) {
                    "${param.render()} sret(${signature.sretType.render()}) %$paramName"
                } else {
                    "${param.render()} %$paramName"
                }
            }.joinToString(", "),
        )
        append(')')
        appendLine(" {")
        blocks.forEach { block ->
            append(block.label).appendLine(":")
            block.instructions.forEach { inst ->
                append("  ").appendLine(inst.render())
            }
            block.terminator?.let { term ->
                append("  ").appendLine(term.render())
            }
        }
        append('}')
    }
}
