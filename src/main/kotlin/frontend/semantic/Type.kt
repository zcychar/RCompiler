package frontend.semantic

sealed interface Type
data object BooleanType: Type
data object NumericType: Type
data object TextualType: Type
data object ArrayType: Type
data object StructType: Type
data object EnumType: Type
data object ReferenceType: Type
data object UnitType: Type