package backend.codegen

import backend.codegen.riscv.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Unit tests for [LivenessAnalysis] on hand-built machine-IR CFGs.
 *
 * Each test manually constructs an [RvMachineFunction] with [RvMachineBlock]s
 * and [RvInst]s, runs [LivenessAnalysis.analyze], and checks the resulting
 * live-in / live-out sets and interference edges.
 */
class LivenessAnalysisTest {

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    /** Shorthand: create a virtual register operand. */
    private fun v(id: Int): RvOperand.Reg = RvOperand.Reg(id)

    /** Shorthand: create a physical register operand. */
    private fun p(reg: RvPhysReg): RvOperand.PhysReg = RvOperand.PhysReg(reg)

    /** Shorthand: create a RegKey for a vreg. */
    private fun vk(id: Int): RegKey = RegKey.Vreg(id)

    /** Shorthand: create a RegKey for a phys reg. */
    private fun pk(reg: RvPhysReg): RegKey = RegKey.Phys(reg)

    /** Build a machine function, run the body to populate it, rebuild CFG, run analysis. */
    private inline fun analyzed(
        name: String = "test",
        build: RvMachineFunction.() -> Unit,
    ): LivenessResult {
        val mf = RvMachineFunction(name)
        mf.build()
        mf.rebuildCfgEdges()
        return LivenessAnalysis.analyze(mf)
    }

    // -----------------------------------------------------------------------
    //  1. Single block — straight-line code
    // -----------------------------------------------------------------------

    @Test
    fun `single block - linear chain`() {
        // v0 = li 1
        // v1 = li 2
        // v2 = add v0, v1
        // ret (using v2 via a0)
        val result = analyzed {
            val bb = createBlock("entry")
            bb.append(RvInst.Li(v(0), 1))
            bb.append(RvInst.Li(v(1), 2))
            bb.append(RvInst.RType(RvArithOp.ADD, v(2), v(0), v(1)))
            bb.append(RvInst.Mv(p(RvPhysReg.A0), v(2)))
            bb.append(RvInst.Ret(listOf(RvPhysReg.A0)))
        }

        val entry = result.mf.blocks[0]

        // Nothing is live-in to entry (all values defined here).
        assertTrue(result.liveIn[entry]!!.isEmpty(), "liveIn should be empty for entry")

        // Nothing is live-out of entry (only block, ret terminates).
        assertTrue(result.liveOut[entry]!!.isEmpty(), "liveOut should be empty for single-block")
    }

    @Test
    fun `single block - unused def is not live`() {
        // v0 = li 42   (dead — never used)
        // ret
        val result = analyzed {
            val bb = createBlock("entry")
            bb.append(RvInst.Li(v(0), 42))
            bb.append(RvInst.Ret())
        }

        val entry = result.mf.blocks[0]
        assertTrue(result.liveIn[entry]!!.isEmpty())
        assertTrue(result.liveOut[entry]!!.isEmpty())
    }

    // -----------------------------------------------------------------------
    //  2. Two blocks — value flows across edge
    // -----------------------------------------------------------------------

    @Test
    fun `two blocks - value live across edge`() {
        // entry:
        //   v0 = li 10
        //   j exit
        // exit:
        //   mv a0, v0
        //   ret
        val result = analyzed {
            val entry = createBlock("entry")
            val exit = createBlock("exit")

            entry.append(RvInst.Li(v(0), 10))
            entry.append(RvInst.J("exit"))

            exit.append(RvInst.Mv(p(RvPhysReg.A0), v(0)))
            exit.append(RvInst.Ret(listOf(RvPhysReg.A0)))
        }

        val entry = result.mf.blocks[0]
        val exit = result.mf.blocks[1]

        // v0 is used in exit so it's live-in to exit.
        assertContains(result.liveIn[exit]!!, vk(0))

        // v0 is live-out of entry (because live-in to successor exit).
        assertContains(result.liveOut[entry]!!, vk(0))

        // v0 is NOT live-in to entry (defined there).
        assertFalse(vk(0) in result.liveIn[entry]!!)
    }

