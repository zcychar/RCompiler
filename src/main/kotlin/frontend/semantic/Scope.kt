package frontend.semantic

import utils.CompileError
import kotlin.system.exitProcess

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
        types.put(name, symbol.type)
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

    fun getTypeFromName(name: String, lookup: Boolean): Type? {
        if (types.containsKey(name)) return types[name]
        return if (lookup) parentScope?.getTypeFromName(name, true)
        else null
    }

    fun modifyType(name: String, type: Type) {
        types[name] = type
    }

    fun addMethodToType(name: String, methodName: String, type: FunctionType) {
        if (types.containsKey(name)) {
            types[name]!!.methods.put(methodName, type)
        } else parentScope?.addMethodToType(name, methodName, type)
            ?: throw CompileError("Semantic:trying to add a method $methodName to undefined type $name")
    }

}

fun globalScope(): Scope {
    val gScope = Scope(null)
    gScope.define(
        "String", StructSymbol("String", StructType("String", listOf(StructType.StructFields("val", StrType))))
    )

    gScope.modifyType("i32", IntType)
    gScope.modifyType("u32", UnsignedIntType)
    gScope.modifyType("isize", IntType)
    gScope.modifyType("usize", UnsignedIntType)
    gScope.modifyType("str", StrType)
    gScope.modifyType("char", CharType)
    gScope.modifyType("bool", BooleanType)

    gScope.addMethodToType(
        "u32",
        "toString",
        FunctionType(listOf(ReferenceType(true, UnsignedIntType)), gScope.getTypeFromName("String", true)!!)
    )
    gScope.addMethodToType(
        "usize",
        "toString",
        FunctionType(listOf(ReferenceType(true, UnsignedIntType)), gScope.getTypeFromName("String", true)!!)
    )




    gScope.define("print", FunctionSymbol("print", FunctionType(listOf(StrType), UnitType)))
    gScope.define("println", FunctionSymbol("println", FunctionType(listOf(StrType), UnitType)))
    gScope.define("printInt", FunctionSymbol("printInt", FunctionType(listOf(IntType), UnitType)))

    gScope.define(
        "getString", FunctionSymbol("getString", FunctionType(listOf(), gScope.getTypeFromName("String", true)!!))
    )
    gScope.define("getInt", FunctionSymbol("getString", FunctionType(listOf(), IntType)))
    gScope.define("exit", FunctionSymbol("exit", FunctionType(listOf(IntType), UnitType)))


    return gScope
}
