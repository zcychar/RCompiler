package semantic

import frontend.AST.*
import frontend.Keyword
import frontend.Literal
import frontend.Punctuation
import frontend.TokenType

/**
 * Main semantic analyzer that performs semantic analysis on the AST
 */
class SemanticAnalyzer {
    private val symbolTable = SymbolTable()
    private val errors = mutableListOf<SemanticError>()
    private var currentReturnType: SemanticType = PrimitiveType.UNIT
    private var inLoop = false
    private var hasExplicitReturn = false
    
    fun analyze(crate: CrateNode): AnalysisResult {
        errors.clear()
        
        try {
            // First pass: declare all items (functions, structs, etc.)
            crate.items.forEach { item ->
                declareItem(item)
            }
            
            // Second pass: analyze function bodies and expressions
            crate.items.forEach { item ->
                analyzeItem(item)
            }
            
        } catch (e: SemanticError) {
            errors.add(e)
        }
        
        return AnalysisResult(
            symbolTable = symbolTable,
            errors = errors.toList(),
            hasErrors = errors.isNotEmpty()
        )
    }
    
    private fun declareItem(item: ItemNode) {
        when (item) {
            is FunctionItemNode -> {
                val paramTypes = item.funParams.map { param ->
                    convertTypeNodeToSemanticType(param.type)
                }
                val returnType = item.returnType?.let { convertTypeNodeToSemanticType(it) } ?: PrimitiveType.UNIT
                val functionType = FunctionType(paramTypes, returnType)
                
                val paramSymbols = item.funParams.map { param ->
                    VariableSymbol(
                        name = extractPatternName(param.pattern),
                        type = convertTypeNodeToSemanticType(param.type),
                        isMutable = false // TODO: extract mutability from pattern
                    )
                }
                
                val functionSymbol = FunctionSymbol(
                    name = item.name,
                    type = functionType,
                    parameters = paramSymbols,
                    isConst = item.isConst
                )
                
                if (!symbolTable.declare(functionSymbol)) {
                    errors.add(SemanticError(SemanticErrorType.RedeclaredVariable(item.name).message))
                }
            }
            
            is StructItemNode -> {
                val fieldSymbols = item.fields.map { field ->
                    VariableSymbol(
                        name = field.name,
                        type = convertTypeNodeToSemanticType(field.type),
                        isMutable = false
                    )
                }
                
                val structSymbol = TypeSymbol(
                    name = item.name,
                    typeKind = UserDefinedType.TypeKind.STRUCT,
                    fields = fieldSymbols
                )
                
                if (!symbolTable.declare(structSymbol)) {
                    errors.add(SemanticError(SemanticErrorType.RedeclaredVariable(item.name).message))
                }
            }
            
            is EnumItemNode -> {
                val enumSymbol = TypeSymbol(
                    name = item.name,
                    typeKind = UserDefinedType.TypeKind.ENUM
                )
                
                if (!symbolTable.declare(enumSymbol)) {
                    errors.add(SemanticError(SemanticErrorType.RedeclaredVariable(item.name).message))
                }
            }
            
            is ConstItemNode -> {
                val constType = convertTypeNodeToSemanticType(item.type)
                val constSymbol = VariableSymbol(
                    name = item.id,
                    type = constType,
                    isMutable = false,
                    isInitialized = item.expr != null
                )
                
                if (!symbolTable.declare(constSymbol)) {
                    errors.add(SemanticError(SemanticErrorType.RedeclaredVariable(item.id).message))
                }
            }
            
            is TraitItemNode -> {
                val traitSymbol = TypeSymbol(
                    name = item.id,
                    typeKind = UserDefinedType.TypeKind.TRAIT
                )
                
                if (!symbolTable.declare(traitSymbol)) {
                    errors.add(SemanticError(SemanticErrorType.RedeclaredVariable(item.id).message))
                }
            }
            
            is ImplItemNode -> {
                // Implementation blocks don't create new symbols directly
                // but we'll analyze their items in the second pass
            }
        }
    }
    
    private fun analyzeItem(item: ItemNode) {
        when (item) {
            is FunctionItemNode -> {
                analyzeFunctionItem(item)
            }
            is ConstItemNode -> {
                if (item.expr != null) {
                    val expectedType = convertTypeNodeToSemanticType(item.type)
                    val actualType = analyzeExpression(item.expr)
                    if (!TypeUtils.canAssign(actualType, expectedType)) {
                        errors.add(SemanticError(SemanticErrorType.TypeMismatch(expectedType.getName(), actualType.getName()).message))
                    }
                }
            }
            // Other item types don't need body analysis for now
            else -> {}
        }
    }
    
