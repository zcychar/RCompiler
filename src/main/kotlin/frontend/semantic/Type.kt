package frontend.semantic

sealed interface Type {
    val methods: HashMap<String, Type>
}

data object BooleanType : Type {
    override val methods: HashMap<String, Type> = hashMapOf()
}

data object IntType : Type {
    override val methods: HashMap<String, Type> = hashMapOf()
}

data object UnsignedIntType : Type {
    override val methods: HashMap<String, Type> = hashMapOf()
}


data object CharType : Type {
    override val methods: HashMap<String, Type> = hashMapOf()
}

data object StrType : Type {
    override val methods: HashMap<String, Type> = hashMapOf()
}

data class StructType(val name: String, val fields: List<StructFields>) : Type {
    data class StructFields(val name: String, val type: Type)

    override val methods: HashMap<String,Type> = hashMapOf()
}

data class EnumType(val name: String, val variants: List<String>) : Type {
    override val methods: HashMap<String,Type> = hashMapOf()
}

//In fact, these types won't be part of name-type lookout
data class ArrayType(val type: Type, val length: Int) : Type {
    override val methods: HashMap<String,Type> = hashMapOf()
}

data class ReferenceType(val mutable: Boolean, val type: Type) : Type {
    override val methods: HashMap<String,Type> = hashMapOf()
}

data object UnitType : Type {
    override val methods: HashMap<String,Type> = hashMapOf()
}

data object InferredType : Type {
    override val methods: HashMap<String,Type> = hashMapOf()
}

data class FunctionType(val params: List<Type>, val ret: Type) : Type {
    override val methods: HashMap<String,Type> = hashMapOf()
}