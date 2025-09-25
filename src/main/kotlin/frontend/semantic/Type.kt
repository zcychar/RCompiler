package frontend.semantic

sealed interface Type {
  
}

object ErrorType : Type {
  override fun equals(other: Any?): Boolean = false
}

object UnitType : Type

object BoolType : Type

object CharType : Type

object Int32Type : Type

object UInt32Type : Type

object StringType : Type

object StrType : Type

data class RefType(val baseType: Type, val isMutable: Boolean) : Type

data class ArrayType(val elementType: Type, val size: Int) : Type

data class StructType(val name: String, var fields: Map<String, Type> = emptyMap()) : Type

data class EnumType(val name: String, var variants: Set<String>) : Type

data class TraitType(val name: String, var associatedItems: Map<String, Symbol> = emptyMap()) : Type

data class SelfType(val isMut: Boolean, val isRef: Boolean) : Type