    private fun analyzeFunctionItem(function: FunctionItemNode) {
        symbolTable.enterScope()
        
        // Set current function return type
        currentReturnType = function.returnType?.let { convertTypeNodeToSemanticType(it) } ?: PrimitiveType.UNIT
        hasExplicitReturn = false
        
        // Declare parameters in function scope
        function.funParams.forEach { param ->
            val paramSymbol = VariableSymbol(
                name = extractPatternName(param.pattern),
                type = convertTypeNodeToSemanticType(param.type),
                isMutable = false, // TODO: extract mutability from pattern
                isInitialized = true
            )
            
            if (!symbolTable.declare(paramSymbol)) {
                errors.add(SemanticError(SemanticErrorType.RedeclaredVariable(paramSymbol.name).message))
            }
        }
        
        // Analyze function body
        if (function.body != null) {
            val bodyType = analyzeBlockExpression(function.body)
            // If the function has explicit returns, don't check block return type
            if (!hasExplicitReturn && !TypeUtils.canAssign(bodyType, currentReturnType)) {
                errors.add(SemanticError(SemanticErrorType.InvalidReturnType(currentReturnType.getName(), bodyType.getName()).message))
            }
        }
        
        symbolTable.exitScope()
    }
    
    private fun analyzeExpression(expr: ExprNode): SemanticType {
        return when (expr) {
            is LiteralExprNode -> analyzeLiteralExpression(expr)
            is IdentifierExprNode -> analyzeIdentifierExpression(expr)
            is BinaryExprNode -> analyzeBinaryExpression(expr)
            is UnaryExprNode -> analyzeUnaryExpression(expr)
            is BlockExprNode -> analyzeBlockExpression(expr)
            is IfExprNode -> analyzeIfExpression(expr)
            is ReturnExprNode -> analyzeReturnExpression(expr)
            is BreakExprNode -> analyzeBreakExpression(expr)
            is ContinueExprNode -> analyzeContinueExpression()
            is CallExprNode -> analyzeCallExpression(expr)
            is FieldAccessExprNode -> analyzeFieldAccessExpression(expr)
            is ArrayExprNode -> analyzeArrayExpression(expr)
            is IndexExprNode -> analyzeIndexExpression(expr)
            is LoopExprNode -> analyzeLoopExpression(expr)
            is WhileExprNode -> analyzeWhileExpression(expr)
            else -> {
                // For unhandled expression types, return unknown type
                UnknownType
            }
        }
    }
    
    private fun analyzeLiteralExpression(expr: LiteralExprNode): SemanticType {
        return when (expr.type) {
            Literal.INTEGER -> PrimitiveType.I32 // Default to i32 for integer literals
            Literal.CHAR -> PrimitiveType.CHAR
            Literal.STRING -> PrimitiveType.STR
            else -> UnknownType
        }
    }
    
    private fun analyzeIdentifierExpression(expr: IdentifierExprNode): SemanticType {
        val symbol = symbolTable.lookup(expr.value)
        if (symbol == null) {
            errors.add(SemanticError(SemanticErrorType.UndeclaredVariable(expr.value).message))
            return UnknownType
        }
        return symbol.type
    }
    
    private fun analyzeBinaryExpression(expr: BinaryExprNode): SemanticType {
        val leftType = analyzeExpression(expr.lhs)
        val rightType = analyzeExpression(expr.rhs)
        
        return when (expr.op) {
            Punctuation.PLUS, Punctuation.MINUS, Punctuation.STAR, Punctuation.SLASH, Punctuation.PERCENT -> {
                if (TypeUtils.isNumericType(leftType) && TypeUtils.isNumericType(rightType)) {
                    // For now, assume result has the same type as left operand
                    leftType
                } else {
                    errors.add(SemanticError(SemanticErrorType.TypeMismatch("numeric", "${leftType.getName()} and ${rightType.getName()}").message))
                    UnknownType
                }
            }
            Punctuation.EQUAL_EQUAL, Punctuation.NOT_EQUAL, 
            Punctuation.LESS, Punctuation.LESS_EQUAL,
            Punctuation.GREATER, Punctuation.GREATER_EQUAL -> {
                PrimitiveType.BOOL
            }
            Punctuation.AND_AND, Punctuation.OR_OR -> {
                if (leftType == PrimitiveType.BOOL && rightType == PrimitiveType.BOOL) {
                    PrimitiveType.BOOL
                } else {
                    errors.add(SemanticError(SemanticErrorType.TypeMismatch("bool", "${leftType.getName()} and ${rightType.getName()}").message))
                    UnknownType
                }
            }
            else -> UnknownType
        }
    }
    