    // -----------------------------------------------------------------------
    //  3. Diamond CFG
    // -----------------------------------------------------------------------

    @Test
    fun `diamond CFG - value live through both paths`() {
        //        entry
        //       /     \
        //    left     right
        //       \     /
        //        merge
        //
        // entry:
        //   v0 = li 5
        //   bne v0, zero, left     (fall-through to right via j)
        //   j right
        // left:
        //   v1 = addi v0, 1
        //   j merge
        // right:
        //   v2 = addi v0, 2
        //   j merge
        // merge:
        //   mv a0, v0              (v0 used in merge → live through both paths)
        //   ret
        val result = analyzed {
            val entry = createBlock("entry")
            val left = createBlock("left")
            val right = createBlock("right")
            val merge = createBlock("merge")

            entry.append(RvInst.Li(v(0), 5))
            entry.append(RvInst.Branch(RvBranchCond.BNE, v(0), p(RvPhysReg.ZERO), "left"))
            entry.append(RvInst.J("right"))

            left.append(RvInst.IType(RvArithImmOp.ADDI, v(1), v(0), 1))
            left.append(RvInst.J("merge"))

            right.append(RvInst.IType(RvArithImmOp.ADDI, v(2), v(0), 2))
            right.append(RvInst.J("merge"))

            merge.append(RvInst.Mv(p(RvPhysReg.A0), v(0)))
            merge.append(RvInst.Ret(listOf(RvPhysReg.A0)))
        }

        val entryB = result.mf.blocks[0]
        val leftB = result.mf.blocks[1]
        val rightB = result.mf.blocks[2]
        val mergeB = result.mf.blocks[3]

        // v0 is live-in to merge (used there).
        assertContains(result.liveIn[mergeB]!!, vk(0))

        // v0 is live-out of left and right (because live-in to merge).
        assertContains(result.liveOut[leftB]!!, vk(0))
        assertContains(result.liveOut[rightB]!!, vk(0))

        // v0 is also live-in to left and right (used in addi and passed through).
        assertContains(result.liveIn[leftB]!!, vk(0))
        assertContains(result.liveIn[rightB]!!, vk(0))

        // v1 is NOT live-out of left (never used after left).
        assertFalse(vk(1) in result.liveOut[leftB]!!, "v1 should be dead after left")

        // v2 is NOT live-out of right.
        assertFalse(vk(2) in result.liveOut[rightB]!!, "v2 should be dead after right")
    }

    // -----------------------------------------------------------------------
    //  4. Loop CFG
    // -----------------------------------------------------------------------

    @Test
    fun `simple loop - value live across back-edge`() {
        // entry:
        //   v0 = li 0        (accumulator)
        //   v1 = li 10       (limit)
        //   j loop
        // loop:
        //   v2 = add v0, v1  (use both — they must be live-in to loop)
        //   bne v2, v1, loop (back-edge)
        //   j exit
        // exit:
        //   mv a0, v2
        //   ret
        val result = analyzed {
            val entry = createBlock("entry")
            val loop = createBlock("loop")
            val exit = createBlock("exit")

            entry.append(RvInst.Li(v(0), 0))
            entry.append(RvInst.Li(v(1), 10))
            entry.append(RvInst.J("loop"))

            loop.append(RvInst.RType(RvArithOp.ADD, v(2), v(0), v(1)))
            loop.append(RvInst.Branch(RvBranchCond.BNE, v(2), v(1), "loop"))
            loop.append(RvInst.J("exit"))

            exit.append(RvInst.Mv(p(RvPhysReg.A0), v(2)))
            exit.append(RvInst.Ret(listOf(RvPhysReg.A0)))
        }

        val entryB = result.mf.blocks[0]
        val loopB = result.mf.blocks[1]
        val exitB = result.mf.blocks[2]

        // v0, v1 must be live-in to loop (used in ADD).
        assertContains(result.liveIn[loopB]!!, vk(0))
        assertContains(result.liveIn[loopB]!!, vk(1))

        // v2 is live-out of loop (used in exit and also in the branch within loop).
        assertContains(result.liveOut[loopB]!!, vk(2))

        // v1 is live-out of loop (back-edge: live-in to loop includes v1).
        assertContains(result.liveOut[loopB]!!, vk(1))

        // v0 is live-out of loop (back-edge: live-in to loop includes v0).
        assertContains(result.liveOut[loopB]!!, vk(0))

        // v0, v1 are live-out of entry.
        assertContains(result.liveOut[entryB]!!, vk(0))
        assertContains(result.liveOut[entryB]!!, vk(1))
    }

