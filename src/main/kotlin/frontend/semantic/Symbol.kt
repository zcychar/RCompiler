package frontend.semantic

import frontend.AST.ItemNode

enum class ResolutionState {
  UNRESOLVED,
  RESOLVING,
  RESOLVED
}

sealed interface Symbol {
  val name: String
}

interface MethodHolder {
  val inherentItems: MutableMap<String, Function>
  val traitImpls: MutableMap<TraitType, Map<String, Function>>
}


data class Variable(override val name: String, var type: Type, val isMutable: Boolean) : Symbol

data class Constant(
  override val name: String,
  val node: ItemNode, // Link to AST node
  var type: Type = ErrorType,
  var value: ConstValue? = null,
  var resolutionState: ResolutionState = ResolutionState.UNRESOLVED
) : Symbol

data class Function(
  override val name: String,
  val node: ItemNode, // Link to AST node
  var params: List<Type> = emptyList(),
  var returnType: Type = UnitType,
  var selfParam: Type? = null,
  var resolutionState: ResolutionState = ResolutionState.UNRESOLVED
) : Symbol

data class Struct(
  override val name: String,
  val node: ItemNode,
  val type: StructType,
  var resolutionState: ResolutionState = ResolutionState.UNRESOLVED
) : Symbol, MethodHolder {
  override val inherentItems = mutableMapOf<String, Function>()
  override val traitImpls = mutableMapOf<TraitType, Map<String, Function>>()
}

data class Enum(
  override val name: String,
  val node: ItemNode,
  val type: EnumType,
  var resolutionState: ResolutionState = ResolutionState.UNRESOLVED
) : Symbol, MethodHolder {
  override val inherentItems = mutableMapOf<String, Function>()
  override val traitImpls = mutableMapOf<TraitType, Map<String, Function>>()
}


data class Trait(override val name: String, val type: TraitType) : Symbol

data class BuiltIn(override val name: String, val type: Type) : Symbol

sealed interface ConstValue {
  data class Int(val value: Long) : ConstValue
  data class Bool(val value: Boolean) : ConstValue
  data class Char(val value: kotlin.Char) : ConstValue
  data class Str(val value: String) : ConstValue
  data class Array(val elements: List<ConstValue>) : ConstValue
  data class Struct(val type: StructType, val fields: Map<String, ConstValue>) : ConstValue
}


