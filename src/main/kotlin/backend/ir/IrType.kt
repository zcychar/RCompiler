package backend.ir

import frontend.semantic.*

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

data class IrPointer(val pointee: IrType) : IrType {
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
    override fun render(): String =
        name?.let { "%$it" } ?: renderBody()
    fun renderDefinition(): String {
        val structName = name ?: error("Cannot render definition for anonymous struct")
        return buildString {
            append('%').append(structName).append(" = type ")
            append(renderBody())
        }
    }

    private fun renderBody(): String = buildString {
        append('{')
        fields.forEachIndexed { index, field ->
            if (index > 0) append(", ")
            field.appendTo(this)
        }
        append('}')
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

fun isAggregate(type: IrType): Boolean = type is IrStruct || type is IrArray

fun toIrType(type: Type): IrType = when (type) {
    is BoolType -> IrPrimitive(PrimitiveKind.BOOL)
    is CharType -> IrPrimitive(PrimitiveKind.CHAR)
    is Int32Type -> IrPrimitive(PrimitiveKind.I32)
    is UInt32Type -> IrPrimitive(PrimitiveKind.U32)
    is ISizeType -> IrPrimitive(PrimitiveKind.ISIZE)
    is USizeType -> IrPrimitive(PrimitiveKind.USIZE)
    is UnitType -> IrPrimitive(PrimitiveKind.UNIT)
    is NeverType -> IrPrimitive(PrimitiveKind.NEVER)
    is IntType -> IrPrimitive(PrimitiveKind.I32)
    is StrType -> IrOpaque("str")
    is StringType -> IrStruct(
        "String",
        listOf(
            IrPointer(IrPrimitive(PrimitiveKind.CHAR)),
            IrPrimitive(PrimitiveKind.U32),
        ),
    )
    is RefType -> IrPointer(toIrType(type.baseType))
    is ArrayType -> IrArray(toIrType(type.elementType), type.size)
    is StructType -> structLayout(type)
    is EnumType -> IrPrimitive(PrimitiveKind.I32)
    is SelfType -> error("SelfType should be resolved before IR mapping")
    is ErrorType -> IrOpaque("error")
    else -> IrOpaque(type.toString())
}

fun structLayout(structType: StructType): IrStruct =
    IrStruct(structType.name, structType.fields.values.map { fieldType -> toIrType(fieldType) })