    // -----------------------------------------------------------------------
    //  5. Physical registers (allocatable) participate in liveness
    // -----------------------------------------------------------------------

    @Test
    fun `physical registers are tracked when allocatable`() {
        // entry:
        //   add v0, a0, a1       (uses phys a0, a1)
        //   mv a0, v0
        //   ret
        val result = analyzed {
            val bb = createBlock("entry")
            bb.append(RvInst.RType(RvArithOp.ADD, v(0), p(RvPhysReg.A0), p(RvPhysReg.A1)))
            bb.append(RvInst.Mv(p(RvPhysReg.A0), v(0)))
            bb.append(RvInst.Ret(listOf(RvPhysReg.A0)))
        }

        val entry = result.mf.blocks[0]

        // a0 and a1 are used before being written → live-in.
        assertContains(result.liveIn[entry]!!, pk(RvPhysReg.A0))
        assertContains(result.liveIn[entry]!!, pk(RvPhysReg.A1))
    }

    @Test
    fun `reserved registers are excluded from liveness`() {
        // entry:
        //   addi v0, sp, 0     (sp is reserved)
        //   ret
        val result = analyzed {
            val bb = createBlock("entry")
            bb.append(RvInst.IType(RvArithImmOp.ADDI, v(0), p(RvPhysReg.SP), 0))
            bb.append(RvInst.Ret())
        }

        val entry = result.mf.blocks[0]

        // sp should NOT appear in liveIn (it's reserved).
        assertFalse(
            result.liveIn[entry]!!.any { it is RegKey.Phys && it.reg == RvPhysReg.SP },
            "sp should be excluded from liveness"
        )
    }

    // -----------------------------------------------------------------------
    //  6. Call clobbers
    // -----------------------------------------------------------------------

    @Test
    fun `call clobbers caller-saved registers`() {
        // entry:
        //   v0 = li 5
        //   mv a0, v0
        //   call foo           (clobbers all caller-saved)
        //   mv v1, a0          (result)
        //   mv a0, v1
        //   ret
        //
        // v0 should NOT be live across the call (it's consumed by mv a0, v0
        // before the call).
        val result = analyzed {
            val bb = createBlock("entry")
            bb.append(RvInst.Li(v(0), 5))
            bb.append(RvInst.Mv(p(RvPhysReg.A0), v(0)))
            bb.append(RvInst.Call("foo", argRegs = listOf(RvPhysReg.A0), resultRegs = listOf(RvPhysReg.A0)))
            bb.append(RvInst.Mv(v(1), p(RvPhysReg.A0)))
            bb.append(RvInst.Mv(p(RvPhysReg.A0), v(1)))
            bb.append(RvInst.Ret(listOf(RvPhysReg.A0)))
        }

        val entry = result.mf.blocks[0]
        // Single block: liveIn and liveOut are both empty.
        assertTrue(result.liveIn[entry]!!.isEmpty())
        assertTrue(result.liveOut[entry]!!.isEmpty())
    }

