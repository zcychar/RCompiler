package frontend.semantic

sealed interface Symbol {
    val name: String
    val type: Type
}
