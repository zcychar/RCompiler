package backend.ir

import frontend.Keyword
import frontend.Literal
import frontend.Punctuation
import frontend.ast.ArrayExprNode
import frontend.ast.BinaryExprNode
import frontend.ast.BlockExprNode
import frontend.ast.BorrowExprNode
import frontend.ast.BreakExprNode
import frontend.ast.CallExprNode
import frontend.ast.CondExprNode
import frontend.ast.ContinueExprNode
import frontend.ast.ExprStmtNode
import frontend.ast.LiteralExprNode
import frontend.ast.LoopExprNode
import frontend.ast.PathExprNode
import frontend.ast.ReturnExprNode
import frontend.ast.StructExprNode
import frontend.ast.TypePathNode
import frontend.ast.WhileExprNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import frontend.semantic.Function
import frontend.semantic.Int32Type
import frontend.semantic.Namespace
import frontend.semantic.Scope
import frontend.semantic.ScopeKind
import frontend.semantic.Struct
import frontend.semantic.StructType
import frontend.semantic.Variable
import frontend.semantic.toPrelude

class ExprEmitterTest {

    private fun withEmitter(
        returnType: IrType = IrPrimitive(PrimitiveKind.UNIT),
        context: CodegenContext = CodegenContext(),
        block: (ExprEmitter, IrFunction, CodegenContext) -> Unit,
    ) {
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

    @Test
    fun `emit while with break jumps to exit`() = withEmitter { emitter, function, _ ->
        val whileExpr = WhileExprNode(
            conds = listOf(CondExprNode(null, LiteralExprNode("true", Keyword.TRUE))),
            expr = BlockExprNode(false, listOf(ExprStmtNode(BreakExprNode(null)))),
        )
        emitter.emitExpr(whileExpr)

        val entryJump = assertIs<IrJump>(function.entryBlock().terminator)
        val condLabel = entryJump.target
        val condBranch = assertIs<IrBranch>(function.blocks.first { it.label == condLabel }.terminator)
        val bodyLabel = condBranch.trueTarget
        val exitLabel = condBranch.falseTarget
        val bodyTerminator = assertIs<IrJump>(function.blocks.first { it.label == bodyLabel }.terminator)
        assertEquals(exitLabel, bodyTerminator.target)
    }

    @Test
    fun `emit loop continue jumps back to body`() = withEmitter { emitter, function, _ ->
        val loopExpr = LoopExprNode(BlockExprNode(false, listOf(ExprStmtNode(ContinueExprNode))))
        emitter.emitExpr(loopExpr)

        val entryJump = assertIs<IrJump>(function.entryBlock().terminator)
        val bodyLabel = entryJump.target
        val bodyTerminator = assertIs<IrJump>(function.blocks.first { it.label == bodyLabel }.terminator)
        assertEquals(bodyLabel, bodyTerminator.target)
    }

    @Test
    fun `emit array literal allocates and stores elements`() = withEmitter { emitter, function, _ ->
        val arrayExpr = ArrayExprNode(
            elements = listOf(
                LiteralExprNode("1", Literal.INTEGER),
                LiteralExprNode("2", Literal.INTEGER),
            ),
            repeatOp = null,
            lengthOp = null,
            evaluatedSize = 2,
        )
        val value = emitter.emitExpr(arrayExpr)
        val arrayType = IrArray(IrPrimitive(PrimitiveKind.I32), 2)
        assertEquals(arrayType, value.type)

        val instructions = function.entryBlock().instructions
        val alloca = assertIs<IrAlloca>(instructions.first())
        assertEquals(IrPointer(arrayType), alloca.type)
        val load = assertIs<IrLoad>(instructions.last())
        assertEquals(arrayType, load.type)
        assertEquals(IrPointer(arrayType), load.address.type)
        val stores = instructions.filterIsInstance<IrStore>()
        assertEquals(2, stores.size)
    }

    @Test
    fun `emit struct literal stores fields in declared order`() {
        val structType = StructType("Point", linkedMapOf("x" to Int32Type, "y" to Int32Type))
        val structSymbol = Struct("Point", node = null, type = structType)
        val rootScope = Scope(toPrelude(), ScopeKind.GLOBAL).also {
            it.declare(structSymbol, Namespace.TYPE)
        }
        val context = CodegenContext(rootScope = rootScope)

        withEmitter(context = context) { emitter, function, _ ->
            val structExpr = StructExprNode(
                path = PathExprNode(TypePathNode("Point", null), null),
                fields = listOf(
                    StructExprNode.StructExprField("x", LiteralExprNode("1", Literal.INTEGER)),
                    StructExprNode.StructExprField("y", LiteralExprNode("2", Literal.INTEGER)),
                ),
            )

            val value = emitter.emitExpr(structExpr)
            val expectedType = IrStruct("Point", listOf(IrPrimitive(PrimitiveKind.I32), IrPrimitive(PrimitiveKind.I32)))
            assertEquals(expectedType, value.type)

            val stores = function.entryBlock().instructions.filterIsInstance<IrStore>()
            assertEquals(2, stores.size)
        }
    }

    @Test
    fun `emit call builds IrCall with resolved signature`() {
        val rootScope = Scope(toPrelude(), ScopeKind.GLOBAL)
        val fnSymbol = Function(
            name = "foo",
            node = null,
            params = listOf(Variable("x", Int32Type, false)),
            returnType = Int32Type,
        )
        rootScope.declare(fnSymbol, Namespace.VALUE)
        val context = CodegenContext(rootScope = rootScope)

        withEmitter(returnType = IrPrimitive(PrimitiveKind.I32), context = context) { emitter, function, _ ->
            val callExpr = CallExprNode(
                PathExprNode(TypePathNode("foo", null), null),
                params = listOf(LiteralExprNode("1", Literal.INTEGER)),
            )

            val value = emitter.emitExpr(callExpr)
            assertIs<IrRegister>(value)

            val call = assertIs<IrCall>(function.entryBlock().instructions.last())
            assertEquals("foo", call.callee.name)
            assertEquals(1, call.arguments.size)
        }
    }

}