    @Test
    fun `value live across call appears in live-out of block containing call`() {
        // entry:
        //   v0 = li 42
        //   call foo
        //   j exit
        // exit:
        //   mv a0, v0       (v0 survives the call)
        //   ret
        val result = analyzed {
            val entry = createBlock("entry")
            val exit = createBlock("exit")

            entry.append(RvInst.Li(v(0), 42))
            entry.append(RvInst.Call("foo"))
            entry.append(RvInst.J("exit"))

            exit.append(RvInst.Mv(p(RvPhysReg.A0), v(0)))
            exit.append(RvInst.Ret(listOf(RvPhysReg.A0)))
        }

        val entryB = result.mf.blocks[0]

        // v0 is live-out of entry (needed in exit).
        assertContains(result.liveOut[entryB]!!, vk(0))
    }

    // -----------------------------------------------------------------------
    //  7. Per-instruction walk (forEachInstruction)
    // -----------------------------------------------------------------------

    @Test
    fun `forEachInstruction produces correct live sets`() {
        // entry:
        //   v0 = li 1          live-after: {v0}
        //   v1 = li 2          live-after: {v0, v1}
        //   v2 = add v0, v1    live-after: {v2}
        //   mv a0, v2          live-after: {a0}
        //   ret                live-after: {}
        val result = analyzed {
            val bb = createBlock("entry")
            bb.append(RvInst.Li(v(0), 1))           // index 0
            bb.append(RvInst.Li(v(1), 2))           // index 1
            bb.append(RvInst.RType(RvArithOp.ADD, v(2), v(0), v(1)))  // index 2
            bb.append(RvInst.Mv(p(RvPhysReg.A0), v(2)))               // index 3
            bb.append(RvInst.Ret(listOf(RvPhysReg.A0)))               // index 4
        }

        val entry = result.mf.blocks[0]
        val liveSets = mutableListOf<Set<RegKey>>()

        result.forEachInstruction(entry) { _, liveAfter ->
            liveSets.add(liveAfter.toSet()) // snapshot
        }

        // forEachInstruction walks backward, so liveSets[0] is for ret (index 4),
        // liveSets[4] is for li v0 (index 0).
        // Reverse to get forward order.
        liveSets.reverse()

        // index 0: li v0 → live-after = {v0}  (v0 needed later, v1/v2 not yet defined)
        assertEquals(setOf(vk(0)), liveSets[0], "after li v0")

        // index 1: li v1 → live-after = {v0, v1}
        assertEquals(setOf(vk(0), vk(1)), liveSets[1], "after li v1")

        // index 2: add v2, v0, v1 → live-after = {v2}
        assertEquals(setOf(vk(2)), liveSets[2], "after add")

        // index 3: mv a0, v2 → live-after = {a0}
        assertEquals(setOf(pk(RvPhysReg.A0)), liveSets[3], "after mv a0, v2")

        // index 4: ret → live-after = {} (nothing live after ret, liveOut is empty)
        assertTrue(liveSets[4].isEmpty(), "after ret")
    }

    // -----------------------------------------------------------------------
    //  8. Interference edges
    // -----------------------------------------------------------------------

