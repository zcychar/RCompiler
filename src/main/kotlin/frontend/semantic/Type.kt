package frontend.semantic

import frontend.ast.LiteralExprNode

sealed interface Type

object ErrorType : Type {
    override fun equals(other: Any?): Boolean = false
}

object UnitType : Type

object BoolType : Type

object CharType : Type

object Int32Type : Type

object UInt32Type : Type

object ISizeType : Type

object USizeType : Type


object IntType : Type

object StringType : Type

object StrType : Type

object NeverType : Type

data class RefType(val baseType: Type, val isMutable: Boolean) : Type

data class ArrayType(val elementType: Type, val size: Int) : Type

data class StructType(val name: String, var fields: Map<String, Type> = emptyMap()) : Type

data class EnumType(val name: String, var variants: Set<String>) : Type

data class TraitType(val name: String, var associatedItems: Map<String, Symbol> = emptyMap()) : Type

data class SelfType(val isMut: Boolean, val isRef: Boolean) : Type

fun isInt(type: Type): Boolean =
    type is UInt32Type || type is Int32Type || type is USizeType || type is ISizeType || type is IntType

fun getInt(expr: LiteralExprNode): ConstValue.Int {
    if (expr.value == null) {
        semanticError("Invalid integer in const expression")
    }
    var (numeric, type) = when {
        expr.value.endsWith("i32") -> Pair(expr.value.removeSuffix("i32"), Int32Type)
        expr.value.endsWith("u32") -> Pair(expr.value.removeSuffix("u32"), UInt32Type)
        expr.value.endsWith("isize") -> Pair(expr.value.removeSuffix("isize"), ISizeType)
        expr.value.endsWith("usize") -> Pair(expr.value.removeSuffix("usize"), USizeType)
        else -> Pair(expr.value, IntType)
    }
    numeric = numeric.replace("_", "")
    val number = when {
        numeric.startsWith("0x", ignoreCase = true) -> {
            numeric.substring(2).toLong(16)
        }

        numeric.startsWith("0b", ignoreCase = true) -> {
            numeric.substring(2).toLong(2)
        }

        numeric.startsWith("0o", ignoreCase = true) -> {
            numeric.substring(2).toLong(8)
        }

        else -> {
            numeric.toLong(10)
        }
    }
    if (number > 4294967296) {
        semanticError("Integer overflow")
    } else if (number > 2147483647) {
        type = UInt32Type
    }
    return ConstValue.Int(number, type)
}

fun unifyInt(lhs: Type, rhs: Type): Type {
    if (!isInt(lhs) || !isInt(rhs)) {
        semanticError("invalid integer type")
    }
    return when {
        lhs == rhs -> lhs
        lhs is IntType -> rhs
        rhs is IntType -> lhs
        else -> semanticError("cannot unify integer type $lhs and $rhs")
    }
}

fun canUnifyInt(lhs: Type, rhs: Type): Boolean {
    unifyInt(lhs, rhs)
    return true
}