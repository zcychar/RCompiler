package frontend.semantic

sealed interface Symbol {
    val name: String
}

interface MethodHolder {
    val inherentItems: MutableMap<String, Function>
    val traitImpls: MutableMap<TraitType, Map<String, Function>>
}

data class Variable(override val name: String, val type: Type, val isMutable: Boolean) : Symbol

data class Constant(override val name: String, var type: Type, var value: Any? = null) : Symbol

data class Function(
    override val name: String,
    var params: List<Type> = emptyList(),
    var returnType: Type = UnitType,
    val selfParam: Type? = null
) : Symbol

data class Struct(override val name: String, val type: StructType) : Symbol, MethodHolder {
    override val inherentItems = mutableMapOf<String, Function>()
    override val traitImpls = mutableMapOf<TraitType, Map<String, Function>>()
}

data class Enum(override val name: String, val type: EnumType) : Symbol, MethodHolder {
    override val inherentItems = mutableMapOf<String, Function>()
    override val traitImpls = mutableMapOf<TraitType, Map<String, Function>>()
}

data class Trait(override val name: String, val type: TraitType) : Symbol

data class BuiltIn(override val name: String, val type: Type) : Symbol
