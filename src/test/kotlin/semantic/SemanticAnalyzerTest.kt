package semantic

import frontend.AST.*
import frontend.Literal
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class SemanticAnalyzerTest {
    
    @Test
    fun testSimpleVariableDeclarationAndUsage() {
        // Create a simple AST: let x = 5; return x;
        val letStmt = LetStmtNode(
            pattern = IdentifierPatternNode(false, false, "x", null),
            type = null,
            expr = LiteralExprNode("5", Literal.INTEGER)
        )
        val returnStmt = ExprStmtNode(
            ReturnExprNode(IdentifierExprNode("x"))
        )
        val block = BlockExprNode(false, listOf(letStmt, returnStmt))
        val function = FunctionItemNode(
            false, "test", null, listOf(), null, block
        )
        val crate = CrateNode(listOf(function))
        
        val analyzer = SemanticAnalyzer()
        val result = analyzer.analyze(crate)
        
        // Check that the variable 'x' is properly declared and found
        assertTrue(result.errors.any { it.message!!.contains("Undeclared variable 'x'") } == false,
            "Variable 'x' should be declared and accessible")
    }
    
    @Test
    fun testUndeclaredVariable() {
        // Create AST that uses undeclared variable: return y;
        val returnStmt = ExprStmtNode(
            ReturnExprNode(IdentifierExprNode("y"))
        )
        val block = BlockExprNode(false, listOf(returnStmt))
        val function = FunctionItemNode(
            false, "test", null, listOf(), null, block
        )
        val crate = CrateNode(listOf(function))
        
        val analyzer = SemanticAnalyzer()
        val result = analyzer.analyze(crate)
        
        // Should have undeclared variable error
        assertTrue(result.hasErrors)
        assertTrue(result.errors.any { it.message!!.contains("Undeclared variable 'y'") })
    }
    
    @Test
    fun testFunctionDeclaration() {
        // Test empty function declaration
        val function = FunctionItemNode(
            false, "test", null, listOf(), 
            TypePathNode("i32", null), 
            BlockExprNode(false, listOf())
        )
        val crate = CrateNode(listOf(function))
        
        val analyzer = SemanticAnalyzer()
        val result = analyzer.analyze(crate)
        
        // Function should be declared in global scope
        val globalSymbols = result.symbolTable.getGlobalScope().getAllSymbols()
        assertTrue(globalSymbols.containsKey("test"))
        val functionSymbol = globalSymbols["test"] as FunctionSymbol
        assertEquals("test", functionSymbol.name)
    }
}