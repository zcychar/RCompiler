package backend.ir

import frontend.semantic.Function
import frontend.semantic.RefType

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

    fun entryBlock(): IrBasicBlock {
        if (blocks.isEmpty()) {
            createBlock("entry")
        }
        return blocks.first()
    }

    fun render(): String = buildString {
        append("define ")
        append(signature.returnType.render())
        append(" @")
        append(name)
        append('(')
        append(
            signature.parameters.mapIndexed { index, param ->
                val paramName = parameterNames.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: "arg$index"
                "${param.render()} %$paramName"
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

fun irFunctionSignature(function: Function): IrFunctionSignature {
    val params = mutableListOf<IrType>()
    function.selfParam?.let {
        val rawSelf = function.self ?: error("method missing self target")
        val selfSemantic = if (it.isRef) RefType(rawSelf, it.isMut) else rawSelf
        params += toIrType(selfSemantic)
    }
    params += function.params.map { param -> toIrType(param.type) }
    val ret = toIrType(function.returnType)
    return IrFunctionSignature(params, ret)
}
