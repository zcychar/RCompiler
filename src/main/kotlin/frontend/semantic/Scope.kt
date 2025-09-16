package frontend.semantic

import utils.CompileError

enum class Namespace { TYPE, VALUE }

class Scope(val parent: Scope? = null, val description: String) {
    private val typeNamespace = mutableMapOf<String, Symbol>()
    private val valueNamespace = mutableMapOf<String, Symbol>()

    fun declare(symbol: Symbol, namespace: Namespace) {
        val table = when (namespace) {
            Namespace.TYPE -> typeNamespace
            Namespace.VALUE -> valueNamespace
        }
        if (table.containsKey(symbol.name)) {
            CompileError("Semantic Error: Symbol '${symbol.name}' already declared in the $namespace namespace of this scope '$description'.")
            return
        }
        table[symbol.name] = symbol
    }

    fun resolve(name: String, namespace: Namespace): Symbol? {
        val table = when (namespace) {
            Namespace.TYPE -> typeNamespace
            Namespace.VALUE -> valueNamespace
        }
        return table[name] ?: parent?.resolve(name, namespace)
    }

    fun parentScope(): Scope? = parent

}

fun toPrelude(): Scope {
    val preludeScope = Scope(null,"prelude")
    preludeScope.declare(BuiltIn("i32", Int32Type), Namespace.TYPE)
    preludeScope.declare(BuiltIn("u32", UInt32Type), Namespace.TYPE)
    preludeScope.declare(BuiltIn("isize", Int32Type), Namespace.TYPE)
    preludeScope.declare(BuiltIn("usize", UInt32Type), Namespace.TYPE)
    preludeScope.declare(BuiltIn("bool", BoolType), Namespace.TYPE)
    preludeScope.declare(BuiltIn("char", CharType), Namespace.TYPE)
    preludeScope.declare(BuiltIn("String", StringType), Namespace.TYPE)
    preludeScope.declare(BuiltIn("str", StrType), Namespace.TYPE)

    preludeScope.declare(Function("printlnInt", params = listOf(Int32Type), returnType = UnitType), Namespace.VALUE)
    preludeScope.declare(Function("getInt", params = emptyList(), returnType = Int32Type), Namespace.VALUE)
    preludeScope.declare(Function("print", params = listOf(RefType(StrType, false)), returnType = UnitType), Namespace.VALUE)
    preludeScope.declare(Function("println", params = listOf(RefType(StrType, false)), returnType = UnitType), Namespace.VALUE)
    preludeScope.declare(Function("getString", params = emptyList(), returnType = StringType), Namespace.VALUE)
    preludeScope.declare(Function("exit", params = listOf(Int32Type), returnType = UnitType), Namespace.VALUE)
    return preludeScope
}

object BuiltInMethods{
    private val arrayMethods = mapOf(
        "len" to Function("len", returnType = USizeType)
    )

    private val strMethods = mapOf(
        "len" to Function("len", returnType = USizeType)
    )

    private val stringMethods = mapOf(
        "len" to Function("len", returnType = USizeType),
        "as_str" to Function("as_str", returnType = RefType(StrType, false)),
    )

    private val i32Methods = mapOf(
        "to_string" to Function("to_string", returnType = StringType)
    )

    private val u32Methods = mapOf(
        "to_string" to Function("to_string", returnType = StringType)
    )


    fun findMethod(receiverType: Type, methodName: String): Function? {
        var baseType = receiverType
        while(baseType is RefType)baseType= baseType.baseType
        return when (baseType) {
            is ArrayType -> arrayMethods[methodName]
            is StrType -> strMethods[methodName]
            is StringType -> stringMethods[methodName]
            is Int32Type -> i32Methods[methodName]
            is UInt32Type -> u32Methods[methodName]
            else -> null
        }
    }
}