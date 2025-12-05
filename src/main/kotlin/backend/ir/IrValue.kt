package backend.ir

sealed interface IrValue {
    val type: IrType
    fun render(): String
}

data class IrLocal(val name: String, override val type: IrType) : IrValue {
    override fun render(): String = "%$name"
}

data class IrParameter(
    val index: Int,
    val name: String,
    override val type: IrType,
) : IrValue {
    override fun render(): String = "%${name.ifEmpty { "arg$index" }}"
}

data class IrGlobalRef(val name: String, override val type: IrType) : IrValue {
    override fun render(): String = "@$name"
}

data class IrFunctionRef(val name: String, override val type: IrType) : IrValue {
    override fun render(): String = "@$name"
}

sealed interface IrConstant : IrValue

data class IrIntConstant(val value: Long, override val type: IrType) : IrConstant {
    override fun render(): String = value.toString()
}

data class IrUndef(override val type: IrType) : IrValue {
    override fun render(): String = "undef"
}

