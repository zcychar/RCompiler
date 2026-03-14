package backend.codegen

import backend.codegen.riscv.*
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertContains

/**
 * End-to-end tests for the RISC-V codegen pipeline and the [AsmEmitter].
 *
 * These tests manually construct [RvMachineFunction]s with virtual registers,
 * run the full pipeline (regalloc → frame layout → asm emission), and verify
 * the generated assembly text.
 *
 * Some tests also exercise [AsmEmitter] directly on pre-built machine IR
 * (post frame layout) to verify formatting.
 */
class RiscVCodegenTest {

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    private fun v(id: Int): RvOperand.Reg = RvOperand.Reg(id)
    private fun p(reg: RvPhysReg): RvOperand.PhysReg = RvOperand.PhysReg(reg)

    /**
     * Build a machine function with virtual registers, run regalloc + frame layout,
     * then emit assembly text.
     */
    private inline fun compiled(
        name: String = "test",
        build: RvMachineFunction.() -> Unit,
    ): String {
        val mf = RvMachineFunction(name)
        mf.build()
        mf.rebuildCfgEdges()

        val alloc = GraphColorRegAlloc()
        alloc.allocate(mf)

        FrameLayout.run(mf)

        return AsmEmitter.emitFunction(mf)
    }

    /**
     * Build a machine function with virtual registers, run regalloc + frame layout,
     * return the mf.
     */
    private inline fun fullPipeline(
        name: String = "test",
        build: RvMachineFunction.() -> Unit,
    ): RvMachineFunction {
        val mf = RvMachineFunction(name)
        mf.build()
        mf.rebuildCfgEdges()

        val alloc = GraphColorRegAlloc()
        alloc.allocate(mf)

        FrameLayout.run(mf)

        return mf
    }

    /** Assert no virtual registers remain in the rendered assembly. */
    private fun assertNoVregs(asm: String) {
        assertFalse(
            Regex("""v\d+""").containsMatchIn(asm),
            "Assembly should not contain virtual registers. Got:\n$asm"
        )
    }

    /** Assert the assembly contains the given substring. */
    private fun assertAsmContains(asm: String, expected: String, msg: String = "") {
        assertTrue(
            asm.contains(expected),
            "${if (msg.isNotEmpty()) "$msg: " else ""}Expected '$expected' in assembly:\n$asm"
        )
    }

    /** Assert the assembly does NOT contain the given substring. */
    private fun assertAsmNotContains(asm: String, unexpected: String, msg: String = "") {
        assertFalse(
            asm.contains(unexpected),
            "${if (msg.isNotEmpty()) "$msg: " else ""}Did not expect '$unexpected' in assembly:\n$asm"
        )
    }

    // =======================================================================
    //  AsmEmitter unit tests
    // =======================================================================

    // -----------------------------------------------------------------------
    //  1. Simple function with only physical registers (no regalloc needed)
    // -----------------------------------------------------------------------

    @Test
    fun `AsmEmitter emits function label and instructions`() {
        val mf = RvMachineFunction("simple")
        val bb = mf.createBlock("simple")
        bb.append(RvInst.Li(p(RvPhysReg.A0), 42))
        bb.append(RvInst.Ret(listOf(RvPhysReg.A0)))

        val asm = AsmEmitter.emitFunction(mf)

        assertAsmContains(asm, "simple:")
        assertAsmContains(asm, "li  a0, 42")
        assertAsmContains(asm, "ret")
    }

    // -----------------------------------------------------------------------
    //  2. Entry block label is not duplicated
    // -----------------------------------------------------------------------

    @Test
    fun `AsmEmitter does not duplicate entry block label`() {
        val mf = RvMachineFunction("foo")
        val bb = mf.createBlock("foo")
        bb.append(RvInst.Ret())

        val asm = AsmEmitter.emitFunction(mf)

        // "foo:" should appear exactly once.
        val count = Regex("""^foo:""", RegexOption.MULTILINE).findAll(asm).count()
        assertEquals(1, count, "Entry block label 'foo:' should appear exactly once:\n$asm")
    }

    // -----------------------------------------------------------------------
    //  3. Non-entry blocks get their labels
    // -----------------------------------------------------------------------

    @Test
    fun `AsmEmitter emits labels for non-entry blocks`() {
        val mf = RvMachineFunction("multi")
        val entry = mf.createBlock("multi")
        val other = mf.createBlock("multi.other")

        entry.append(RvInst.J("multi.other"))
        other.append(RvInst.Li(p(RvPhysReg.A0), 0))
        other.append(RvInst.Ret(listOf(RvPhysReg.A0)))

        val asm = AsmEmitter.emitFunction(mf)

        assertAsmContains(asm, "multi:")
        assertAsmContains(asm, "multi.other:")
    }

