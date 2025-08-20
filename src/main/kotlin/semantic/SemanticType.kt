package semantic

/**
 * Represents types in the semantic analysis phase
 */
sealed interface SemanticType {
    fun getName(): String
}

/**
 * Built-in primitive types
 */
enum class PrimitiveType(val typeName: String) : SemanticType {
    I32("i32"),
    I64("i64"),
    U32("u32"),
    U64("u64"),
    F32("f32"),
    F64("f64"),
    BOOL("bool"),
    CHAR("char"),
    STR("str"),
    UNIT("()");  // unit type for functions that don't return a value

    override fun getName(): String = typeName

    companion object {
        fun fromString(typeName: String): PrimitiveType? {
            return entries.find { it.typeName == typeName }
        }
    }
}

/**
 * Reference types (&T, &mut T)
 */
data class ReferenceType(
    val referencedType: SemanticType,
    val isMutable: Boolean
) : SemanticType {
    override fun getName(): String = "${if (isMutable) "&mut " else "&"}${referencedType.getName()}"
}

/**
 * Array types [T; N]
 */
data class ArrayType(
    val elementType: SemanticType,
    val size: Int?
) : SemanticType {
    override fun getName(): String = if (size != null) "[${elementType.getName()}; $size]" else "[${elementType.getName()}]"
}

/**
 * Function types
 */
data class FunctionType(
    val paramTypes: List<SemanticType>,
    val returnType: SemanticType
) : SemanticType {
    override fun getName(): String = "fn(${paramTypes.joinToString(", ") { it.getName() }}) -> ${returnType.getName()}"
}

/**
 * User-defined types (structs, enums)
 */
data class UserDefinedType(
    val typeName: String,
    val kind: TypeKind
) : SemanticType {
    override fun getName(): String = typeName
    
    enum class TypeKind {
        STRUCT, ENUM, TRAIT
    }
}

/**
 * Unknown or inferred types
 */
data object UnknownType : SemanticType {
    override fun getName(): String = "<unknown>"
}

/**
 * Type utility functions
 */
object TypeUtils {
    fun isNumericType(type: SemanticType): Boolean {
        return type is PrimitiveType && type in setOf(
            PrimitiveType.I32, PrimitiveType.I64, 
            PrimitiveType.U32, PrimitiveType.U64,
            PrimitiveType.F32, PrimitiveType.F64
        )
    }
    
    fun isIntegerType(type: SemanticType): Boolean {
        return type is PrimitiveType && type in setOf(
            PrimitiveType.I32, PrimitiveType.I64, 
            PrimitiveType.U32, PrimitiveType.U64
        )
    }
    
    fun isFloatType(type: SemanticType): Boolean {
        return type is PrimitiveType && type in setOf(PrimitiveType.F32, PrimitiveType.F64)
    }
    
    fun canAssign(from: SemanticType, to: SemanticType): Boolean {
        // Basic type compatibility check - can be expanded with more sophisticated rules
        return from == to || (from == UnknownType) || (to == UnknownType)
    }
}