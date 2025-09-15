package frontend.semantic

import frontend.AST.*
import utils.CompileError

sealed interface Type {
    fun isEquivalentTo(other: Type): Boolean
    fun findMethod(name: String): FunctionSymbol? = null
}

object ErrorType : Type { override fun isEquivalentTo(other: Type) = false }
object UnitType : Type { override fun isEquivalentTo(other: Type) = other is UnitType }
object BoolType : Type { override fun isEquivalentTo(other: Type) = other is BoolType }
object CharType : Type { override fun isEquivalentTo(other: Type) = other is CharType }

object Int32Type : Type {
    private val methods = mapOf(
        "to_string" to FunctionSymbol("to_string", returnType = StringType)
    )
    override fun isEquivalentTo(other: Type) = other is Int32Type
    override fun findMethod(name: String): FunctionSymbol? = methods[name]
}

object UInt32Type : Type {
    private val methods = mapOf(
        "to_string" to FunctionSymbol("to_string", returnType = StringType)
    )
    override fun isEquivalentTo(other: Type) = other is UInt32Type
    override fun findMethod(name: String): FunctionSymbol? = methods[name]
}

object ISizeType : Type { override fun isEquivalentTo(other: Type) = other is ISizeType }
object USizeType : Type { override fun isEquivalentTo(other: Type) = other is USizeType }

data class RefType(val baseType: Type, val isMutable: Boolean) : Type {
    override fun isEquivalentTo(other: Type): Boolean {
        if (other !is RefType) return false
        return this.baseType.isEquivalentTo(other.baseType)
    }
    override fun findMethod(name: String): FunctionSymbol? {
        return baseType.findMethod(name)
    }
}

object StringType : Type {
    private val methods = mapOf(
        "len" to FunctionSymbol("len", returnType = USizeType),
        "as_str" to FunctionSymbol("as_str", returnType = RefType(StrType, false))
    )
    override fun isEquivalentTo(other: Type) = other is StringType
    override fun findMethod(name: String): FunctionSymbol? = methods[name]
}


object StrType : Type {
    private val methods = mapOf(
        "len" to FunctionSymbol("len", returnType = USizeType)
    )
    override fun isEquivalentTo(other: Type) = other is StrType
    override fun findMethod(name: String): FunctionSymbol? = methods[name]
}

data class ArrayType(val elementType: Type, val size: Int) : Type {
    private val methods = mapOf(
        "len" to FunctionSymbol("len", returnType = USizeType)
    )
    override fun isEquivalentTo(other: Type): Boolean {
        if (other !is ArrayType) return false
        return this.size == other.size && this.elementType.isEquivalentTo(other.elementType)
    }
    override fun findMethod(name: String): FunctionSymbol? = methods[name]
}


data class StructType(
    val name: String,
    var fields: Map<String, Type> = emptyMap(),
    val inherentItems: MutableMap<String, FunctionSymbol> = mutableMapOf(),
    val traitImpls: MutableMap<TraitType, Map<String, FunctionSymbol>> = mutableMapOf()
) : Type {
    override fun isEquivalentTo(other: Type): Boolean = other is StructType && this.name == other.name
    override fun findMethod(name: String): FunctionSymbol? {
        inherentItems[name]?.let { return it }
        val foundInTraits = traitImpls.values.mapNotNull { it[name] }
        if (foundInTraits.size > 1) {
            CompileError("Ambiguous method call '$name' for struct '${this.name}'.")
            return null
        }
        return foundInTraits.firstOrNull()
    }
}


data class EnumType(
    val name: String,
    val variants: Set<String>,
    val inherentItems: MutableMap<String, FunctionSymbol> = mutableMapOf(),
    val traitImpls: MutableMap<TraitType, Map<String, FunctionSymbol>> = mutableMapOf()
) : Type {
    override fun isEquivalentTo(other: Type): Boolean = other is EnumType && this.name == other.name
    override fun findMethod(name: String): FunctionSymbol? {

        inherentItems[name]?.let { return it }
        val foundInTraits = traitImpls.values.mapNotNull { it[name] }
        if (foundInTraits.size > 1) {
            CompileError("Ambiguous method call '$name' for enum '${this.name}'.")
            return null
        }
        return foundInTraits.firstOrNull()
    }
}

data class TraitType(val name: String, var associatedItems: Map<String, FunctionSymbol> = emptyMap()) : Type {
    override fun isEquivalentTo(other: Type): Boolean = other is TraitType && this.name == other.name
}