    // -----------------------------------------------------------------------
    //  4. Comments are preserved
    // -----------------------------------------------------------------------

    @Test
    fun `AsmEmitter preserves comments`() {
        val mf = RvMachineFunction("commented")
        val bb = mf.createBlock("commented")
        bb.append(RvInst.Comment("this is a comment"))
        bb.append(RvInst.Ret())

        val asm = AsmEmitter.emitFunction(mf)
        assertAsmContains(asm, "# this is a comment")
    }

    // -----------------------------------------------------------------------
    //  5. Full module emission with .text and .globl main
    // -----------------------------------------------------------------------

    @Test
    fun `AsmEmitter emit produces text section and globl for main`() {
        val mf1 = RvMachineFunction("helper")
        mf1.createBlock("helper").apply {
            append(RvInst.Li(p(RvPhysReg.A0), 1))
            append(RvInst.Ret(listOf(RvPhysReg.A0)))
        }

        val mf2 = RvMachineFunction("main")
        mf2.createBlock("main").apply {
            append(RvInst.Call("helper", resultRegs = listOf(RvPhysReg.A0)))
            append(RvInst.Ret(listOf(RvPhysReg.A0)))
        }

        // We need a minimal IrModule. Since we only need it for globals,
        // and there are none, we can use a real one.
        val irModule = backend.ir.IrModule()
        val asm = AsmEmitter.emit(listOf(mf1, mf2), irModule)

        assertAsmContains(asm, ".text")
        assertAsmContains(asm, ".globl main")
        assertAsmContains(asm, "helper:")
        assertAsmContains(asm, "main:")
    }

    // -----------------------------------------------------------------------
    //  6. .globl not emitted for non-main functions
    // -----------------------------------------------------------------------

    @Test
    fun `AsmEmitter does not emit globl for non-main functions`() {
        val mf = RvMachineFunction("helper")
        mf.createBlock("helper").apply {
            append(RvInst.Ret())
        }

        val irModule = backend.ir.IrModule()
        val asm = AsmEmitter.emit(listOf(mf), irModule)

        assertAsmNotContains(asm, ".globl")
    }

    // =======================================================================
    //  Full pipeline tests (vregs → regalloc → frame → asm)
    // =======================================================================

    // -----------------------------------------------------------------------
    //  7. Trivial function: li + ret
    // -----------------------------------------------------------------------

    @Test
    fun `trivial function compiles to valid assembly`() {
        val asm = compiled("trivial") {
            val bb = createBlock("trivial")
            bb.append(RvInst.Li(v(0), 42))
            bb.append(RvInst.Mv(p(RvPhysReg.A0), v(0)))
            bb.append(RvInst.Ret(listOf(RvPhysReg.A0)))
        }

        assertNoVregs(asm)
        assertAsmContains(asm, "trivial:")
        assertAsmContains(asm, "ret")
    }

    // -----------------------------------------------------------------------
    //  8. Function with add
    // -----------------------------------------------------------------------

    @Test
    fun `add function compiles correctly`() {
        val asm = compiled("add_func") {
            val bb = createBlock("add_func")
            bb.append(RvInst.Mv(v(0), p(RvPhysReg.A0)))
            bb.append(RvInst.Mv(v(1), p(RvPhysReg.A1)))
            bb.append(RvInst.RType(RvArithOp.ADD, v(2), v(0), v(1)))
            bb.append(RvInst.Mv(p(RvPhysReg.A0), v(2)))
            bb.append(RvInst.Ret(listOf(RvPhysReg.A0)))
        }

        assertNoVregs(asm)
        assertAsmContains(asm, "add_func:")
        assertAsmContains(asm, "ret")
    }

    // -----------------------------------------------------------------------
    //  9. Function with a call — prologue/epilogue should appear
    // -----------------------------------------------------------------------

    @Test
    fun `function with call has prologue and epilogue`() {
        val asm = compiled("caller") {
            hasCalls = true
            val bb = createBlock("caller")
            bb.append(RvInst.Li(v(0), 5))
            bb.append(RvInst.Mv(p(RvPhysReg.A0), v(0)))
            bb.append(RvInst.Call("callee", argRegs = listOf(RvPhysReg.A0), resultRegs = listOf(RvPhysReg.A0)))
            bb.append(RvInst.Mv(v(1), p(RvPhysReg.A0)))
            bb.append(RvInst.Mv(p(RvPhysReg.A0), v(1)))
            bb.append(RvInst.Ret(listOf(RvPhysReg.A0)))
        }

        assertNoVregs(asm)
        // Should have sp adjustments (prologue).
        assertAsmContains(asm, "addi  sp, sp, -", "Prologue sp subtract")
        assertAsmContains(asm, "addi  sp, sp, ", "Epilogue sp add")
        // Should save ra.
        assertAsmContains(asm, "sw  ra,", "Prologue ra save")
        assertAsmContains(asm, "lw  ra,", "Epilogue ra restore")
    }

