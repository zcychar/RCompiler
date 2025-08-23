package frontend.semantic

sealed interface Type

data object BooleanType : Type
data class NumericType(val signed: Boolean) : Type
data object CharType : Type
data object StrType : Type
data class StructType(val name: String, val fields: List<StructFields>) : Type {
  data class StructFields(val name: String, val type: Type)
}
data class EnumType(val name: String, val variants: List<String>) : Type

//In fact, these types won't be part of name-type lookout
data class ArrayType(val type: Type, val length: Int) : Type
data class ReferenceType(val mutable: Boolean, val type: Type) : Type
data object UnitType : Type
data class FunctionType(val params:List<Type>,val ret:Type) : Type