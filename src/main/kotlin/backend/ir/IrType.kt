package backend.ir

sealed interface IrType {
    fun render(): String
    fun appendTo(builder: StringBuilder) {
        builder.append(render())
    }
}

enum class PrimitiveKind(val llvmName: String) {
    BOOL("i1"),
    CHAR("i8"),
    I32("i32"),
    U32("i32"),
    ISIZE("i32"),
    USIZE("i32"),
    UNIT("void"),
    NEVER("void");
}

data class IrPrimitive(val kind: PrimitiveKind) : IrType {
    override fun render(): String = kind.llvmName
}

data class IrPointer(val pointee: IrType, val mutable: Boolean = false) : IrType {
    override fun render(): String = buildString {
        pointee.appendTo(this)
        append('*')
    }
}

data class IrArray(val element: IrType, val length: Int) : IrType {
    override fun render(): String = buildString {
        append('[')
        append(this@IrArray.length)
        append(" x ")
        element.appendTo(this)
        append(']')
    }
}

data class IrStruct(val name: String?, val fields: List<IrType>) : IrType {
    override fun render(): String = buildString {
        name?.let {
            append('%').append(it).append(" = ")
        }
        append('{')
        fields.forEachIndexed { index, field ->
            if (index > 0) append(", ")
            field.appendTo(this)
        }
        append('}')
    }
}

data class IrSlice(val element: IrType) : IrType {
    override fun render(): String = buildString {
        append("{ ")
        element.appendTo(this)
        append("* , i32 }")
    }
}

data class IrFunctionType(val parameters: List<IrType>, val returnType: IrType) : IrType {
    override fun render(): String = buildString {
        returnType.appendTo(this)
        append(" (")
        parameters.forEachIndexed { index, param ->
            if (index > 0) append(", ")
            param.appendTo(this)
        }
        append(')')
    }
}

data class IrOpaque(val name: String) : IrType {
    override fun render(): String = "%$name"
}