    @Test
    fun `interference edges for overlapping live ranges`() {
        // entry:
        //   v0 = li 1
        //   v1 = li 2         (v0 is still live → v0 interferes with v1)
        //   v2 = add v0, v1   (after def of v2, v0 & v1 are dead)
        //   mv a0, v2
        //   ret
        val result = analyzed {
            val bb = createBlock("entry")
            bb.append(RvInst.Li(v(0), 1))
            bb.append(RvInst.Li(v(1), 2))
            bb.append(RvInst.RType(RvArithOp.ADD, v(2), v(0), v(1)))
            bb.append(RvInst.Mv(p(RvPhysReg.A0), v(2)))
            bb.append(RvInst.Ret(listOf(RvPhysReg.A0)))
        }

        val edges = result.interferenceEdges()

        // v0 and v1 should interfere (both live at def of v1).
        val v0v1 = LivenessResult.orderedPair(vk(0), vk(1))
        assertContains(edges, v0v1, "v0 and v1 should interfere")

        // v2 should NOT interfere with v0 or v1 (they die at the add that defines v2;
        // the add uses v0 and v1, but v2 is defined, so v0 and v1 are in liveAfter
        // when v2 is defined, so they DO interfere with v2 — let's check).
        // Actually: at the add instruction, liveAfter = {v2} (after the instruction),
        // but the interference is computed as: defs={v2}, liveAfter before removing defs
        // and adding uses. Wait — we need to be careful about the convention.
        //
        // The convention in forEachInstruction:
        //   liveAfter is the live set AFTER the instruction (before we process it).
        //   live = liveOut at that point.
        //
        // At the add instruction (scanning backward):
        //   Before processing: live = {v2} (from mv a0, v2 we added v2)
        //   action(add, live={v2}) → defs={v2}, liveAfter={v2}
        //   v2 interferes with everything in liveAfter except itself = nothing extra
        //   Then: live -= v2, live += v0, v1 → live = {v0, v1}
        //
        // So v2 does NOT interfere with v0 or v1 (correct — they don't overlap).
        val v0v2 = LivenessResult.orderedPair(vk(0), vk(2))
        assertFalse(v0v2 in edges, "v0 and v2 should NOT interfere")

        val v1v2 = LivenessResult.orderedPair(vk(1), vk(2))
        assertFalse(v1v2 in edges, "v1 and v2 should NOT interfere")
    }

    @Test
    fun `move does not create interference between src and dst`() {
        // entry:
        //   v0 = li 5
        //   mv v1, v0          (move — v0 and v1 should NOT interfere)
        //   mv a0, v1
        //   ret
        val result = analyzed {
            val bb = createBlock("entry")
            bb.append(RvInst.Li(v(0), 5))
            bb.append(RvInst.Mv(v(1), v(0)))
            bb.append(RvInst.Mv(p(RvPhysReg.A0), v(1)))
            bb.append(RvInst.Ret(listOf(RvPhysReg.A0)))
        }

        val edges = result.interferenceEdges()

        // v0 and v1 should NOT interfere (move special-case).
        // At the mv v1, v0 instruction:
        //   liveAfter = {v1} (v1 needed by mv a0, v1)
        //   defs = {v1}, uses = {v0}, isMove = true
        //   moveSource = v0
        //   v1 interferes with liveAfter \ {v1, v0} = {} → no interference
        val v0v1 = LivenessResult.orderedPair(vk(0), vk(1))
        assertFalse(v0v1 in edges, "move src/dst should not interfere (enables coalescing)")
    }

    @Test
    fun `move creates interference when src is also live independently`() {
        // entry:
        //   v0 = li 1
        //   v1 = li 2
        //   mv v2, v0          (v1 is still live → v2 interferes with v1)
        //   add v3, v1, v2
        //   mv a0, v3
        //   ret
        val result = analyzed {
            val bb = createBlock("entry")
            bb.append(RvInst.Li(v(0), 1))
            bb.append(RvInst.Li(v(1), 2))
            bb.append(RvInst.Mv(v(2), v(0)))
            bb.append(RvInst.RType(RvArithOp.ADD, v(3), v(1), v(2)))
            bb.append(RvInst.Mv(p(RvPhysReg.A0), v(3)))
            bb.append(RvInst.Ret(listOf(RvPhysReg.A0)))
        }

        val edges = result.interferenceEdges()

        // At mv v2, v0: liveAfter = {v1, v2} (v1 needed by add, v2 needed by add)
        // defs={v2}, moveSource=v0
        // v2 interferes with liveAfter \ {v2, v0} = {v1}
        val v1v2 = LivenessResult.orderedPair(vk(1), vk(2))
        assertContains(edges, v1v2, "v1 and v2 should interfere (v1 live at def of v2)")

        // v0 and v2 should NOT interfere (move exception).
        val v0v2 = LivenessResult.orderedPair(vk(0), vk(2))
        assertFalse(v0v2 in edges, "v0 and v2 should not interfere (move)")
    }

    // -----------------------------------------------------------------------
    //  9. Convergence on complex loop
    // -----------------------------------------------------------------------

