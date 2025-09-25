package frontend.semantic

import frontend.ast.ItemNode

enum class ResolutionState {
    UNRESOLVED,
    RESOLVING,
    RESOLVED
}

sealed interface Symbol {
    val name: String
}

interface AssociateHolder {
    val associateItems: MutableMap<String, Symbol>
    val methods: MutableMap<String, Function>
}


data class Variable(override val name: String, var type: Type, val isMutable: Boolean) : Symbol

data class Constant(
    override val name: String,
    val node: ItemNode?,
    var type: Type = ErrorType,
    var value: Value? = null,
    var resolutionState: ResolutionState = ResolutionState.UNRESOLVED
) : Symbol

data class Function(
    override val name: String,
    val node: ItemNode?,
    var self: Type? = null,
    var params: List<Variable> = emptyList(),
    var returnType: Type = UnitType,
    var selfParam: Type? = null,
) : Symbol

data class Struct(
    override val name: String,
    val node: ItemNode?,
    val type: StructType,
    var resolutionState: ResolutionState = ResolutionState.UNRESOLVED
) : Symbol, AssociateHolder {
    override val associateItems: MutableMap<String, Symbol> = mutableMapOf()
    override val methods: MutableMap<String, Function> = mutableMapOf()
}

data class Enum(
    override val name: String,
    val node: ItemNode?,
    val type: EnumType,
    var resolutionState: ResolutionState = ResolutionState.UNRESOLVED
) : Symbol, AssociateHolder {
    override val associateItems: MutableMap<String, Symbol> = mutableMapOf()
    override val methods: MutableMap<String, Function> = mutableMapOf()
}


data class Trait(
    override val name: String,
    val type: TraitType,
    val node: ItemNode?
) : Symbol

data class BuiltIn(override val name: String, val type: Type) : Symbol

sealed interface Value {
    data class Int(val value: Long) : Value
    data class Bool(val value: Boolean) : Value
    data class Char(val value: kotlin.Char) : Value
    data class Str(val value: String) : Value
    data class Array(val elements: List<Value>, val elementType: Type) : Value
    data class Struct(val type: StructType, val fields: Map<String, Value>) : Value
}

fun getTypeFromValue(value: Value): Type {
    return when (value) {
        is Value.Array -> ArrayType(value.elementType, value.elements.size)
        is Value.Bool -> BoolType
        is Value.Char -> CharType
        is Value.Int -> Int32Type
        is Value.Str -> RefType(StrType, false)
        is Value.Struct -> value.type
    }
}