    // -----------------------------------------------------------------------
    //  10. Function with value live across call uses callee-saved reg
    // -----------------------------------------------------------------------

    @Test
    fun `value live across call uses callee-saved register`() {
        val mf = fullPipeline("live_across") {
            hasCalls = true
            val bb = createBlock("live_across")
            bb.append(RvInst.Li(v(0), 100))
            bb.append(RvInst.Call("work"))
            bb.append(RvInst.Mv(p(RvPhysReg.A0), v(0)))
            bb.append(RvInst.Ret(listOf(RvPhysReg.A0)))
        }

        assertTrue(
            mf.usedCalleeSaved.isNotEmpty(),
            "Should use callee-saved regs for value live across call"
        )

        val asm = AsmEmitter.emitFunction(mf)
        assertNoVregs(asm)
        // Callee-saved should be saved/restored.
        for (reg in mf.usedCalleeSaved) {
            assertAsmContains(asm, "sw  ${reg.abiName},", "Save ${reg.abiName}")
            assertAsmContains(asm, "lw  ${reg.abiName},", "Restore ${reg.abiName}")
        }
    }

    // -----------------------------------------------------------------------
    //  11. Diamond CFG compiles and has correct labels
    // -----------------------------------------------------------------------

    @Test
    fun `diamond CFG compiles with all block labels`() {
        val asm = compiled("diamond") {
            val entry = createBlock("diamond")
            val left = createBlock("diamond.left")
            val right = createBlock("diamond.right")
            val merge = createBlock("diamond.merge")

            entry.append(RvInst.Li(v(0), 1))
            entry.append(RvInst.Branch(RvBranchCond.BNE, v(0), p(RvPhysReg.ZERO), "diamond.left"))
            entry.append(RvInst.J("diamond.right"))

            left.append(RvInst.Li(v(1), 10))
            left.append(RvInst.Mv(v(3), v(1)))
            left.append(RvInst.J("diamond.merge"))

            right.append(RvInst.Li(v(2), 20))
            right.append(RvInst.Mv(v(3), v(2)))
            right.append(RvInst.J("diamond.merge"))

            merge.append(RvInst.Mv(p(RvPhysReg.A0), v(3)))
            merge.append(RvInst.Ret(listOf(RvPhysReg.A0)))
        }

        assertNoVregs(asm)
        assertAsmContains(asm, "diamond:")
        assertAsmContains(asm, "diamond.left:")
        assertAsmContains(asm, "diamond.right:")
        assertAsmContains(asm, "diamond.merge:")
        assertAsmContains(asm, "ret")
    }

    // -----------------------------------------------------------------------
    //  12. Loop CFG compiles
    // -----------------------------------------------------------------------

    @Test
    fun `loop CFG compiles correctly`() {
        val asm = compiled("loop") {
            val entry = createBlock("loop")
            val body = createBlock("loop.body")
            val exit = createBlock("loop.exit")

            entry.append(RvInst.Li(v(0), 0))
            entry.append(RvInst.Li(v(1), 10))
            entry.append(RvInst.J("loop.body"))

            body.append(RvInst.RType(RvArithOp.ADD, v(2), v(0), v(1)))
            body.append(RvInst.Branch(RvBranchCond.BNE, v(2), v(1), "loop.body"))
            body.append(RvInst.J("loop.exit"))

            exit.append(RvInst.Mv(p(RvPhysReg.A0), v(2)))
            exit.append(RvInst.Ret(listOf(RvPhysReg.A0)))
        }

        assertNoVregs(asm)
        assertAsmContains(asm, "loop.body:")
        assertAsmContains(asm, "loop.exit:")
    }

    // -----------------------------------------------------------------------
    //  13. Function with alloca (stack slot) compiles
    // -----------------------------------------------------------------------