    @Test
    fun `nested loop converges correctly`() {
        // entry:
        //   v0 = li 0
        //   j outer
        // outer:
        //   v1 = addi v0, 1
        //   j inner
        // inner:
        //   v2 = add v1, v0
        //   bne v2, v0, inner   (inner back-edge)
        //   j outer_end
        // outer_end:
        //   bne v1, v0, outer   (outer back-edge)
        //   j exit
        // exit:
        //   mv a0, v0
        //   ret
        val result = analyzed {
            val entry = createBlock("entry")
            val outer = createBlock("outer")
            val inner = createBlock("inner")
            val outerEnd = createBlock("outer_end")
            val exit = createBlock("exit")

            entry.append(RvInst.Li(v(0), 0))
            entry.append(RvInst.J("outer"))

            outer.append(RvInst.IType(RvArithImmOp.ADDI, v(1), v(0), 1))
            outer.append(RvInst.J("inner"))

            inner.append(RvInst.RType(RvArithOp.ADD, v(2), v(1), v(0)))
            inner.append(RvInst.Branch(RvBranchCond.BNE, v(2), v(0), "inner"))
            inner.append(RvInst.J("outer_end"))

            outerEnd.append(RvInst.Branch(RvBranchCond.BNE, v(1), v(0), "outer"))
            outerEnd.append(RvInst.J("exit"))

            exit.append(RvInst.Mv(p(RvPhysReg.A0), v(0)))
            exit.append(RvInst.Ret(listOf(RvPhysReg.A0)))
        }

        val entryB = result.mf.blocks[0]
        val outerB = result.mf.blocks[1]
        val innerB = result.mf.blocks[2]
        val outerEndB = result.mf.blocks[3]
        val exitB = result.mf.blocks[4]

        // v0 must be live everywhere (used in exit, inner, outer_end, outer).
        assertContains(result.liveOut[entryB]!!, vk(0))
        assertContains(result.liveIn[outerB]!!, vk(0))
        assertContains(result.liveIn[innerB]!!, vk(0))
        assertContains(result.liveIn[outerEndB]!!, vk(0))
        assertContains(result.liveIn[exitB]!!, vk(0))

        // v1 must be live-in to inner (used there) and live-in to outer_end.
        assertContains(result.liveIn[innerB]!!, vk(1))
        assertContains(result.liveIn[outerEndB]!!, vk(1))

        // v1 must be live-out of inner (flows to outer_end and back to inner via back-edge).
        assertContains(result.liveOut[innerB]!!, vk(1))

        // v2 should NOT be live-in to outer (v2 is only defined and used within inner).
        assertFalse(vk(2) in result.liveIn[outerB]!!, "v2 should not escape inner loop")
    }

    // -----------------------------------------------------------------------
    //  10. Empty block (just a jump)
    // -----------------------------------------------------------------------

    @Test
    fun `empty block passes liveness through`() {
        // entry:
        //   v0 = li 1
        //   j middle
        // middle:
        //   j exit
        // exit:
        //   mv a0, v0
        //   ret
        val result = analyzed {
            val entry = createBlock("entry")
            val middle = createBlock("middle")
            val exit = createBlock("exit")

            entry.append(RvInst.Li(v(0), 1))
            entry.append(RvInst.J("middle"))

            middle.append(RvInst.J("exit"))

            exit.append(RvInst.Mv(p(RvPhysReg.A0), v(0)))
            exit.append(RvInst.Ret(listOf(RvPhysReg.A0)))
        }

        val middleB = result.mf.blocks[1]

        // v0 should flow through the empty middle block.
        assertContains(result.liveIn[middleB]!!, vk(0))
        assertContains(result.liveOut[middleB]!!, vk(0))
    }

    // -----------------------------------------------------------------------
    //  11. Store instruction (no defs, only uses)
    // -----------------------------------------------------------------------

