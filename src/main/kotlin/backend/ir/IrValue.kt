package backend.ir

sealed interface IrValue {
    val type: IrType
    fun render(): String
}

data class IrRegister(val id: Int, override val type: IrType) : IrValue {
    override fun render(): String = "%$id"
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

data class IrBoolConstant(val value: Boolean, override val type: IrType) : IrConstant {
    override fun render(): String = if (value) "true" else "false"
}

data class IrCharConstant(val value: Int, override val type: IrType) : IrConstant {
    override fun render(): String = value.toString()
}

data class IrStringConstant(val bytes: String, override val type: IrType) : IrConstant {
    override fun render(): String = "\"$bytes\""
}

data class IrArrayConstant(val elements: List<IrConstant>, override val type: IrType) : IrConstant {
    override fun render(): String = "[${elements.joinToString(", ") { it.render() }}]"
}

data class IrStructConstant(val fields: List<IrConstant>, override val type: IrType) : IrConstant {
    override fun render(): String = "{${fields.joinToString(", ") { it.render() }}}"
}

data class IrBlockRef(val label: String, override val type: IrType) : IrValue {
    override fun render(): String = label
}

data class IrUndef(override val type: IrType) : IrValue {
    override fun render(): String = "undef"
}