    @Test
    fun `function with alloca has frame and patched offsets`() {
        val asm = compiled("with_alloca") {
            val bb = createBlock("with_alloca")
            val slotIdx = allocateStackSlot("local.x", 4, 4)

            // Simulate isel's alloca pattern.
            bb.append(RvInst.Comment("slot $slotIdx"))
            bb.append(RvInst.IType(RvArithImmOp.ADDI, v(0), p(RvPhysReg.SP), 0))
            bb.append(RvInst.Li(v(1), 42))
            bb.append(RvInst.Store(MemWidth.WORD, v(1), v(0), 0))
            bb.append(RvInst.Load(MemWidth.WORD, v(2), v(0), 0))
            bb.append(RvInst.Mv(p(RvPhysReg.A0), v(2)))
            bb.append(RvInst.Ret(listOf(RvPhysReg.A0)))
        }

        assertNoVregs(asm)
        // Should have a frame (sp adjustment).
        assertAsmContains(asm, "addi  sp, sp, -")
        // Slot comment should be removed.
        assertAsmNotContains(asm, "# slot 0")
    }

    // -----------------------------------------------------------------------
    //  14. Spilling function (high register pressure) compiles
    // -----------------------------------------------------------------------

    @Test
    fun `high pressure function with spills compiles`() {
        val asm = compiled("spilly") {
            val bb = createBlock("spilly")

            // Create 30 simultaneously live vregs (exceeds K=27).
            for (i in 0 until 30) {
                bb.append(RvInst.Li(v(i), i))
            }

            var acc = v(0)
            for (i in 1 until 30) {
                val dst = v(30 + i - 1)
                bb.append(RvInst.RType(RvArithOp.ADD, dst, acc, v(i)))
                acc = dst
            }

            bb.append(RvInst.Mv(p(RvPhysReg.A0), acc))
            bb.append(RvInst.Ret(listOf(RvPhysReg.A0)))
        }

        assertNoVregs(asm)
        assertAsmContains(asm, "ret")
        // Should have a frame due to spills.
        assertAsmContains(asm, "addi  sp, sp, -")
    }

    // -----------------------------------------------------------------------
    //  15. Call with arguments
    // -----------------------------------------------------------------------

    @Test
    fun `call with arguments compiles`() {
        val asm = compiled("call_args") {
            hasCalls = true
            val bb = createBlock("call_args")
            bb.append(RvInst.Li(v(0), 1))
            bb.append(RvInst.Li(v(1), 2))
            bb.append(RvInst.Mv(p(RvPhysReg.A0), v(0)))
            bb.append(RvInst.Mv(p(RvPhysReg.A1), v(1)))
            bb.append(
                RvInst.Call(
                    "add_func",
                    argRegs = listOf(RvPhysReg.A0, RvPhysReg.A1),
                    resultRegs = listOf(RvPhysReg.A0)
                )
            )
            bb.append(RvInst.Mv(v(2), p(RvPhysReg.A0)))
            bb.append(RvInst.Mv(p(RvPhysReg.A0), v(2)))
            bb.append(RvInst.Ret(listOf(RvPhysReg.A0)))
        }

        assertNoVregs(asm)
        assertAsmContains(asm, "call  add_func")
    }

    // -----------------------------------------------------------------------
    //  16. Unary instructions compile
    // -----------------------------------------------------------------------

    @Test
    fun `unary pseudo-instructions compile`() {
        val asm = compiled("unary") {
            val bb = createBlock("unary")
            bb.append(RvInst.Li(v(0), 5))
            bb.append(RvInst.Neg(v(1), v(0)))
            bb.append(RvInst.Not(v(2), v(1)))
            bb.append(RvInst.Seqz(v(3), v(2)))
            bb.append(RvInst.Snez(v(4), v(3)))
            bb.append(RvInst.Mv(p(RvPhysReg.A0), v(4)))
            bb.append(RvInst.Ret(listOf(RvPhysReg.A0)))
        }

        assertNoVregs(asm)
        assertAsmContains(asm, "neg")
        assertAsmContains(asm, "not")
        assertAsmContains(asm, "seqz")
        assertAsmContains(asm, "snez")
    }

    // -----------------------------------------------------------------------
    //  17. lui and la instructions compile
    // -----------------------------------------------------------------------

    @Test
    fun `lui and la instructions compile`() {
        val asm = compiled("lui_la") {
            val bb = createBlock("lui_la")
            bb.append(RvInst.Lui(v(0), RvOperand.Reloc(RelocKind.HI, "sym")))
            bb.append(RvInst.La(v(1), "sym"))
            bb.append(RvInst.Mv(p(RvPhysReg.A0), v(0)))
            bb.append(RvInst.Ret(listOf(RvPhysReg.A0)))
        }

        assertNoVregs(asm)
        assertAsmContains(asm, "lui")
        assertAsmContains(asm, "la")
    }

    // -----------------------------------------------------------------------
    //  18. Store and Load instructions compile
    // -----------------------------------------------------------------------