    @Test
    fun `store instruction uses but does not def`() {
        // entry:
        //   v0 = li 42
        //   v1 = addi sp, 0        (address — sp is reserved, not tracked)
        //   sw v0, 0(v1)
        //   ret
        val result = analyzed {
            val bb = createBlock("entry")
            bb.append(RvInst.Li(v(0), 42))
            bb.append(RvInst.IType(RvArithImmOp.ADDI, v(1), p(RvPhysReg.SP), 0))
            bb.append(RvInst.Store(MemWidth.WORD, v(0), v(1), 0))
            bb.append(RvInst.Ret())
        }

        val entry = result.mf.blocks[0]
        // All values defined in entry, so liveIn is empty.
        assertTrue(result.liveIn[entry]!!.isEmpty())
    }

    // -----------------------------------------------------------------------
    //  12. Multiple definitions of same vreg in one block
    // -----------------------------------------------------------------------

    @Test
    fun `redefinition kills previous liveness`() {
        // entry:
        //   v0 = li 1
        //   j bb2
        // bb2:
        //   v0 = li 2        (redefine v0 — the old v0 from entry is dead here)
        //   mv a0, v0
        //   ret
        //
        // Note: this is unusual in SSA but can happen after phi lowering
        // or in machine IR that doesn't enforce SSA.
        val result = analyzed {
            val entry = createBlock("entry")
            val bb2 = createBlock("bb2")

            entry.append(RvInst.Li(v(0), 1))
            entry.append(RvInst.J("bb2"))

            bb2.append(RvInst.Li(v(0), 2))
            bb2.append(RvInst.Mv(p(RvPhysReg.A0), v(0)))
            bb2.append(RvInst.Ret(listOf(RvPhysReg.A0)))
        }

        val entryB = result.mf.blocks[0]
        val bb2B = result.mf.blocks[1]

        // v0 is NOT live-in to bb2 because it's redefined before use.
        assertFalse(vk(0) in result.liveIn[bb2B]!!, "v0 should not be live-in to bb2 (redefined)")

        // Therefore v0 is NOT live-out of entry.
        assertFalse(vk(0) in result.liveOut[entryB]!!, "v0 should not be live-out of entry")
    }

    // -----------------------------------------------------------------------
    //  13. Interference with physical registers from call
    // -----------------------------------------------------------------------

    @Test
    fun `call defs create interference with live vregs`() {
        // entry:
        //   v0 = li 100
        //   call bar              (clobbers all caller-saved including a0..a7, t0..t6)
        //   mv a0, v0             (v0 must survive the call)
        //   ret
        //
        // v0 is live across the call, so v0 should interfere with every
        // caller-saved register that the call defines.
        val result = analyzed {
            val bb = createBlock("entry")
            bb.append(RvInst.Li(v(0), 100))
            bb.append(RvInst.Call("bar"))
            bb.append(RvInst.Mv(p(RvPhysReg.A0), v(0)))
            bb.append(RvInst.Ret(listOf(RvPhysReg.A0)))
        }

        val edges = result.interferenceEdges()

        // v0 should interfere with every caller-saved register that is allocatable
        // (t0 is reserved as a scratch register so it's excluded from liveness).
        for (csr in CALLER_SAVED_REGS) {
            if (csr in RESERVED_REGS) continue
            val pair = LivenessResult.orderedPair(vk(0), pk(csr))
            assertContains(edges, pair, "v0 should interfere with ${csr.abiName} (call clobber)")
        }

        // v0 should also interfere with ra (call defines ra implicitly).
        // But ra is a RESERVED reg, so it's excluded from liveness tracking.
        val raKey = pk(RvPhysReg.RA)
        // ra should NOT appear in any edge because it's reserved.
        val hasRa = edges.any { it.first == raKey || it.second == raKey }
        assertFalse(hasRa, "ra is reserved and should not appear in interference edges")
    }

    // -----------------------------------------------------------------------
    //  14. Verify orderedPair is symmetric
    // -----------------------------------------------------------------------

    @Test
    fun `orderedPair produces canonical order`() {
        val a = vk(3)
        val b = vk(7)
        val p1 = LivenessResult.orderedPair(a, b)
        val p2 = LivenessResult.orderedPair(b, a)
        assertEquals(p1, p2, "orderedPair should be symmetric")
    }

