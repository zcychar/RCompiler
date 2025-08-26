package frontend.semantic

sealed interface Type {
    val methods: MutableMap<String, Type>
}

data object BooleanType : Type {
    override val methods: MutableMap<String, Type> = mutableMapOf()
}

data object IntType : Type {
    override val methods: MutableMap<String, Type> = mutableMapOf()
}

data object UnsignedIntType : Type {
    override val methods: MutableMap<String, Type> = mutableMapOf()
}


data object CharType : Type {
    override val methods: MutableMap<String, Type> = mutableMapOf()
}

data object StrType : Type {
    override val methods: MutableMap<String, Type> = mutableMapOf(
        Pair(
            "len", FunctionType(
                FunctionType.SelfParam(ref = true, mutable = false, type = null), listOf(),
                UnsignedIntType
            )
        )
    )
}

data class StructType(val name: String, val fields: List<StructFields>) : Type {
    data class StructFields(val name: String, val type: Type)

    override val methods: MutableMap<String, Type> = mutableMapOf()
}

data class EnumType(val name: String, val variants: List<String>) : Type {
    override val methods: MutableMap<String, Type> = mutableMapOf()
}

//In fact, these types won't be part of name-type lookout
data class ArrayType(val type: Type, val length: Int) : Type {
    override val methods: MutableMap<String, Type> = mutableMapOf(
        Pair(
            "len", FunctionType(
                FunctionType.SelfParam(ref = true, mutable = false, type = null), listOf(),
                UnsignedIntType
            )
        )
    )
}

data class ReferenceType(val mutable: Boolean, val type: Type) : Type {
    override val methods: MutableMap<String, Type> = mutableMapOf()
}

data object UnitType : Type {
    override val methods: MutableMap<String, Type> = mutableMapOf()
}

data object InferredType : Type {
    override val methods: MutableMap<String, Type> = mutableMapOf()
}

data class FunctionType(val selfParam: SelfParam?, val funParams: List<Type>, val ret: Type) : Type {
    data class SelfParam(val ref: Boolean, val mutable: Boolean, val type: Type?)

    override val methods: MutableMap<String, Type> = mutableMapOf()
}

data object TraitType : Type {
    override val methods: MutableMap<String, Type> = mutableMapOf()
}