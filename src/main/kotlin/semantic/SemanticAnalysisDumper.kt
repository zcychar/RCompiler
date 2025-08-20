package semantic

/**
 * Utility to dump semantic analysis results for debugging
 */
object SemanticAnalysisDumper {
    
    fun dumpAnalysisResult(result: AnalysisResult): String {
        val builder = StringBuilder()
        
        builder.appendLine("===== SEMANTIC ANALYSIS RESULT =====")
        builder.appendLine()
        
        // Dump errors
        if (result.errors.isNotEmpty()) {
            builder.appendLine("ERRORS:")
            result.errors.forEach { error ->
                builder.appendLine("  - ${error.message}")
            }
            builder.appendLine()
        } else {
            builder.appendLine("No semantic errors found.")
            builder.appendLine()
        }
        
        // Dump global symbols
        builder.appendLine("GLOBAL SYMBOLS:")
        val globalSymbols = result.symbolTable.getGlobalScope().getAllSymbols()
        if (globalSymbols.isNotEmpty()) {
            globalSymbols.forEach { (name, symbol) ->
                builder.appendLine("  ${formatSymbol(symbol)}")
            }
        } else {
            builder.appendLine("  (no global symbols)")
        }
        builder.appendLine()
        
        builder.appendLine("======================================")
        
        return builder.toString()
    }
    
    private fun formatSymbol(symbol: Symbol): String {
        return when (symbol) {
            is VariableSymbol -> {
                val mutability = if (symbol.isMutable) "mut " else ""
                val initialization = if (symbol.isInitialized) " (initialized)" else " (uninitialized)"
                "${mutability}${symbol.name}: ${symbol.type.getName()}${initialization}"
            }
            is FunctionSymbol -> {
                val constness = if (symbol.isConst) "const " else ""
                val params = symbol.parameters.joinToString(", ") { "${it.name}: ${it.type.getName()}" }
                val returnType = (symbol.type as FunctionType).returnType.getName()
                "${constness}fn ${symbol.name}(${params}) -> ${returnType}"
            }
            is TypeSymbol -> {
                val kind = symbol.typeKind.name.lowercase()
                val fields = if (symbol.fields.isNotEmpty()) {
                    " { ${symbol.fields.joinToString(", ") { "${it.name}: ${it.type.getName()}" }} }"
                } else ""
                "$kind ${symbol.name}${fields}"
            }
        }
    }
}