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