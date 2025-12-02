package backend.ir

import frontend.Literal
import frontend.Keyword
import frontend.Punctuation
import frontend.ast.BinaryExprNode
import frontend.ast.BorrowExprNode
import frontend.ast.LiteralExprNode
import frontend.ast.PathExprNode
import frontend.ast.ReturnExprNode
import frontend.ast.TypePathNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExprEmitterTest {

    private fun withEmitter(
        returnType: IrType = IrPrimitive(PrimitiveKind.UNIT),
        block: (ExprEmitter, IrFunction, CodegenContext) -> Unit,
    ) {
        val context = CodegenContext()
        val builder = context.builder
        val function = IrFunction("test", IrFunctionSignature(emptyList(), returnType))
        builder.positionAt(function, function.entryBlock())
        context.currentFunction = function
        context.valueEnv.pushFunction(returnType)
        context.valueEnv.enterScope()
        val emitter = ExprEmitter(context)
        try {
            block(emitter, function, context)
        } finally {
            context.valueEnv.leaveScope()
            context.valueEnv.popFunction()
            context.currentFunction = null
        }
    }

    @Test
    fun `emit integer addition`() = withEmitter(IrPrimitive(PrimitiveKind.I32)) { emitter, function, _ ->
        val expr = BinaryExprNode(
            Punctuation.PLUS,
            LiteralExprNode("1", Literal.INTEGER),
            LiteralExprNode("2", Literal.INTEGER),
        )
        val value = emitter.emitExpr(expr)

        assertIs<IrRegister>(value)
        assertEquals(IrPrimitive(PrimitiveKind.I32), value.type)

        val block = function.entryBlock()
        assertEquals(1, block.instructions.size)
        val binary = assertIs<IrBinary>(block.instructions.first())
        assertEquals(BinaryOperator.ADD, binary.operator)
    }

    @Test
    fun `emit assignment to stack slot`() = withEmitter { emitter, function, context ->
        val valueType = IrPrimitive(PrimitiveKind.I32)
        val address = context.builder.emit(
            IrAlloca(
                id = -1,
                type = IrPointer(valueType),
                allocatedType = valueType,
                slotName = "x",
            ),
        )
        context.valueEnv.bind("x", StackSlot(address, valueType, mutable = true))

        val assign = BinaryExprNode(
            Punctuation.EQUAL,
            PathExprNode(TypePathNode("x", null), null),
            LiteralExprNode("5", Literal.INTEGER),
        )
        emitter.emitExpr(assign)

        val block = function.entryBlock()
        assertEquals(2, block.instructions.size)
        assertIs<IrStore>(block.instructions.last())
    }

    @Test
    fun `emit return with value`() = withEmitter(IrPrimitive(PrimitiveKind.I32)) { emitter, function, _ ->
        val retExpr = ReturnExprNode(LiteralExprNode("5", Literal.INTEGER))
        emitter.emitExpr(retExpr)

        val terminator = assertNotNull(function.entryBlock().terminator)
        val ret = assertIs<IrReturn>(terminator)
        assertIs<IrIntConstant>(ret.value)
    }

    @Test
    fun `short-circuit and skips rhs block on false lhs`() = withEmitter(IrPrimitive(PrimitiveKind.BOOL)) { emitter, function, context ->
        val expr = BinaryExprNode(
            Punctuation.AND_AND,
            LiteralExprNode("false", Keyword.FALSE),
            LiteralExprNode("true", Keyword.TRUE),
        )
        // seed a mutable slot for x so the RHS is representable
        val boolType = IrPrimitive(PrimitiveKind.BOOL)
        val intType = IrPrimitive(PrimitiveKind.I32)
        val address = context.builder.emit(
            IrAlloca(
                id = -1,
                type = IrPointer(intType),
                allocatedType = intType,
                slotName = "x",
            ),
        )
        context.valueEnv.bind("x", StackSlot(address, intType, mutable = true))

        val value = emitter.emitExpr(expr)
        assertEquals(boolType, value.type)

        val blocks = function.blocks
        assertTrue(blocks.size >= 2)
        val entryTerm = assertIs<IrBranch>(blocks.first().terminator)
        val mergeLabel = entryTerm.falseTarget
        assertTrue(blocks.any { it.label == mergeLabel })
    }

    @Test
    fun `borrow produces bitcast when mutability changes`() = withEmitter { emitter, _, context ->
        val i32 = IrPrimitive(PrimitiveKind.I32)
        val slot = context.builder.emit(
            IrAlloca(
                id = -1,
                type = IrPointer(i32),
                allocatedType = i32,
                slotName = "y",
            ),
        )
        context.valueEnv.bind("y", StackSlot(slot, i32, mutable = false))
        val borrow = BorrowExprNode(PathExprNode(TypePathNode("y", null), null), isMut = true)
        val value = emitter.emitExpr(borrow)
        assertEquals(IrPointer(i32, mutable = true), value.type)
    }

}
