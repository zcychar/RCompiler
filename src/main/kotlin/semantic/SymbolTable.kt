package semantic

/**
 * Represents a symbol in the symbol table
 */
sealed interface Symbol {
    val name: String
    val type: SemanticType
}

/**
 * Variable symbol
 */
data class VariableSymbol(
    override val name: String,
    override val type: SemanticType,
    val isMutable: Boolean,
    val isInitialized: Boolean = false
) : Symbol

/**
 * Function symbol  
 */
data class FunctionSymbol(
    override val name: String,
    override val type: FunctionType,
    val parameters: List<VariableSymbol>,
    val isConst: Boolean = false
) : Symbol

/**
 * Type symbol (for user-defined types)
 */
data class TypeSymbol(
    override val name: String,
    val typeKind: UserDefinedType.TypeKind,
    val fields: List<VariableSymbol> = emptyList() // for structs
) : Symbol {
    override val type: SemanticType = UserDefinedType(name, typeKind)
}

/**
 * Scope represents a lexical scope that can contain symbols
 */
class Scope(val parent: Scope? = null) {
    private val symbols = mutableMapOf<String, Symbol>()
    
    fun declare(symbol: Symbol): Boolean {
        if (symbols.containsKey(symbol.name)) {
            return false // Symbol already declared in this scope
        }
        symbols[symbol.name] = symbol
        return true
    }
    
    fun lookup(name: String): Symbol? {
        return symbols[name] ?: parent?.lookup(name)
    }
    
    fun lookupLocal(name: String): Symbol? {
        return symbols[name]
    }
    
    fun getAllSymbols(): Map<String, Symbol> {
        return symbols.toMap()
    }
}

/**
 * Symbol table manages multiple scopes and symbol resolution
 */
class SymbolTable {
    private var currentScope: Scope = Scope() // Global scope
    private val scopes = mutableListOf<Scope>()
    
    init {
        scopes.add(currentScope)
        // Add built-in types to global scope
        initBuiltinTypes()
    }
    
    private fun initBuiltinTypes() {
        // Add primitive types as type symbols in global scope
        PrimitiveType.entries.forEach { primitiveType ->
            val typeSymbol = TypeSymbol(primitiveType.typeName, UserDefinedType.TypeKind.STRUCT)
            currentScope.declare(typeSymbol)
        }
    }
    
    fun enterScope(): Scope {
        val newScope = Scope(currentScope)
        currentScope = newScope
        scopes.add(newScope)
        return newScope
    }
    
    fun exitScope(): Scope? {
        val parent = currentScope.parent
        if (parent != null) {
            currentScope = parent
            return parent
        }
        return null
    }
    
    fun declare(symbol: Symbol): Boolean {
        return currentScope.declare(symbol)
    }
    
    fun lookup(name: String): Symbol? {
        return currentScope.lookup(name)
    }
    
    fun lookupLocal(name: String): Symbol? {
        return currentScope.lookupLocal(name)
    }
    
    fun getCurrentScope(): Scope {
        return currentScope
    }
    
    fun getGlobalScope(): Scope {
        return scopes[0]
    }
    
    fun isInGlobalScope(): Boolean {
        return currentScope.parent == null
    }
}