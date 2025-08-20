package semantic

import utils.CompileError

/**
 * Represents errors that occur during semantic analysis
 */
class SemanticError(message: String) : CompileError("SemanticAnalyzer: $message")

/**
 * Different types of semantic errors
 */
sealed class SemanticErrorType(val message: String) {
    class UndeclaredVariable(val name: String) : SemanticErrorType("Undeclared variable '$name'")
    class RedeclaredVariable(val name: String) : SemanticErrorType("Variable '$name' is already declared in this scope")
    class UndeclaredFunction(val name: String) : SemanticErrorType("Undeclared function '$name'")
    class TypeMismatch(val expected: String, val actual: String) : SemanticErrorType("Type mismatch: expected '$expected', found '$actual'")
    class InvalidReturnType(val expected: String, val actual: String) : SemanticErrorType("Invalid return type: expected '$expected', found '$actual'")
    class UnreachableCode : SemanticErrorType("Unreachable code")
    class InvalidBreak : SemanticErrorType("'break' outside of loop")
    class InvalidContinue : SemanticErrorType("'continue' outside of loop")
}