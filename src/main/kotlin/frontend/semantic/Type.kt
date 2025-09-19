package frontend.semantic
sealed interface Type {
    fun isEquivalentTo(other: Type): Boolean
}

object ErrorType : Type { override fun isEquivalentTo(other: Type) = false }
object UnitType : Type { override fun isEquivalentTo(other: Type) = other is UnitType }
object BoolType : Type { override fun isEquivalentTo(other: Type) = other is BoolType }
object CharType : Type { override fun isEquivalentTo(other: Type) = other is CharType }
object Int32Type : Type { override fun isEquivalentTo(other: Type) = other is Int32Type }
object UInt32Type : Type { override fun isEquivalentTo(other: Type) = other is UInt32Type }
object StringType : Type { override fun isEquivalentTo(other: Type) = other is StringType } // 代表语言内置的 String
object StrType : Type { override fun isEquivalentTo(other: Type) = other is StrType }     // 代表 str 切片类型

data class RefType(val baseType: Type, val isMutable: Boolean) : Type {
    override fun isEquivalentTo(other: Type): Boolean {
        if (other !is RefType) return false
        return this.baseType.isEquivalentTo(other.baseType)
    }
}

data class ArrayType(val elementType: Type, val size: Int) : Type {
    override fun isEquivalentTo(other: Type): Boolean {
        if (other !is ArrayType) return false
        return this.size == other.size && this.elementType.isEquivalentTo(other.elementType)
    }
}

data class StructType(val name: String, var fields: Map<String, Type> = emptyMap()) : Type {
    override fun isEquivalentTo(other: Type): Boolean = other is StructType && this.name == other.name // 标称类型系统
}

data class EnumType(val name: String, val variants: Set<String>) : Type {
    override fun isEquivalentTo(other: Type): Boolean = other is EnumType && this.name == other.name
}

data class TraitType(val name: String, var associatedItems: Map<String, Function> = emptyMap()) : Type {
    override fun isEquivalentTo(other: Type): Boolean = other is TraitType && this.name == other.name
}