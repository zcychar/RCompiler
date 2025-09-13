package frontend.semantic

import frontend.AST.*

sealed interface Type

data object IntType : Type
data object UIntType : Type
data object BoolType : Type
data object CharType : Type
data object UnitType : Type
data object StringType : Type

data object ArrayArcheType : Type

data class RefType(val baseType: Type, val isMutable: Boolean) : Type

data class ArrayType(val elementType: Type, val size: Int) : Type

data class StructType(val name: String, var fields: Map<String, Type> = emptyMap()) : Type

data class EnumType(val name: String, val variants: Set<String>) : Type

data class TraitType(val name: String) : Type
