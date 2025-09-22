package frontend.semantic

import utils.CompileError

enum class Namespace { TYPE, VALUE }

enum class ScopeKind {
    PRELUDE,
    GLOBAL,
    FUNCTION,
    TRAIT,
    IMPL,
    BLOCK
}

class Scope(val parent: Scope? = null,val kind: ScopeKind) {
    private val typeNamespace = mutableMapOf<String, Symbol>()
    private val valueNamespace = mutableMapOf<String, Symbol>()

    fun declare(symbol: Symbol, namespace: Namespace) {
        val table = when (namespace) {
            Namespace.TYPE -> typeNamespace
            Namespace.VALUE -> valueNamespace
        }
        if (table.containsKey(symbol.name)) {
            CompileError("Semantic Error: Symbol '${symbol.name}' already declared in the $namespace namespace of this scope.")
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
    val preludeScope = Scope(null,  ScopeKind.PRELUDE)
    preludeScope.declare(BuiltIn("i32", Int32Type), Namespace.TYPE)
    preludeScope.declare(BuiltIn("u32", UInt32Type), Namespace.TYPE)
    preludeScope.declare(BuiltIn("isize", Int32Type), Namespace.TYPE)
    preludeScope.declare(BuiltIn("usize", UInt32Type), Namespace.TYPE)
    preludeScope.declare(BuiltIn("bool", BoolType), Namespace.TYPE)
    preludeScope.declare(BuiltIn("char", CharType), Namespace.TYPE)
    preludeScope.declare(BuiltIn("String", StringType), Namespace.TYPE)
    preludeScope.declare(BuiltIn("str", StrType), Namespace.TYPE)

    preludeScope.declare(
        Function("printlnInt", params = listOf(Int32Type), returnType = UnitType, node = null),
        Namespace.VALUE
    )
    preludeScope.declare(Function("getInt", params = emptyList(), returnType = Int32Type, node = null), Namespace.VALUE)
    preludeScope.declare(
        Function(
            "print",
            params = listOf(RefType(StrType, false)),
            returnType = UnitType,
            node = null
        ), Namespace.VALUE
    )
    preludeScope.declare(
        Function(
            "println",
            params = listOf(RefType(StrType, false)),
            returnType = UnitType,
            node = null
        ), Namespace.VALUE
    )
    preludeScope.declare(
        Function("getString", params = emptyList(), returnType = StringType, node = null),
        Namespace.VALUE
    )
    preludeScope.declare(
        Function("exit", params = listOf(Int32Type), returnType = UnitType, node = null),
        Namespace.VALUE
    )
    return preludeScope
}

object BuiltInMethods {
    private val arrayMethods = mapOf(
        "len" to Function("len", returnType = UInt32Type, node = null)
    )

    private val strMethods = mapOf(
        "len" to Function("len", returnType = UInt32Type, node = null)
    )

    private val stringMethods = mapOf(
        "len" to Function("len", returnType = UInt32Type, node = null),
        "as_str" to Function("as_str", returnType = RefType(StrType, false), node = null),
    )

    private val i32Methods = mapOf(
        "to_string" to Function("to_string", returnType = StringType, node = null)
    )

    private val u32Methods = mapOf(
        "to_string" to Function("to_string", returnType = StringType, node = null)
    )


    fun findMethod(receiverType: Type, methodName: String): Function? {
        var baseType = receiverType
        while (baseType is RefType) baseType = baseType.baseType
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