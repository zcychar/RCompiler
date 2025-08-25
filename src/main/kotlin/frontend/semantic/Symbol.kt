package frontend.semantic

sealed interface Symbol {
    val name: String
    val type: Type
}

data class StructSymbol(override val name: String, override val type: StructType) : Symbol

data class EnumSymbol(override val name: String, override val type: EnumType) : Symbol

data class FunctionSymbol(override val name: String, override val type: FunctionType) : Symbol

data class ValueSymbol(val mutable: Boolean, override val name: String, override val type: Type, val value: Any?) : Symbol