    @Test
    fun `store and load instructions compile`() {
        val asm = compiled("mem_ops") {
            val bb = createBlock("mem_ops")
            bb.append(RvInst.Li(v(0), 42))
            bb.append(RvInst.IType(RvArithImmOp.ADDI, v(1), p(RvPhysReg.SP), 0))
            bb.append(RvInst.Store(MemWidth.WORD, v(0), v(1), 0))
            bb.append(RvInst.Load(MemWidth.WORD, v(2), v(1), 0))
            bb.append(RvInst.Mv(p(RvPhysReg.A0), v(2)))
            bb.append(RvInst.Ret(listOf(RvPhysReg.A0)))
        }

        assertNoVregs(asm)
        assertAsmContains(asm, "sw")
        assertAsmContains(asm, "lw")
    }

    // -----------------------------------------------------------------------
    //  19. Empty function (just ret)
    // -----------------------------------------------------------------------

    @Test
    fun `empty function compiles`() {
        val asm = compiled("empty") {
            val bb = createBlock("empty")
            bb.append(RvInst.Ret())
        }

        assertNoVregs(asm)
        assertAsmContains(asm, "empty:")
        assertAsmContains(asm, "ret")
        // No sp adjustments for truly empty function.
        assertAsmNotContains(asm, "addi  sp, sp,")
    }

    // -----------------------------------------------------------------------
    //  20. Multiple return sites
    // -----------------------------------------------------------------------

    @Test
    fun `multiple return sites both have epilogues`() {
        val asm = compiled("multi_ret") {
            hasCalls = true
            val entry = createBlock("multi_ret")
            val taken = createBlock("multi_ret.taken")
            val fallthrough = createBlock("multi_ret.fallthrough")

            entry.append(RvInst.Li(v(0), 1))
            entry.append(RvInst.Mv(p(RvPhysReg.A0), v(0)))
            entry.append(RvInst.Call("check", argRegs = listOf(RvPhysReg.A0), resultRegs = listOf(RvPhysReg.A0)))
            entry.append(RvInst.Mv(v(1), p(RvPhysReg.A0)))
            entry.append(RvInst.Branch(RvBranchCond.BNE, v(1), p(RvPhysReg.ZERO), "multi_ret.taken"))
            entry.append(RvInst.J("multi_ret.fallthrough"))

            taken.append(RvInst.Li(p(RvPhysReg.A0), 1))
            taken.append(RvInst.Ret(listOf(RvPhysReg.A0)))

            fallthrough.append(RvInst.Li(p(RvPhysReg.A0), 0))
            fallthrough.append(RvInst.Ret(listOf(RvPhysReg.A0)))
        }

        assertNoVregs(asm)
        // Count "ret" instructions — should be exactly 2.
        val retCount = Regex("""^\s+ret\s*$""", RegexOption.MULTILINE).findAll(asm).count()
        assertEquals(2, retCount, "Should have exactly 2 ret instructions:\n$asm")
    }

    // -----------------------------------------------------------------------
    //  21. Move coalescing eliminates trivial moves
    // -----------------------------------------------------------------------

