package frontend.semantic

sealed interface Symbol {
    val name: String
}

interface MethodHolder {
    val inherentItems: MutableMap<String, Symbol>
    val traitImpls: MutableMap<TraitType, Map<String, Symbol>>
}

data class VariableSymbol(override val name: String, val type: Type, val isMutable: Boolean) : Symbol
data class ConstantSymbol(override val name: String, var type: Type, var value: Any? = null) : Symbol

data class FunctionSymbol(
    override val name: String,
    var params: List<Type> = emptyList(),
    var returnType: Type = UnitType,
    val selfParam: Type? = null // For methods
) : Symbol

data class StructSymbol(override val name: String, val type: StructType) : Symbol, MethodHolder {
    override val inherentItems = mutableMapOf<String, Symbol>()
    override val traitImpls = mutableMapOf<TraitType, Map<String, Symbol>>()
}

data class EnumSymbol(override val name: String, val type: EnumType) : Symbol, MethodHolder {
    override val inherentItems = mutableMapOf<String, Symbol>()
    override val traitImpls = mutableMapOf<TraitType, Map<String, Symbol>>()
}

data class TraitSymbol(override val name: String, val type: TraitType, var associatedItems: Map<String, Symbol> = emptyMap()) : Symbol

data class BuiltinTypeSymbol(override val name: String) : Symbol, MethodHolder {
    override val inherentItems = mutableMapOf<String, Symbol>()
    override val traitImpls = mutableMapOf<TraitType, Map<String, Symbol>>()
}

data class ConstructorSymbol(
    override val name: String,
    val constructedType: Type,
    val params: Map<String, Type>
) : Symbol
