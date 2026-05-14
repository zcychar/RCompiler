package backend.codegen

import backend.codegen.riscv.RvInst
import backend.codegen.riscv.RvMachineFunction
import backend.codegen.riscv.RvBranchCond
import backend.codegen.riscv.RvOperand
import backend.codegen.riscv.RvPhysReg
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FallthroughJumpEliminationTest {
    @Test
    fun `removes unconditional jump to next block`() {
        val mf = RvMachineFunction("fallthrough")
        val entry = mf.createBlock("fallthrough")
        val next = mf.createBlock("fallthrough.next")

        entry.append(RvInst.J("fallthrough.next"))
        next.append(RvInst.Ret())
        mf.rebuildCfgEdges()

        val removed = FallthroughJumpElimination.run(mf)

        assertEquals(1, removed)
        assertTrue(entry.instructions.isEmpty(), "jump to next block should be removed")
        assertEquals(listOf(next), entry.successors, "fallthrough keeps the same CFG edge")
    }

    @Test
    fun `keeps unconditional jump to non-next block`() {
        val mf = RvMachineFunction("keep")
        val entry = mf.createBlock("keep")
        mf.createBlock("keep.next")
        mf.createBlock("keep.target")

        entry.append(RvInst.J("keep.target"))
        mf.rebuildCfgEdges()

        val removed = FallthroughJumpElimination.run(mf)

        assertEquals(0, removed)
        assertTrue(entry.instructions.single() is RvInst.J)
    }

    @Test
    fun `removes conditional fallthrough jump while preserving branch`() {
        val mf = RvMachineFunction("branch")
        val entry = mf.createBlock("branch")
        mf.createBlock("branch.fallthrough")
        mf.createBlock("branch.taken")

        entry.append(
            RvInst.Branch(
                RvBranchCond.BNE,
                RvOperand.PhysReg(RvPhysReg.A0),
                RvOperand.PhysReg(RvPhysReg.ZERO),
                "branch.taken",
            )
        )
        entry.append(RvInst.J("branch.fallthrough"))
        mf.rebuildCfgEdges()

        val removed = FallthroughJumpElimination.run(mf)

        assertEquals(1, removed)
        assertTrue(entry.instructions.single() is RvInst.Branch)
        assertFalse(entry.instructions.any { it is RvInst.J })
    }
}
