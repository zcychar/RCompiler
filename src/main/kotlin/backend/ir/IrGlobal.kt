package backend.ir

data class IrGlobal(
    val name: String,
    val type: IrType,
    val initializer: IrConstant,
    val alignment: Int? = null,
) {
    fun render(): String = buildString {
        append('@').append(name)
        append(" = constant ")
        type.appendTo(this)
        append(' ')
        append(initializer.render())
        alignment?.let {
            append(", align ").append(it)
        }
    }

    fun asValue(): IrGlobalRef = IrGlobalRef(name, type)
}
