package frontend.semantic

import utils.CompileError

open class Scope {
    private val members = hashMapOf<String, Type>()
    private var parentScope: Scope? = null

    constructor(parent: Scope) {
        parentScope = parent
    }

    fun parentScope(): Scope? = parentScope
    fun defineVariable(name: String, type: Type) {
        if (members.containsKey(name)) {
            throw CompileError("Semantic:duplicated variable: $name")
        }
        members.put(name, type)
    }

    fun containsVariable(name: String, lookup: Boolean): Boolean {
        if (members.containsKey(name)) return true
        if (lookup) return parentScope?.containsVariable(name, lookup) ?: false
        else return false
    }

    fun getType(name: String, lookup: Boolean): Type? {
        if (members.containsKey(name)) return members[name]
        if (lookup) return parentScope?.getType(name, lookup)
        else return null
    }
}

class globalScope : Scope {
    constructor(parent: Scope) : super(parent)
}