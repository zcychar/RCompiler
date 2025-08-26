package frontend.semantic

import utils.CompileError
import java.util.Vector
import kotlin.system.exitProcess

open class Scope {
    private val members = hashMapOf<String, Symbol>()

    //the difference between types and named_types lies in the process of reference types
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

    fun getCertainType(name: String): Type {
        return getTypeFromName(name, true) ?: throw CompileError("Semantic:Refering to unknown type $name")
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
        FunctionType(
            FunctionType.SelfParam(ref = true, mutable = false, type = null),
            listOf(),
            gScope.getCertainType("String")
        )
    )
    gScope.addMethodToType(
        "usize",
        "toString",
        FunctionType(
            FunctionType.SelfParam(ref = true, mutable = false, type = null),
            listOf(),
            gScope.getCertainType("String")
        )
    )

    gScope.define("print", FunctionSymbol("print", FunctionType(null, listOf(StrType), UnitType)))
    gScope.define("println", FunctionSymbol("println", FunctionType(null, listOf(StrType), UnitType)))
    gScope.define("printInt", FunctionSymbol("printInt", FunctionType(null, listOf(IntType), UnitType)))

    gScope.define(
        "getString", FunctionSymbol("getString", FunctionType(null, listOf(), gScope.getCertainType("String")))
    )
    gScope.define("getInt", FunctionSymbol("getString", FunctionType(null, listOf(), IntType)))
    gScope.define("exit", FunctionSymbol("exit", FunctionType(null, listOf(IntType), UnitType)))

    gScope.addMethodToType(
        "String",
        "len",
        FunctionType(
            FunctionType.SelfParam(ref = true, mutable = false, type = null), listOf(),
            UnsignedIntType
        )
    )
    gScope.addMethodToType(
        "String",
        "as_str",
        FunctionType(
            FunctionType.SelfParam(ref = true, mutable = false, type = null),
            listOf(), ReferenceType(false, StrType)
        )
    )
    gScope.addMethodToType(
        "String",
        "as_mut_str",
        FunctionType(
            FunctionType.SelfParam(ref = true, mutable = true, type = null),
            listOf(), ReferenceType(true, StrType)
        )
    )
    gScope.addMethodToType(
        "String",
        "from",
        FunctionType(
            null, listOf(ReferenceType(false, StrType)),
            gScope.getCertainType("String")
        )
    )
    gScope.addMethodToType(
        "String",
        "from",
        FunctionType(
            null, listOf(ReferenceType(true, StrType)),
            gScope.getCertainType("String")
        )
    )
    gScope.addMethodToType(
        "String",
        "append",
        FunctionType(
            FunctionType.SelfParam(ref = true, mutable = true, type = null),
            listOf(ReferenceType(false, StrType)),
            UnitType
        )
    )
    return gScope
}
