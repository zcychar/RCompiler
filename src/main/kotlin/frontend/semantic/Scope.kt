package frontend.semantic

import utils.CompileError

open class Scope {
    private val members = hashMapOf<String, Type>()
    private var parentScope: Scope? = null

    constructor(parent: Scope?) {
        parentScope = parent
    }

    fun parentScope(): Scope? = parentScope
    fun define(name: String, type: Type) {
        if (members.containsKey(name)) {
            throw CompileError("Semantic:duplicated variable: $name")
        }
        members.put(name, type)
    }

    fun contain(name: String, lookup: Boolean): Boolean {
        if (members.containsKey(name)) return true
        if (lookup) return parentScope?.contain(name, lookup) ?: false
        else return false
    }

    fun getType(name: String, lookup: Boolean): Type? {
        if (members.containsKey(name)) return members[name]
        if (lookup) return parentScope?.getType(name, lookup)
        else return null
    }
}

class globalScope : Scope {
    private val types = hashMapOf<String, Type>()

    constructor(parent: Scope?) : super(parent)

    fun addType(name: String, type: Type) {
        if (types.containsKey(name)) throw CompileError("Semantic:duplicated type $name")
        types.put(name, type)
    }

    fun getTypeFromName(name: String): Type {
        if (types[name] == null) throw CompileError("Semantic:undefined type $name")
        else return types[name]!!
    }
}