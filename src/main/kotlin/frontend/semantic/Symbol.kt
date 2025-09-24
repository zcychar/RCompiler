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
  var value: ConstValue? = null,
  var resolutionState: ResolutionState = ResolutionState.UNRESOLVED
) : Symbol

data class Function(
  override val name: String,
  val node: ItemNode?,
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

sealed interface ConstValue {
  data class Int(val value: Long) : ConstValue
  data class Bool(val value: Boolean) : ConstValue
  data class Char(val value: kotlin.Char) : ConstValue
  data class Str(val value: String) : ConstValue
  data class Array(val elements: List<ConstValue>, val elementType: Type) : ConstValue
  data class Struct(val type: StructType, val fields: Map<String, ConstValue>) : ConstValue
}

fun getTypeFromConst(const: ConstValue): Type {
  return when (const) {
    is ConstValue.Array -> ArrayType(const.elementType, const.elements.size)
    is ConstValue.Bool -> BoolType
    is ConstValue.Char -> CharType
    is ConstValue.Int -> Int32Type
    is ConstValue.Str -> RefType(StrType, false)
    is ConstValue.Struct -> const.type
  }
}

