package frontend.semantic

import utils.CompileError

open class Scope {
  private val members = hashMapOf<String, Symbol>()
  private val types = hashMapOf<String, Type>()
  private var parentScope: Scope? = null

  constructor(parent: Scope?) {
    parentScope = parent
  }

  fun parentScope(): Scope? = parentScope
  fun define(name: String, symbol: Symbol) {
    if (members.containsKey(name)) {
      throw CompileError("Semantic:duplicated variable: $name")
    }
    members.put(name, symbol)
    types.put(name,symbol.type)
  }

  fun contain(name: String, lookup: Boolean): Boolean {
    if (members.containsKey(name)) return true
    return if (lookup) parentScope?.contain(name, true) ?: false
    else false
  }

  fun getSymbol(name: String, lookup: Boolean): Symbol? {
    if (members.containsKey(name)) return members[name]
    return if (lookup) parentScope?.getSymbol(name, true)
    else null
  }

  fun getTypefromName(name: String, lookup: Boolean): Type? {
    if (types.containsKey(name)) return types[name]
    return if (lookup) parentScope?.getTypefromName(name, true)
    else null
  }

  fun modifyType(name: String, type: Type) {
    types[name] = type
  }
}

fun globalScope(): Scope{
  val gScope = Scope(null)

  return gScope
}
