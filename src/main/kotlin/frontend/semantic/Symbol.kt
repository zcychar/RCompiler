package frontend.semantic

sealed interface Symbol {
    val name: String
    val type: Type
}

data class TypeSymbol(override val name: String, override val type: Type) : Symbol

data class ValueSymbol(val mutable: Boolean, override val name: String, override val type: Type, val value: Any?) : Symbol
