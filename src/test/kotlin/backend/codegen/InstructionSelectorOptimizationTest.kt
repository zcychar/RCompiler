package backend.codegen

import backend.ir.BinaryOperator
import backend.ir.ComparePredicate
import backend.ir.IrAlloca
import backend.ir.IrArray
import backend.ir.IrBinary
import backend.ir.IrBranch
import backend.ir.IrBuilder
import backend.ir.IrCmp
import backend.ir.IrConstant
import backend.ir.IrFunction
import backend.ir.IrFunctionSignature
import backend.ir.IrGep
import backend.ir.IrLoad
import backend.ir.IrModule
import backend.ir.IrParameter
import backend.ir.IrPointer
import backend.ir.IrPrimitive
import backend.ir.IrReturn
import backend.ir.IrStruct
import backend.ir.IrType
import backend.ir.PrimitiveKind
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstructionSelectorOptimizationTest {
    private val bool = IrPrimitive(PrimitiveKind.BOOL)
    private val i32 = IrPrimitive(PrimitiveKind.I32)
    private val u32 = IrPrimitive(PrimitiveKind.U32)
    private val unit = IrPrimitive(PrimitiveKind.UNIT)

    @Test
    fun `dynamic gep with power of two element size uses shift`() {
        val (module, function, builder) = newFunction("gepShift.", i32, listOf(i32), listOf("idx"))
        val entry = function.createBlock("entry")
        builder.positionAt(function, entry)

        val array = IrArray(i32, 16)
        val alloca = builder.emit(IrAlloca("arr", IrPointer(array), array), "arr")
        val elem = builder.emit(
            IrGep(
                "",
                IrPointer(i32),
                alloca,
                listOf(IrConstant(0, i32), IrParameter(0, "idx", i32))
            )
        )
        val loaded = builder.emit(IrLoad("", i32, elem))
        builder.emitTerminator(IrReturn("", i32, loaded))

        val asm = RiscVCodegen.compile(module)
        assertTrue(asm.contains("slli"), "dynamic i32 indexing should use a shift:\n$asm")
        assertNoOpcode(asm, "mul")
    }

    @Test
    fun `dynamic gep with cheap composite element size uses shift add`() {
        val (module, function, builder) = newFunction("gepShiftAdd.", i32, listOf(i32), listOf("idx"))
        val entry = function.createBlock("entry")
        builder.positionAt(function, entry)

        val element = IrStruct("SixWords", listOf(i32, i32, i32, i32, i32, i32))
        val array = IrArray(element, 16)
        val alloca = builder.emit(IrAlloca("arr", IrPointer(array), array), "arr")
        val elem = builder.emit(
            IrGep(
                "",
                IrPointer(element),
                alloca,
                listOf(IrConstant(0, i32), IrParameter(0, "idx", i32))
            )
        )
        val field = builder.emit(
            IrGep("", IrPointer(i32), elem, listOf(IrConstant(0, i32), IrConstant(0, i32)))
        )
        val loaded = builder.emit(IrLoad("", i32, field))
        builder.emitTerminator(IrReturn("", i32, loaded))

        val asm = RiscVCodegen.compile(module)
        assertTrue(asm.contains("slli"), "dynamic 24-byte indexing should use shifts:\n$asm")
        assertNoOpcode(asm, "mul")
    }

    @Test
    fun `branch only compare lowers to direct branch`() {
        val (module, function, builder) = newFunction(
            "branchCmp.",
            i32,
            listOf(i32, i32),
            listOf("a", "b")
        )
        val entry = function.createBlock("entry")
        val elseBlock = function.createBlock("else")
        val thenBlock = function.createBlock("then")

        builder.positionAt(function, entry)
        val cmp = builder.emit(
            IrCmp(
                "",
                bool,
                ComparePredicate.SLT,
                IrParameter(0, "a", i32),
                IrParameter(1, "b", i32)
            )
        )
        builder.emitTerminator(IrBranch("", unit, cmp, "then", "else"))

        builder.positionAt(function, elseBlock)
        builder.emitTerminator(IrReturn("", i32, IrConstant(0, i32)))

        builder.positionAt(function, thenBlock)
        builder.emitTerminator(IrReturn("", i32, IrConstant(1, i32)))

        val asm = RiscVCodegen.compile(module)
        assertTrue(asm.contains("blt"), "branch should use direct compare:\n$asm")
        assertNoOpcode(asm, "slt")
    }

    @Test
    fun `materialized unsigned word compare zero extends operands on RV64`() {
        val (module, function, builder) = newFunction(
            "unsignedCmp.",
            bool,
            listOf(u32, u32),
            listOf("a", "b")
        )
        val entry = function.createBlock("entry")
        builder.positionAt(function, entry)

        val cmp = builder.emit(
            IrCmp(
                "",
                bool,
                ComparePredicate.ULT,
                IrParameter(0, "a", u32),
                IrParameter(1, "b", u32)
            )
        )
        builder.emitTerminator(IrReturn("", bool, cmp))

        val asm = RiscVCodegen.compile(module)
        assertTrue(asm.contains("slli"), "unsigned u32 compare should zero-extend lhs/rhs first:\n$asm")
        assertTrue(asm.contains("srli"), "unsigned u32 compare should zero-extend lhs/rhs first:\n$asm")
        assertTrue(asm.contains("sltu"), "materialized unsigned compare should use sltu:\n$asm")
    }

    @Test
    fun `branch only unsigned word compare zero extends before direct branch`() {
        val (module, function, builder) = newFunction(
            "unsignedBranchCmp.",
            i32,
            listOf(u32, u32),
            listOf("a", "b")
        )
        val entry = function.createBlock("entry")
        val elseBlock = function.createBlock("else")
        val thenBlock = function.createBlock("then")

        builder.positionAt(function, entry)
        val cmp = builder.emit(
            IrCmp(
                "",
                bool,
                ComparePredicate.ULT,
                IrParameter(0, "a", u32),
                IrParameter(1, "b", u32)
            )
        )
        builder.emitTerminator(IrBranch("", unit, cmp, "then", "else"))

        builder.positionAt(function, elseBlock)
        builder.emitTerminator(IrReturn("", i32, IrConstant(0, i32)))

        builder.positionAt(function, thenBlock)
        builder.emitTerminator(IrReturn("", i32, IrConstant(1, i32)))

        val asm = RiscVCodegen.compile(module)
        assertTrue(asm.contains("slli"), "direct unsigned branch should zero-extend lhs/rhs first:\n$asm")
        assertTrue(asm.contains("srli"), "direct unsigned branch should zero-extend lhs/rhs first:\n$asm")
        assertTrue(asm.contains("bltu"), "branch should still use direct unsigned branch:\n$asm")
        assertNoOpcode(asm, "sltu")
    }

    @Test
    fun `unsigned div and rem by power of two lower to cheap immediates`() {
        val (module, function, builder) = newFunction("udivRemPow2.", u32, listOf(u32), listOf("x"))
        val entry = function.createBlock("entry")
        builder.positionAt(function, entry)

        val x = IrParameter(0, "x", u32)
        val q = builder.emit(IrBinary("", u32, BinaryOperator.UDIV, x, IrConstant(8, u32)))
        val r = builder.emit(IrBinary("", u32, BinaryOperator.UREM, x, IrConstant(8, u32)))
        val sum = builder.emit(IrBinary("", u32, BinaryOperator.ADD, q, r))
        builder.emitTerminator(IrReturn("", u32, sum))

        val asm = RiscVCodegen.compile(module)
        assertTrue(asm.contains("srli"), "division by 8 should use srli:\n$asm")
        assertTrue(asm.contains("andi"), "remainder by 8 should use andi:\n$asm")
        assertNoOpcode(asm, "divu")
        assertNoOpcode(asm, "remu")
    }

    private fun assertNoOpcode(asm: String, opcode: String) {
        assertFalse(
            Regex("""\b${Regex.escape(opcode)}\b""").containsMatchIn(asm),
            "Did not expect opcode '$opcode' in assembly:\n$asm"
        )
    }

    private fun newFunction(
        name: String,
        returnType: IrType,
        parameters: List<IrType> = emptyList(),
        parameterNames: List<String> = emptyList()
    ): Triple<IrModule, IrFunction, IrBuilder> {
        val module = IrModule()
        val function = IrFunction(
            name = name,
            signature = IrFunctionSignature(parameters = parameters, returnType = returnType),
            parameterNames = parameterNames
        )
        module.declareFunction(function)
        return Triple(module, function, IrBuilder(module))
    }
}