    private fun analyzeUnaryExpression(expr: UnaryExprNode): SemanticType {
        val operandType = analyzeExpression(expr.rhs)
        
        return when (expr.op) {
            Punctuation.MINUS -> {
                if (TypeUtils.isNumericType(operandType)) {
                    operandType
                } else {
                    errors.add(SemanticError(SemanticErrorType.TypeMismatch("numeric", operandType.getName()).message))
                    UnknownType
                }
            }
            Punctuation.BANG -> {
                if (operandType == PrimitiveType.BOOL) {
                    PrimitiveType.BOOL
                } else {
                    errors.add(SemanticError(SemanticErrorType.TypeMismatch("bool", operandType.getName()).message))
                    UnknownType
                }
            }
            else -> UnknownType
        }
    }
    
    private fun analyzeBlockExpression(expr: BlockExprNode): SemanticType {
        symbolTable.enterScope()
        
        var lastType: SemanticType = PrimitiveType.UNIT
        
        expr.stmts.forEach { stmt ->
            when (stmt) {
                is LetStmtNode -> analyzeLetStatement(stmt)
                is ExprStmtNode -> {
                    lastType = analyzeExpression(stmt.expr)
                }
                is ItemStmtNode -> {
                    declareItem(stmt.item)
                    analyzeItem(stmt.item)
                }
                is NullStmtNode -> {}
            }
        }
        
        symbolTable.exitScope()
        return lastType
    }
    
    private fun analyzeLetStatement(stmt: LetStmtNode) {
        val varName = extractPatternName(stmt.pattern)
        val declaredType = stmt.type?.let { convertTypeNodeToSemanticType(it) }
        
        val actualType = if (stmt.expr != null) {
            analyzeExpression(stmt.expr)
        } else {
            declaredType ?: UnknownType
        }
        
        if (declaredType != null && !TypeUtils.canAssign(actualType, declaredType)) {
            errors.add(SemanticError(SemanticErrorType.TypeMismatch(declaredType.getName(), actualType.getName()).message))
        }
        
        val variableSymbol = VariableSymbol(
            name = varName,
            type = declaredType ?: actualType,
            isMutable = false, // TODO: extract mutability from pattern
            isInitialized = stmt.expr != null
        )
        
        if (!symbolTable.declare(variableSymbol)) {
            errors.add(SemanticError(SemanticErrorType.RedeclaredVariable(varName).message))
        }
    }
    
    private fun analyzeIfExpression(expr: IfExprNode): SemanticType {
        // Analyze conditions
        expr.conds.forEach { cond ->
            val condType = analyzeExpression(cond.expr)
            if (condType != PrimitiveType.BOOL) {
                errors.add(SemanticError(SemanticErrorType.TypeMismatch("bool", condType.getName()).message))
            }
        }
        
        // Analyze then block
        val thenType = analyzeBlockExpression(expr.expr)
        
        // Analyze else block if present
        val elseType = when {
            expr.elseExpr != null -> analyzeBlockExpression(expr.elseExpr)
            expr.elseIf != null -> analyzeIfExpression(expr.elseIf)
            else -> PrimitiveType.UNIT
        }
        
        // Both branches should have compatible types
        if (!TypeUtils.canAssign(elseType, thenType)) {
            errors.add(SemanticError(SemanticErrorType.TypeMismatch(thenType.getName(), elseType.getName()).message))
        }
        
        return thenType
    }
    
    private fun analyzeReturnExpression(expr: ReturnExprNode): SemanticType {
        hasExplicitReturn = true
        val returnType = if (expr.expr != null) {
            analyzeExpression(expr.expr)
        } else {
            PrimitiveType.UNIT
        }
        
        if (!TypeUtils.canAssign(returnType, currentReturnType)) {
            errors.add(SemanticError(SemanticErrorType.InvalidReturnType(currentReturnType.getName(), returnType.getName()).message))
        }
        
        return PrimitiveType.UNIT // Return statement doesn't have a value
    }
    
    private fun analyzeBreakExpression(expr: BreakExprNode): SemanticType {
        if (!inLoop) {
            errors.add(SemanticError(SemanticErrorType.InvalidBreak().message))
        }
        
        return if (expr.expr != null) {
            analyzeExpression(expr.expr)
        } else {
            PrimitiveType.UNIT
        }
    }
    
    private fun analyzeContinueExpression(): SemanticType {
        if (!inLoop) {
            errors.add(SemanticError(SemanticErrorType.InvalidContinue().message))
        }
        return PrimitiveType.UNIT
    }
    