    @Test
    fun `move coalescing eliminates trivial self-moves`() {
        val asm = compiled("coalesce") {
            val bb = createBlock("coalesce")
            bb.append(RvInst.Li(v(0), 7))
            bb.append(RvInst.Mv(v(1), v(0)))
            bb.append(RvInst.Mv(v(2), v(1)))
            bb.append(RvInst.Mv(p(RvPhysReg.A0), v(2)))
            bb.append(RvInst.Ret(listOf(RvPhysReg.A0)))
        }

        assertNoVregs(asm)
        // After coalescing, there should be no mv rX, rX lines.
        val lines = asm.lines().map { it.trim() }
        for (line in lines) {
            if (line.startsWith("mv")) {
                val parts = line.removePrefix("mv").trim().split(",").map { it.trim() }
                if (parts.size == 2) {
                    assertTrue(
                        parts[0] != parts[1],
                        "Trivial self-move should be eliminated: $line"
                    )
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    //  22. Phi-lowered pattern compiles
    // -----------------------------------------------------------------------

    @Test
    fun `phi-lowered pattern compiles through full pipeline`() {
        val asm = compiled("phi_test") {
            val entry = createBlock("phi_test")
            val left = createBlock("phi_test.left")
            val right = createBlock("phi_test.right")
            val merge = createBlock("phi_test.merge")

            entry.append(RvInst.Li(v(0), 1))
            entry.append(RvInst.Branch(RvBranchCond.BNE, v(0), p(RvPhysReg.ZERO), "phi_test.left"))
            entry.append(RvInst.J("phi_test.right"))

            left.append(RvInst.Li(v(1), 10))
            left.append(RvInst.Mv(v(3), v(1)))
            left.append(RvInst.J("phi_test.merge"))

            right.append(RvInst.Li(v(2), 20))
            right.append(RvInst.Mv(v(3), v(2)))
            right.append(RvInst.J("phi_test.merge"))

            merge.append(RvInst.Mv(p(RvPhysReg.A0), v(3)))
            merge.append(RvInst.Ret(listOf(RvPhysReg.A0)))
        }

        assertNoVregs(asm)
        assertAsmContains(asm, "phi_test.merge:")
        assertAsmContains(asm, "ret")
    }

    // -----------------------------------------------------------------------
    //  23. Comparison and branch instructions compile
    // -----------------------------------------------------------------------

    @Test
    fun `comparison and branch instructions compile`() {
        val asm = compiled("cmp_branch") {
            val entry = createBlock("cmp_branch")
            val taken = createBlock("cmp_branch.taken")
            val fallthrough = createBlock("cmp_branch.fallthrough")

            entry.append(RvInst.Li(v(0), 5))
            entry.append(RvInst.Li(v(1), 10))
            entry.append(RvInst.RType(RvArithOp.SLT, v(2), v(0), v(1)))
            entry.append(RvInst.Branch(RvBranchCond.BNE, v(2), p(RvPhysReg.ZERO), "cmp_branch.taken"))
            entry.append(RvInst.J("cmp_branch.fallthrough"))

            taken.append(RvInst.Mv(p(RvPhysReg.A0), v(0)))
            taken.append(RvInst.Ret(listOf(RvPhysReg.A0)))

            fallthrough.append(RvInst.Mv(p(RvPhysReg.A0), v(1)))
            fallthrough.append(RvInst.Ret(listOf(RvPhysReg.A0)))
        }

        assertNoVregs(asm)
        assertAsmContains(asm, "slt")
        assertAsmContains(asm, "bne")
    }

    // -----------------------------------------------------------------------
    //  24. No callee-saved regs in simple function
    // -----------------------------------------------------------------------

    @Test
    fun `simple function does not save callee-saved regs`() {
        val mf = fullPipeline("simple_no_save") {
            val bb = createBlock("simple_no_save")
            bb.append(RvInst.Li(v(0), 1))
            bb.append(RvInst.Li(v(1), 2))
            bb.append(RvInst.RType(RvArithOp.ADD, v(2), v(0), v(1)))
            bb.append(RvInst.Mv(p(RvPhysReg.A0), v(2)))
            bb.append(RvInst.Ret(listOf(RvPhysReg.A0)))
        }

        assertTrue(
            mf.usedCalleeSaved.isEmpty(),
            "Simple function should not use callee-saved regs: ${mf.usedCalleeSaved}"
        )

        val asm = AsmEmitter.emitFunction(mf)
        for (reg in SAVED_REGS) {
            assertAsmNotContains(asm, "sw  ${reg.abiName},",
                "Should not save ${reg.abiName}")
        }
    }

    // -----------------------------------------------------------------------
    //  25. Frame size is correctly set after pipeline
    // -----------------------------------------------------------------------

    @Test
    fun `frame size is correct after full pipeline`() {
        val mf = fullPipeline("frame_check") {
            hasCalls = true
            usedCalleeSaved.add(RvPhysReg.S0)
            val bb = createBlock("frame_check")
            val slotIdx = allocateStackSlot("local", 4, 4)
            bb.append(RvInst.Comment("slot $slotIdx"))
            bb.append(RvInst.IType(RvArithImmOp.ADDI, v(0), p(RvPhysReg.SP), 0))
            bb.append(RvInst.Mv(p(RvPhysReg.A0), v(0)))
            bb.append(RvInst.Ret(listOf(RvPhysReg.A0)))
        }

        assertTrue(mf.frameSize > 0, "Frame size should be > 0")
        assertTrue(mf.frameSize % 16 == 0, "Frame size should be 16-byte aligned")
    }

    // -----------------------------------------------------------------------
    //  26. All instruction types render correctly in assembly
    // -----------------------------------------------------------------------

    @Test
    fun `all instruction types render in assembly`() {
        val mf = RvMachineFunction("all_insts")
        val bb = mf.createBlock("all_insts")

        bb.append(RvInst.Comment("testing all instruction types"))
        bb.append(RvInst.Li(p(RvPhysReg.T0), 42))
        bb.append(RvInst.La(p(RvPhysReg.T1), "some_symbol"))
        bb.append(RvInst.Lui(p(RvPhysReg.T2), RvOperand.Reloc(RelocKind.HI, "sym")))
        bb.append(RvInst.Mv(p(RvPhysReg.T3), p(RvPhysReg.T0)))
        bb.append(RvInst.Neg(p(RvPhysReg.T4), p(RvPhysReg.T3)))
        bb.append(RvInst.Not(p(RvPhysReg.T5), p(RvPhysReg.T4)))
        bb.append(RvInst.Seqz(p(RvPhysReg.T6), p(RvPhysReg.T5)))
        bb.append(RvInst.Snez(p(RvPhysReg.A0), p(RvPhysReg.T6)))
        bb.append(RvInst.RType(RvArithOp.ADD, p(RvPhysReg.A1), p(RvPhysReg.T0), p(RvPhysReg.T1)))
        bb.append(RvInst.RType(RvArithOp.MUL, p(RvPhysReg.A2), p(RvPhysReg.A0), p(RvPhysReg.A1)))
        bb.append(RvInst.IType(RvArithImmOp.ADDI, p(RvPhysReg.A3), p(RvPhysReg.A2), 100))
        bb.append(RvInst.Store(MemWidth.WORD, p(RvPhysReg.A3), p(RvPhysReg.SP), 0))
        bb.append(RvInst.Load(MemWidth.WORD, p(RvPhysReg.A4), p(RvPhysReg.SP), 0))
        bb.append(RvInst.Store(MemWidth.BYTE, p(RvPhysReg.A0), p(RvPhysReg.SP), 4))
        bb.append(RvInst.Load(MemWidth.BYTE, p(RvPhysReg.A5), p(RvPhysReg.SP), 4))
        bb.append(RvInst.Call("extern_func", argRegs = listOf(RvPhysReg.A0)))
        bb.append(RvInst.Ret(listOf(RvPhysReg.A0)))

        val asm = AsmEmitter.emitFunction(mf)

        assertAsmContains(asm, "li  t0, 42")
        assertAsmContains(asm, "la  t1, some_symbol")
        assertAsmContains(asm, "lui  t2, %hi(sym)")
        assertAsmContains(asm, "mv  t3, t0")
        assertAsmContains(asm, "neg  t4, t3")
        assertAsmContains(asm, "not  t5, t4")
        assertAsmContains(asm, "seqz  t6, t5")
        assertAsmContains(asm, "snez  a0, t6")
        assertAsmContains(asm, "add  a1, t0, t1")
        assertAsmContains(asm, "mul  a2, a0, a1")
        assertAsmContains(asm, "addi  a3, a2, 100")
        assertAsmContains(asm, "sw  a3, 0(sp)")
        assertAsmContains(asm, "lw  a4, 0(sp)")
        assertAsmContains(asm, "sb  a0, 4(sp)")
        assertAsmContains(asm, "lbu  a5, 4(sp)")
        assertAsmContains(asm, "call  extern_func")
        assertAsmContains(asm, "ret")
    }

    // -----------------------------------------------------------------------
    //  27. Multiple functions in module
    // -----------------------------------------------------------------------

    @Test
    fun `multiple functions emit correctly in module`() {
        val mf1 = RvMachineFunction("func_a")
        mf1.createBlock("func_a").apply {
            append(RvInst.Li(p(RvPhysReg.A0), 1))
            append(RvInst.Ret(listOf(RvPhysReg.A0)))
        }

        val mf2 = RvMachineFunction("func_b")
        mf2.createBlock("func_b").apply {
            append(RvInst.Li(p(RvPhysReg.A0), 2))
            append(RvInst.Ret(listOf(RvPhysReg.A0)))
        }

        val mf3 = RvMachineFunction("main")
        mf3.createBlock("main").apply {
            append(RvInst.Li(p(RvPhysReg.A0), 0))
            append(RvInst.Ret(listOf(RvPhysReg.A0)))
        }

        val irModule = backend.ir.IrModule()
        val asm = AsmEmitter.emit(listOf(mf1, mf2, mf3), irModule)

        assertAsmContains(asm, "func_a:")
        assertAsmContains(asm, "func_b:")
        assertAsmContains(asm, "main:")
        assertAsmContains(asm, ".globl main")
        // .globl should not appear for func_a or func_b.
        assertAsmNotContains(asm, ".globl func_a")
        assertAsmNotContains(asm, ".globl func_b")
    }

    // -----------------------------------------------------------------------
    //  28. Instructions are indented with 4 spaces
    // -----------------------------------------------------------------------

    @Test
    fun `instructions are indented with 4 spaces`() {
        val mf = RvMachineFunction("indented")
        val bb = mf.createBlock("indented")
        bb.append(RvInst.Li(p(RvPhysReg.A0), 0))
        bb.append(RvInst.Ret(listOf(RvPhysReg.A0)))

        val asm = AsmEmitter.emitFunction(mf)
        val lines = asm.lines().filter { it.isNotBlank() }

        // The function label should NOT be indented.
        assertTrue(lines[0].startsWith("indented:"), "Function label should not be indented")

        // Instructions should be indented with 4 spaces.
        for (line in lines.drop(1)) {
            if (line.isNotBlank()) {
                assertTrue(
                    line.startsWith("    "),
                    "Instruction should be indented with 4 spaces: '$line'"
                )
            }
        }
    }

    // -----------------------------------------------------------------------
    //  29. .option nopic is in module output
    // -----------------------------------------------------------------------

    @Test
    fun `module output has option nopic preamble`() {
        val irModule = backend.ir.IrModule()
        val mf = RvMachineFunction("test")
        mf.createBlock("test").append(RvInst.Ret())

        val asm = AsmEmitter.emit(listOf(mf), irModule)
        assertAsmContains(asm, ".option nopic")
    }

    // -----------------------------------------------------------------------
    //  30. Complex function: alloca + call + callee-saved + spill
    // -----------------------------------------------------------------------

    @Test
    fun `complex function with alloca, call, callee-saved compiles`() {
        val asm = compiled("complex") {
            hasCalls = true
            val bb = createBlock("complex")

            // Alloca slot.
            val slotIdx = allocateStackSlot("local.arr", 16, 4)
            bb.append(RvInst.Comment("slot $slotIdx"))
            bb.append(RvInst.IType(RvArithImmOp.ADDI, v(0), p(RvPhysReg.SP), 0))

            // Store some values.
            bb.append(RvInst.Li(v(1), 100))
            bb.append(RvInst.Store(MemWidth.WORD, v(1), v(0), 0))

            // Call a function.
            bb.append(RvInst.Mv(p(RvPhysReg.A0), v(0)))
            bb.append(RvInst.Call("process", argRegs = listOf(RvPhysReg.A0), resultRegs = listOf(RvPhysReg.A0)))
            bb.append(RvInst.Mv(v(2), p(RvPhysReg.A0)))

            // Load back and add.
            bb.append(RvInst.Load(MemWidth.WORD, v(3), v(0), 0))
            bb.append(RvInst.RType(RvArithOp.ADD, v(4), v(2), v(3)))
            bb.append(RvInst.Mv(p(RvPhysReg.A0), v(4)))
            bb.append(RvInst.Ret(listOf(RvPhysReg.A0)))
        }

        assertNoVregs(asm)
        assertAsmContains(asm, "call  process")
        assertAsmContains(asm, "ret")
        // Should have a frame.
        assertAsmContains(asm, "addi  sp, sp, -")
    }

    // -----------------------------------------------------------------------
    //  31. Immediate arithmetic instructions compile
    // -----------------------------------------------------------------------

    @Test
    fun `immediate arithmetic instructions compile`() {
        val asm = compiled("imm_arith") {
            val bb = createBlock("imm_arith")
            bb.append(RvInst.Li(v(0), 10))
            bb.append(RvInst.IType(RvArithImmOp.ADDI, v(1), v(0), 5))
            bb.append(RvInst.IType(RvArithImmOp.ANDI, v(2), v(1), 0xFF))
            bb.append(RvInst.IType(RvArithImmOp.ORI, v(3), v(2), 0x10))
            bb.append(RvInst.IType(RvArithImmOp.SLLI, v(4), v(3), 2))
            bb.append(RvInst.Mv(p(RvPhysReg.A0), v(4)))
            bb.append(RvInst.Ret(listOf(RvPhysReg.A0)))
        }

        assertNoVregs(asm)
        assertAsmContains(asm, "addi")
        assertAsmContains(asm, "andi")
        assertAsmContains(asm, "ori")
        assertAsmContains(asm, "slli")
    }

    // -----------------------------------------------------------------------
    //  32. M-extension instructions compile
    // -----------------------------------------------------------------------

    @Test
    fun `M-extension multiply and divide instructions compile`() {
        val asm = compiled("m_ext") {
            val bb = createBlock("m_ext")
            bb.append(RvInst.Li(v(0), 7))
            bb.append(RvInst.Li(v(1), 3))
            bb.append(RvInst.RType(RvArithOp.MUL, v(2), v(0), v(1)))
            bb.append(RvInst.RType(RvArithOp.DIV, v(3), v(2), v(1)))
            bb.append(RvInst.RType(RvArithOp.REM, v(4), v(0), v(1)))
            bb.append(RvInst.Mv(p(RvPhysReg.A0), v(4)))
            bb.append(RvInst.Ret(listOf(RvPhysReg.A0)))
        }

        assertNoVregs(asm)
        assertAsmContains(asm, "mul")
        assertAsmContains(asm, "div")
        assertAsmContains(asm, "rem")
    }
}
