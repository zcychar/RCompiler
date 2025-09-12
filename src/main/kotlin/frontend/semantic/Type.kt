package frontend.semantic

import frontend.AST.*

sealed interface Type

data object IntType : Type
data object UIntType : Type
data object BoolType : Type
data object CharType : Type
data object UnitType: Type
data object StringType: Type

data class RefType(val baseType:Type,val isMutable: Boolean): Type
class ArrayType:Type