    private fun analyzeCallExpression(expr: CallExprNode): SemanticType {
        val funcType = analyzeExpression(expr.expr)
        
        if (funcType is FunctionType) {
            // Check parameter count
            if (expr.params.size != funcType.paramTypes.size) {
                errors.add(SemanticError("Function expects ${funcType.paramTypes.size} parameters, found ${expr.params.size}"))
                return UnknownType
            }
            
            // Check parameter types
            expr.params.forEachIndexed { index, param ->
                val paramType = analyzeExpression(param)
                val expectedType = funcType.paramTypes[index]
                if (!TypeUtils.canAssign(paramType, expectedType)) {
                    errors.add(SemanticError(SemanticErrorType.TypeMismatch(expectedType.getName(), paramType.getName()).message))
                }
            }
            
            return funcType.returnType
        } else {
            errors.add(SemanticError("Cannot call expression of non-function type ${funcType.getName()}"))
            return UnknownType
        }
    }
    
    private fun analyzeFieldAccessExpression(expr: FieldAccessExprNode): SemanticType {
        val objectType = analyzeExpression(expr.expr)
        
        // For now, just return unknown type - field access requires more sophisticated type system
        return UnknownType
    }
    
    private fun analyzeArrayExpression(expr: ArrayExprNode): SemanticType {
        if (expr.elements != null) {
            val elementTypes = expr.elements.map { analyzeExpression(it) }
            if (elementTypes.isNotEmpty()) {
                val firstType = elementTypes.first()
                // Check all elements have the same type
                elementTypes.forEach { elementType ->
                    if (!TypeUtils.canAssign(elementType, firstType)) {
                        errors.add(SemanticError(SemanticErrorType.TypeMismatch(firstType.getName(), elementType.getName()).message))
                    }
                }
                return ArrayType(firstType, elementTypes.size)
            }
        }
        
        return UnknownType
    }
    
    private fun analyzeIndexExpression(expr: IndexExprNode): SemanticType {
        val arrayType = analyzeExpression(expr.first)
        val indexType = analyzeExpression(expr.second)
        
        if (!TypeUtils.isIntegerType(indexType)) {
            errors.add(SemanticError(SemanticErrorType.TypeMismatch("integer", indexType.getName()).message))
        }
        
        return when (arrayType) {
            is ArrayType -> arrayType.elementType
            else -> {
                errors.add(SemanticError("Cannot index into non-array type ${arrayType.getName()}"))
                UnknownType
            }
        }
    }
    
    private fun analyzeLoopExpression(expr: LoopExprNode): SemanticType {
        val wasInLoop = inLoop
        inLoop = true
        val result = analyzeBlockExpression(expr.expr)
        inLoop = wasInLoop
        return result
    }
    
    private fun analyzeWhileExpression(expr: WhileExprNode): SemanticType {
        // Analyze conditions
        expr.conds.forEach { cond ->
            val condType = analyzeExpression(cond.expr)
            if (condType != PrimitiveType.BOOL) {
                errors.add(SemanticError(SemanticErrorType.TypeMismatch("bool", condType.getName()).message))
            }
        }
        
        val wasInLoop = inLoop
        inLoop = true
        val result = analyzeBlockExpression(expr.expr)
        inLoop = wasInLoop
        return result
    }
    
    private fun convertTypeNodeToSemanticType(typeNode: TypeNode): SemanticType {
        return when (typeNode) {
            is TypePathNode -> {
                typeNode.id?.let { typeName ->
                    PrimitiveType.fromString(typeName) ?: UserDefinedType(typeName, UserDefinedType.TypeKind.STRUCT)
                } ?: UnknownType
            }
            is RefTypeNode -> {
                ReferenceType(
                    referencedType = convertTypeNodeToSemanticType(typeNode.type),
                    isMutable = typeNode.hasMut
                )
            }
            is ArrayTypeNode -> {
                ArrayType(
                    elementType = convertTypeNodeToSemanticType(typeNode.type),
                    size = null // TODO: extract size from expression
                )
            }
            is SliceTypeNode -> {
                ArrayType(
                    elementType = convertTypeNodeToSemanticType(typeNode.type),
                    size = null
                )
            }
            is UnitTypeNode -> PrimitiveType.UNIT
        }
    }
    
    private fun extractPatternName(pattern: PatternNode): String {
        return when (pattern) {
            is IdentifierPatternNode -> pattern.id
            is LiteralPatternNode -> "_literal" // Placeholder for literal patterns
            is PathPatternNode -> pattern.path.seg1.id ?: "_path" // Extract identifier from path
            is WildcardPatternNode -> "_"
            is RefPatternNode -> extractPatternName(pattern.pattern)
        }
    }
}

/**
 * Result of semantic analysis
 */
data class AnalysisResult(
    val symbolTable: SymbolTable,
    val errors: List<SemanticError>,
    val hasErrors: Boolean
)