    // -----------------------------------------------------------------------
    //  15. RegKey from various operand types
    // -----------------------------------------------------------------------

    @Test
    fun `toRegKey returns null for non-register operands`() {
        assertTrue(RvOperand.Imm(42).toRegKey() == null)
        assertTrue(RvOperand.Label("foo").toRegKey() == null)
        assertTrue(RvOperand.Reloc(RelocKind.HI, "bar").toRegKey() == null)
    }

    @Test
    fun `toRegKey returns null for reserved physical registers`() {
        assertTrue(RvOperand.PhysReg(RvPhysReg.ZERO).toRegKey() == null)
        assertTrue(RvOperand.PhysReg(RvPhysReg.RA).toRegKey() == null)
        assertTrue(RvOperand.PhysReg(RvPhysReg.SP).toRegKey() == null)
        assertTrue(RvOperand.PhysReg(RvPhysReg.GP).toRegKey() == null)
        assertTrue(RvOperand.PhysReg(RvPhysReg.TP).toRegKey() == null)
    }

    @Test
    fun `toRegKey returns valid key for allocatable phys regs`() {
        val key = RvOperand.PhysReg(RvPhysReg.T1).toRegKey()
        assertEquals(RegKey.Phys(RvPhysReg.T1), key)
    }

    @Test
    fun `toRegKey for vreg ignores width`() {
        val k1 = RvOperand.Reg(5, 4).toRegKey()
        val k2 = RvOperand.Reg(5, 1).toRegKey()
        assertEquals(k1, k2, "same vreg id with different widths should produce equal keys")
    }

    // -----------------------------------------------------------------------
    //  16. Large diamond with phi-like pattern
    // -----------------------------------------------------------------------

    @Test
    fun `phi-like pattern - different values merge`() {
        // This simulates what the instruction selector produces after
        // lowering phi nodes: different values defined on different paths,
        // with moves on each edge into a common vreg.
        //
        // entry:
        //   v0 = li 1
        //   bne v0, zero, left
        //   j right
        // left:
        //   v1 = li 10
        //   mv v3, v1            (phi move)
        //   j merge
        // right:
        //   v2 = li 20
        //   mv v3, v2            (phi move)
        //   j merge
        // merge:
        //   mv a0, v3
        //   ret
        val result = analyzed {
            val entry = createBlock("entry")
            val left = createBlock("left")
            val right = createBlock("right")
            val merge = createBlock("merge")

            entry.append(RvInst.Li(v(0), 1))
            entry.append(RvInst.Branch(RvBranchCond.BNE, v(0), p(RvPhysReg.ZERO), "left"))
            entry.append(RvInst.J("right"))

            left.append(RvInst.Li(v(1), 10))
            left.append(RvInst.Mv(v(3), v(1)))
            left.append(RvInst.J("merge"))

            right.append(RvInst.Li(v(2), 20))
            right.append(RvInst.Mv(v(3), v(2)))
            right.append(RvInst.J("merge"))

            merge.append(RvInst.Mv(p(RvPhysReg.A0), v(3)))
            merge.append(RvInst.Ret(listOf(RvPhysReg.A0)))
        }

        val mergeB = result.mf.blocks[3]
        val leftB = result.mf.blocks[1]
        val rightB = result.mf.blocks[2]

        // v3 is live-in to merge.
        assertContains(result.liveIn[mergeB]!!, vk(3))

        // v3 is live-out of left and right.
        assertContains(result.liveOut[leftB]!!, vk(3))
        assertContains(result.liveOut[rightB]!!, vk(3))

        // v1 should NOT be live-out of left (consumed by mv v3, v1).
        assertFalse(vk(1) in result.liveOut[leftB]!!, "v1 should be dead after left")

        // v2 should NOT be live-out of right.
        assertFalse(vk(2) in result.liveOut[rightB]!!, "v2 should be dead after right")
    }
}
