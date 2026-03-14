package backend.codegen

import backend.codegen.riscv.*
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Unit tests for [FrameLayout] — the frame finalization pass that:
 *   - Computes stack frame layout and assigns concrete offsets to stack slots.
 *   - Patches placeholder offsets (alloca slot comments + spill marker offsets).
 *   - Inserts prologue (frame allocation + register saves).
 *   - Inserts epilogue (register restores + frame deallocation).
 *   - Handles large offsets (> 12-bit immediate).
 *
 * Each test manually constructs an [RvMachineFunction] with physical registers
 * (post-regalloc), runs [FrameLayout.run], and verifies the resulting instructions.
 */
class FrameLayoutTest {

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    private fun p(reg: RvPhysReg): RvOperand.PhysReg = RvOperand.PhysReg(reg)

    /** Build a machine function, configure it, run frame layout, return the mutated mf. */
    private inline fun framed(
        name: String = "test",
        build: RvMachineFunction.() -> Unit,
    ): RvMachineFunction {
        val mf = RvMachineFunction(name)
        mf.build()
        mf.rebuildCfgEdges()
        FrameLayout.run(mf)
        return mf
    }

    /** Collect all instructions across all blocks as a flat list. */
    private fun allInstructions(mf: RvMachineFunction): List<RvInst> =
        mf.blocks.flatMap { it.instructions }

    /** Collect rendered instruction strings for a block. */
    private fun rendered(block: RvMachineBlock): List<String> =
        block.instructions.map { it.render().trim() }

    /** Collect rendered instruction strings for the entire function. */
    private fun rendered(mf: RvMachineFunction): List<String> =
        allInstructions(mf).map { it.render().trim() }

    /** Check if any instruction in the function matches the given predicate. */
    private fun hasInst(mf: RvMachineFunction, predicate: (RvInst) -> Boolean): Boolean =
        allInstructions(mf).any(predicate)

    /** Find the first instruction matching the predicate, or null. */
    private fun findInst(mf: RvMachineFunction, predicate: (RvInst) -> Boolean): RvInst? =
        allInstructions(mf).firstOrNull(predicate)

    /** Count instructions matching the predicate. */
    private fun countInst(mf: RvMachineFunction, predicate: (RvInst) -> Boolean): Int =
        allInstructions(mf).count(predicate)

    // -----------------------------------------------------------------------
    //  1. Trivial function — no stack slots, no calls, no callee-saved
    // -----------------------------------------------------------------------

    @Test
    fun `trivial function with no stack usage has zero frame size`() {
        val mf = framed {
            val bb = createBlock("entry")
            bb.append(RvInst.Li(p(RvPhysReg.A0), 42))
            bb.append(RvInst.Ret(listOf(RvPhysReg.A0)))
        }

        assertEquals(0, mf.frameSize, "Trivial function should have zero frame size")
        // No prologue/epilogue instructions should be inserted.
        val instrs = rendered(mf)
        assertFalse(instrs.any { it.contains("addi  sp, sp,") },
            "No sp adjustment expected for zero frame")
    }

    // -----------------------------------------------------------------------
    //  2. Function with a single alloca slot
    // -----------------------------------------------------------------------

    @Test
    fun `single alloca slot gets correct offset`() {
        val mf = framed {
            val bb = createBlock("entry")
            // Allocate a 4-byte, 4-aligned stack slot.
            val slotIdx = allocateStackSlot("local.x", 4, 4)
            // Simulate isel's slot address pattern: comment + addi placeholder.
            bb.append(RvInst.Comment("slot $slotIdx"))
            bb.append(RvInst.IType(RvArithImmOp.ADDI, p(RvPhysReg.T0), p(RvPhysReg.SP), 0))
            bb.append(RvInst.Store(MemWidth.WORD, p(RvPhysReg.A0), p(RvPhysReg.T0), 0))
            bb.append(RvInst.Ret())
        }

        // Frame size should be 16 (aligned up from 4).
        assertEquals(16, mf.frameSize, "Frame should be 16-byte aligned")

        // The slot offset should be 0 (at the base of the local area).
        assertEquals(0, mf.stackSlots[0].offset, "Slot offset should be 0")

        // The addi should have been patched from 0 to the real offset (0).
        // And the slot comment should be removed.
        val instrs = rendered(mf)
        assertFalse(instrs.any { it.startsWith("# slot") },
            "Slot comment should be removed after patching")

        // Prologue should adjust sp.
        assertTrue(instrs.any { it == "addi  sp, sp, -16" },
            "Prologue should allocate 16-byte frame")

        // Epilogue should restore sp before ret.
        assertTrue(instrs.any { it == "addi  sp, sp, 16" },
            "Epilogue should deallocate frame")
    }

    // -----------------------------------------------------------------------
    //  3. Function with calls — ra must be saved
    // -----------------------------------------------------------------------

    @Test
    fun `function with calls saves and restores ra`() {
        val mf = framed {
            hasCalls = true
            val bb = createBlock("entry")
            bb.append(RvInst.Li(p(RvPhysReg.A0), 1))
            bb.append(RvInst.Call("foo", argRegs = listOf(RvPhysReg.A0)))
            bb.append(RvInst.Ret(listOf(RvPhysReg.A0)))
        }

        // Frame should be at least 16 bytes (ra save = 4, padded to 16).
        assertTrue(mf.frameSize >= 16, "Frame size should be at least 16")
        assertTrue(mf.frameSize % 16 == 0, "Frame size should be 16-byte aligned")

        val instrs = rendered(mf)

        // Should save ra in prologue.
        assertTrue(instrs.any { it.contains("sw  ra,") && it.contains("(sp)") },
            "Prologue should save ra. Instructions: $instrs")

        // Should restore ra in epilogue.
        assertTrue(instrs.any { it.contains("lw  ra,") && it.contains("(sp)") },
            "Epilogue should restore ra. Instructions: $instrs")
    }

    // -----------------------------------------------------------------------
    //  4. Function with callee-saved registers
    // -----------------------------------------------------------------------

    @Test
    fun `callee-saved registers are saved and restored`() {
        val mf = framed {
            usedCalleeSaved.add(RvPhysReg.S0)
            usedCalleeSaved.add(RvPhysReg.S1)
            val bb = createBlock("entry")
            bb.append(RvInst.Li(p(RvPhysReg.S0), 10))
            bb.append(RvInst.Li(p(RvPhysReg.S1), 20))
            bb.append(RvInst.RType(RvArithOp.ADD, p(RvPhysReg.A0), p(RvPhysReg.S0), p(RvPhysReg.S1)))
            bb.append(RvInst.Ret(listOf(RvPhysReg.A0)))
        }

        assertTrue(mf.frameSize >= 16, "Frame should accommodate two callee-saved regs")

        val instrs = rendered(mf)

        // Both s0 and s1 should be saved.
        assertTrue(instrs.any { it.contains("sw  s0,") && it.contains("(sp)") },
            "s0 should be saved in prologue")
        assertTrue(instrs.any { it.contains("sw  s1,") && it.contains("(sp)") },
            "s1 should be saved in prologue")

        // Both should be restored.
        assertTrue(instrs.any { it.contains("lw  s0,") && it.contains("(sp)") },
            "s0 should be restored in epilogue")
        assertTrue(instrs.any { it.contains("lw  s1,") && it.contains("(sp)") },
            "s1 should be restored in epilogue")
    }

    // -----------------------------------------------------------------------
    //  5. Function with calls AND callee-saved — both ra and sN saved
    // -----------------------------------------------------------------------

    @Test
    fun `ra and callee-saved regs saved when function has calls`() {
        val mf = framed {
            hasCalls = true
            usedCalleeSaved.add(RvPhysReg.S0)
            val bb = createBlock("entry")
            bb.append(RvInst.Li(p(RvPhysReg.S0), 100))
            bb.append(RvInst.Call("bar"))
            bb.append(RvInst.Mv(p(RvPhysReg.A0), p(RvPhysReg.S0)))
            bb.append(RvInst.Ret(listOf(RvPhysReg.A0)))
        }

        val instrs = rendered(mf)

        // ra and s0 both saved.
        assertTrue(instrs.any { it.contains("sw  ra,") },
            "ra should be saved")
        assertTrue(instrs.any { it.contains("sw  s0,") },
            "s0 should be saved")

        // ra and s0 both restored.
        assertTrue(instrs.any { it.contains("lw  ra,") },
            "ra should be restored")
        assertTrue(instrs.any { it.contains("lw  s0,") },
            "s0 should be restored")
    }

    // -----------------------------------------------------------------------
    //  6. Prologue is at the beginning, epilogue is before ret
    // -----------------------------------------------------------------------

    @Test
    fun `prologue is first and epilogue is immediately before ret`() {
        val mf = framed {
            hasCalls = true
            val bb = createBlock("entry")
            bb.append(RvInst.Li(p(RvPhysReg.A0), 0))
            bb.append(RvInst.Call("nop"))
            bb.append(RvInst.Ret(listOf(RvPhysReg.A0)))
        }

        val entry = mf.entryBlock()
        val instrs = entry.instructions

        // First instruction should be the sp adjustment (prologue).
        val first = instrs[0]
        assertTrue(
            first is RvInst.IType
                    && first.op == RvArithImmOp.ADDI
                    && first.rd == p(RvPhysReg.SP)
                    && first.rs1 == p(RvPhysReg.SP)
                    && first.imm < 0,
            "First instruction should be sp -= frameSize, got: ${first.render()}"
        )

        // Last instruction should be ret.
        val last = instrs.last()
        assertTrue(last is RvInst.Ret, "Last instruction should be ret")

        // Second-to-last should be sp restoration (epilogue).
        val beforeRet = instrs[instrs.size - 2]
        assertTrue(
            beforeRet is RvInst.IType
                    && beforeRet.op == RvArithImmOp.ADDI
                    && beforeRet.rd == p(RvPhysReg.SP)
                    && beforeRet.rs1 == p(RvPhysReg.SP)
                    && beforeRet.imm > 0,
            "Instruction before ret should be sp += frameSize, got: ${beforeRet.render()}"
        )
    }

    // -----------------------------------------------------------------------
    //  7. Multiple return sites get epilogue
    // -----------------------------------------------------------------------

    @Test
    fun `epilogue inserted before every ret in multi-block function`() {
        val mf = framed {
            hasCalls = true
            val entry = createBlock("entry")
            val taken = createBlock("taken")
            val fallthrough = createBlock("fallthrough")

            entry.append(RvInst.Li(p(RvPhysReg.A0), 1))
            entry.append(RvInst.Call("check"))
            entry.append(RvInst.Branch(RvBranchCond.BNE, p(RvPhysReg.A0), p(RvPhysReg.ZERO), "taken"))
            entry.append(RvInst.J("fallthrough"))

            taken.append(RvInst.Li(p(RvPhysReg.A0), 1))
            taken.append(RvInst.Ret(listOf(RvPhysReg.A0)))

            fallthrough.append(RvInst.Li(p(RvPhysReg.A0), 0))
            fallthrough.append(RvInst.Ret(listOf(RvPhysReg.A0)))
        }

        // Count total number of sp restores (epilogue) — should be 2 (one per ret).
        val spRestores = countInst(mf) { inst ->
            inst is RvInst.IType
                    && inst.op == RvArithImmOp.ADDI
                    && inst.rd == p(RvPhysReg.SP)
                    && inst.rs1 == p(RvPhysReg.SP)
                    && inst.imm > 0
        }
        assertEquals(2, spRestores, "Each ret should have its own epilogue sp restore")

        // Count ra restores — should also be 2.
        val raRestores = countInst(mf) { inst ->
            inst is RvInst.Load
                    && inst.rd == p(RvPhysReg.RA)
                    && inst.base == p(RvPhysReg.SP)
        }
        assertEquals(2, raRestores, "Each ret should restore ra")
    }

    // -----------------------------------------------------------------------
    //  8. Alloca slot offset patching — slot comment is removed
    // -----------------------------------------------------------------------

    @Test
    fun `slot comment is removed and addi is patched`() {
        val mf = framed {
            val bb = createBlock("entry")
            val slot0 = allocateStackSlot("x", 4, 4)
            val slot1 = allocateStackSlot("y", 4, 4)

            // Slot 0 address.
            bb.append(RvInst.Comment("slot $slot0"))
            bb.append(RvInst.IType(RvArithImmOp.ADDI, p(RvPhysReg.T0), p(RvPhysReg.SP), 0))

            // Slot 1 address.
            bb.append(RvInst.Comment("slot $slot1"))
            bb.append(RvInst.IType(RvArithImmOp.ADDI, p(RvPhysReg.T1), p(RvPhysReg.SP), 0))

            bb.append(RvInst.Ret())
        }

        // No slot comments should remain.
        val instrs = rendered(mf)
        assertFalse(instrs.any { it.startsWith("# slot") },
            "Slot comments should be removed")

        // The two slots should have different offsets.
        assertNotEquals(mf.stackSlots[0].offset, mf.stackSlots[1].offset,
            "Two distinct slots should have different offsets")
    }

    // -----------------------------------------------------------------------
    //  9. Spill marker offset patching (negative markers)
    // -----------------------------------------------------------------------

    @Test
    fun `spill load marker offsets are patched`() {
        val mf = framed {
            val bb = createBlock("entry")
            // Allocate a spill slot.
            val slotIdx = allocateStackSlot("spill.v0", 4, 4)

            // Simulate spill load with marker offset: -(slotIdx + 1) * 256
            val markerOffset = -(slotIdx + 1) * 256
            bb.append(RvInst.Load(MemWidth.WORD, p(RvPhysReg.T0), p(RvPhysReg.SP), markerOffset))
            bb.append(RvInst.Mv(p(RvPhysReg.A0), p(RvPhysReg.T0)))
            bb.append(RvInst.Ret(listOf(RvPhysReg.A0)))
        }

        // The marker offset should have been replaced.
        val loads = allInstructions(mf).filterIsInstance<RvInst.Load>()
            .filter { it.base == p(RvPhysReg.SP) }
        for (load in loads) {
            assertTrue(load.offset >= 0,
                "Spill load offset should be non-negative after patching, got: ${load.offset}")
        }
    }

    @Test
    fun `spill store marker offsets are patched`() {
        val mf = framed {
            val bb = createBlock("entry")
            val slotIdx = allocateStackSlot("spill.v1", 4, 4)

            val markerOffset = -(slotIdx + 1) * 256
            bb.append(RvInst.Li(p(RvPhysReg.T0), 99))
            bb.append(RvInst.Store(MemWidth.WORD, p(RvPhysReg.T0), p(RvPhysReg.SP), markerOffset))
            bb.append(RvInst.Ret())
        }

        val stores = allInstructions(mf).filterIsInstance<RvInst.Store>()
            .filter { it.base == p(RvPhysReg.SP) }
        for (store in stores) {
            assertTrue(store.offset >= 0,
                "Spill store offset should be non-negative after patching, got: ${store.offset}")
        }
    }

    // -----------------------------------------------------------------------
    //  10. Multiple spill slots get distinct offsets
    // -----------------------------------------------------------------------

    @Test
    fun `multiple spill slots have distinct non-overlapping offsets`() {
        val mf = framed {
            val bb = createBlock("entry")
            val slot0 = allocateStackSlot("spill.v0", 4, 4)
            val slot1 = allocateStackSlot("spill.v1", 4, 4)
            val slot2 = allocateStackSlot("spill.v2", 4, 4)

            // Loads with marker offsets.
            for (idx in listOf(slot0, slot1, slot2)) {
                val marker = -(idx + 1) * 256
                bb.append(RvInst.Load(MemWidth.WORD, p(RvPhysReg.T0), p(RvPhysReg.SP), marker))
            }
            bb.append(RvInst.Ret())
        }

        val offsets = mf.stackSlots.map { it.offset }.toSet()
        assertEquals(3, offsets.size, "Three slots should have three distinct offsets: ${mf.stackSlots.map { it.offset }}")
    }

    // -----------------------------------------------------------------------
    //  11. Frame size is 16-byte aligned
    // -----------------------------------------------------------------------

    @Test
    fun `frame size is always 16-byte aligned`() {
        // Allocate various sizes and verify alignment.
        for (slotSize in listOf(1, 2, 3, 4, 5, 8, 12, 13, 16, 20, 100)) {
            val mf = RvMachineFunction("test_$slotSize")
            val bb = mf.createBlock("entry")
            mf.allocateStackSlot("slot", slotSize, 4)
            bb.append(RvInst.Ret())
            mf.rebuildCfgEdges()
            FrameLayout.run(mf)

            assertTrue(mf.frameSize % 16 == 0,
                "Frame size ${mf.frameSize} should be 16-byte aligned for slot size $slotSize")
            assertTrue(mf.frameSize >= slotSize,
                "Frame size ${mf.frameSize} should be >= slot size $slotSize")
        }
    }

    // -----------------------------------------------------------------------
    //  12. Slot alignment is respected
    // -----------------------------------------------------------------------

    @Test
    fun `slot alignment is honored`() {
        val mf = framed {
            val bb = createBlock("entry")
            // 1-byte slot followed by 4-byte aligned slot.
            allocateStackSlot("byte_slot", 1, 1)
            allocateStackSlot("word_slot", 4, 4)
            bb.append(RvInst.Ret())
        }

        val byteSlot = mf.stackSlots[0]
        val wordSlot = mf.stackSlots[1]

        // The word slot should be 4-byte aligned.
        assertEquals(0, wordSlot.offset % 4,
            "Word slot offset ${wordSlot.offset} should be 4-byte aligned")
        // The word slot should come after the byte slot.
        assertTrue(wordSlot.offset >= byteSlot.offset + byteSlot.size,
            "Word slot should not overlap with byte slot")
    }

    // -----------------------------------------------------------------------
    //  13. No prologue/epilogue when frame size is 0
    // -----------------------------------------------------------------------

    @Test
    fun `no prologue or epilogue when frame is empty`() {
        val mf = framed {
            val bb = createBlock("entry")
            bb.append(RvInst.Li(p(RvPhysReg.A0), 0))
            bb.append(RvInst.Ret(listOf(RvPhysReg.A0)))
        }

        assertEquals(0, mf.frameSize)
        val instrs = allInstructions(mf)

        // No sp adjustments.
        assertFalse(instrs.any {
            it is RvInst.IType
                    && it.op == RvArithImmOp.ADDI
                    && it.rd == p(RvPhysReg.SP)
                    && it.rs1 == p(RvPhysReg.SP)
        }, "No sp adjustments expected for empty frame")

        // No saves/restores.
        assertFalse(instrs.any { it is RvInst.Store && it.base == p(RvPhysReg.SP) },
            "No stores to sp expected for empty frame")
        assertFalse(instrs.any { it is RvInst.Load && it.base == p(RvPhysReg.SP) },
            "No loads from sp expected for empty frame")
    }

    // -----------------------------------------------------------------------
    //  14. Callee-saved registers are saved in order, restored in reverse
    // -----------------------------------------------------------------------

    @Test
    fun `callee-saved regs saved in index order and restored in reverse`() {
        val mf = framed {
            usedCalleeSaved.add(RvPhysReg.S2)
            usedCalleeSaved.add(RvPhysReg.S0)
            usedCalleeSaved.add(RvPhysReg.S5)
            val bb = createBlock("entry")
            bb.append(RvInst.Ret())
        }

        val entry = mf.entryBlock()
        val instrs = entry.instructions

        // Find all stores to sp (saves) — they should be in index order: s0, s2, s5.
        val saves = instrs.filterIsInstance<RvInst.Store>()
            .filter { it.base == p(RvPhysReg.SP) }
            .mapNotNull { store ->
                val rs = store.rs
                if (rs is RvOperand.PhysReg && rs.reg in CALLEE_SAVED_REGS) rs.reg else null
            }
        assertEquals(listOf(RvPhysReg.S0, RvPhysReg.S2, RvPhysReg.S5), saves,
            "Callee-saved saves should be in register index order")

        // Find all loads from sp (restores) — they should be in reverse order: s5, s2, s0.
        val restores = instrs.filterIsInstance<RvInst.Load>()
            .filter { it.base == p(RvPhysReg.SP) }
            .mapNotNull { load ->
                val rd = load.rd
                if (rd is RvOperand.PhysReg && rd.reg in CALLEE_SAVED_REGS) rd.reg else null
            }
        assertEquals(listOf(RvPhysReg.S5, RvPhysReg.S2, RvPhysReg.S0), restores,
            "Callee-saved restores should be in reverse index order")
    }

    // -----------------------------------------------------------------------
    //  15. Combined: alloca + spill + callee-saved + calls
    // -----------------------------------------------------------------------

    @Test
    fun `complex function with alloca, spills, callee-saved, and calls`() {
        val mf = framed {
            hasCalls = true
            usedCalleeSaved.add(RvPhysReg.S0)
            usedCalleeSaved.add(RvPhysReg.S1)

            val bb = createBlock("entry")

            // Alloca slot.
            val slot0 = allocateStackSlot("local.x", 8, 4)
            bb.append(RvInst.Comment("slot $slot0"))
            bb.append(RvInst.IType(RvArithImmOp.ADDI, p(RvPhysReg.T0), p(RvPhysReg.SP), 0))

            // Spill slot.
            val slot1 = allocateStackSlot("spill.v0", 4, 4)
            val marker = -(slot1 + 1) * 256
            bb.append(RvInst.Store(MemWidth.WORD, p(RvPhysReg.T1), p(RvPhysReg.SP), marker))

            bb.append(RvInst.Call("work"))

            // Spill reload.
            bb.append(RvInst.Load(MemWidth.WORD, p(RvPhysReg.T1), p(RvPhysReg.SP), marker))

            bb.append(RvInst.Mv(p(RvPhysReg.A0), p(RvPhysReg.T1)))
            bb.append(RvInst.Ret(listOf(RvPhysReg.A0)))
        }

        // Verify frame size is 16-byte aligned.
        assertTrue(mf.frameSize % 16 == 0,
            "Frame size ${mf.frameSize} should be 16-byte aligned")

        // Frame must accommodate: local(8) + spill(4) + s0(4) + s1(4) + ra(4) = 24, aligned to 32.
        assertTrue(mf.frameSize >= 24,
            "Frame size ${mf.frameSize} should be at least 24 bytes")

        val instrs = rendered(mf)

        // No remaining slot comments.
        assertFalse(instrs.any { it.startsWith("# slot") },
            "No slot comments should remain")

        // Saves: ra, s0, s1.
        assertTrue(instrs.any { it.contains("sw  ra,") }, "Should save ra")
        assertTrue(instrs.any { it.contains("sw  s0,") }, "Should save s0")
        assertTrue(instrs.any { it.contains("sw  s1,") }, "Should save s1")

        // Restores.
        assertTrue(instrs.any { it.contains("lw  ra,") }, "Should restore ra")
        assertTrue(instrs.any { it.contains("lw  s0,") }, "Should restore s0")
        assertTrue(instrs.any { it.contains("lw  s1,") }, "Should restore s1")

        // No negative offsets should remain.
        val allLoads = allInstructions(mf).filterIsInstance<RvInst.Load>()
        val allStores = allInstructions(mf).filterIsInstance<RvInst.Store>()
        for (load in allLoads) {
            if (load.base == p(RvPhysReg.SP)) {
                assertTrue(load.offset >= 0,
                    "Load offset should be >= 0 after frame layout, got ${load.offset}")
            }
        }
        for (store in allStores) {
            if (store.base == p(RvPhysReg.SP)) {
                assertTrue(store.offset >= 0,
                    "Store offset should be >= 0 after frame layout, got ${store.offset}")
            }
        }
    }

    // -----------------------------------------------------------------------
    //  16. Frame size with outgoing arg area
    // -----------------------------------------------------------------------

    @Test
    fun `outgoing arg area is included in frame size`() {
        val mf = framed {
            hasCalls = true
            outgoingArgAreaSize = 8  // 2 overflow args
            val bb = createBlock("entry")
            bb.append(RvInst.Call("many_args"))
            bb.append(RvInst.Ret())
        }

        // Frame should include: outgoing(8) + ra(4) = 12, aligned to 16.
        assertTrue(mf.frameSize >= 12,
            "Frame should include outgoing arg area")
        assertEquals(16, mf.frameSize,
            "Frame should be 16-byte aligned")
    }

    // -----------------------------------------------------------------------
    //  17. sp adjustment uses correct negative/positive values
    // -----------------------------------------------------------------------

    @Test
    fun `prologue subtracts and epilogue adds frame size`() {
        val mf = framed {
            allocateStackSlot("x", 4, 4)
            val bb = createBlock("entry")
            bb.append(RvInst.Ret())
        }

        val frameSize = mf.frameSize
        assertTrue(frameSize > 0)

        val entry = mf.entryBlock()

        // Prologue: addi sp, sp, -frameSize
        val prologueAddi = entry.instructions.filterIsInstance<RvInst.IType>()
            .firstOrNull {
                it.op == RvArithImmOp.ADDI
                        && it.rd == p(RvPhysReg.SP)
                        && it.rs1 == p(RvPhysReg.SP)
                        && it.imm < 0
            }
        assertTrue(prologueAddi != null, "Should have prologue sp adjustment")
        assertEquals(-frameSize, prologueAddi.imm,
            "Prologue should subtract frame size from sp")

        // Epilogue: addi sp, sp, +frameSize
        val epilogueAddi = entry.instructions.filterIsInstance<RvInst.IType>()
            .firstOrNull {
                it.op == RvArithImmOp.ADDI
                        && it.rd == p(RvPhysReg.SP)
                        && it.rs1 == p(RvPhysReg.SP)
                        && it.imm > 0
            }
        assertTrue(epilogueAddi != null, "Should have epilogue sp adjustment")
        assertEquals(frameSize, epilogueAddi.imm,
            "Epilogue should add frame size to sp")
    }

    // -----------------------------------------------------------------------
    //  18. debugRender after frame layout shows physical registers only
    // -----------------------------------------------------------------------

    @Test
    fun `debugRender after frame layout shows only physical registers`() {
        val mf = framed {
            hasCalls = true
            usedCalleeSaved.add(RvPhysReg.S0)
            val bb = createBlock("entry")
            bb.append(RvInst.Li(p(RvPhysReg.S0), 42))
            bb.append(RvInst.Call("work"))
            bb.append(RvInst.Mv(p(RvPhysReg.A0), p(RvPhysReg.S0)))
            bb.append(RvInst.Ret(listOf(RvPhysReg.A0)))
        }

        val rendered = mf.debugRender()
        assertFalse(Regex("""v\d+""").containsMatchIn(rendered),
            "No virtual registers should appear after frame layout: $rendered")
    }

    // -----------------------------------------------------------------------
    //  19. Empty blocks (just a jump) don't get epilogue
    // -----------------------------------------------------------------------

    @Test
    fun `blocks without ret don't get epilogue`() {
        val mf = framed {
            hasCalls = true
            val entry = createBlock("entry")
            val exit = createBlock("exit")

            entry.append(RvInst.Call("work"))
            entry.append(RvInst.J("exit"))

            exit.append(RvInst.Li(p(RvPhysReg.A0), 0))
            exit.append(RvInst.Ret(listOf(RvPhysReg.A0)))
        }

        // Entry block should NOT have ra restore (no ret there).
        val entryInstrs = mf.blocks[0].instructions
        val entryRaRestores = entryInstrs.filterIsInstance<RvInst.Load>()
            .count { it.rd == p(RvPhysReg.RA) && it.base == p(RvPhysReg.SP) }
        assertEquals(0, entryRaRestores,
            "Entry block (no ret) should not have ra restore")

        // Exit block should have ra restore.
        val exitInstrs = mf.blocks[1].instructions
        val exitRaRestores = exitInstrs.filterIsInstance<RvInst.Load>()
            .count { it.rd == p(RvPhysReg.RA) && it.base == p(RvPhysReg.SP) }
        assertEquals(1, exitRaRestores,
            "Exit block (has ret) should have ra restore")
    }

    // -----------------------------------------------------------------------
    //  20. Slot offsets are deterministic
    // -----------------------------------------------------------------------

    @Test
    fun `slot offsets are deterministic across runs`() {
        fun buildAndLayout(): List<Int> {
            val mf = RvMachineFunction("test")
            val bb = mf.createBlock("entry")
            mf.allocateStackSlot("a", 4, 4)
            mf.allocateStackSlot("b", 8, 8)
            mf.allocateStackSlot("c", 1, 1)
            bb.append(RvInst.Ret())
            mf.rebuildCfgEdges()
            FrameLayout.run(mf)
            return mf.stackSlots.map { it.offset }
        }

        val offsets1 = buildAndLayout()
        val offsets2 = buildAndLayout()
        assertEquals(offsets1, offsets2,
            "Slot offsets should be deterministic: $offsets1 vs $offsets2")
    }

    // -----------------------------------------------------------------------
    //  21. Only one prologue at the entry
    // -----------------------------------------------------------------------

    @Test
    fun `prologue is only in the entry block`() {
        val mf = framed {
            hasCalls = true
            val entry = createBlock("entry")
            val loop = createBlock("loop")
            val exit = createBlock("exit")

            entry.append(RvInst.Call("init"))
            entry.append(RvInst.J("loop"))

            loop.append(RvInst.Call("work"))
            loop.append(RvInst.Branch(RvBranchCond.BNE, p(RvPhysReg.A0), p(RvPhysReg.ZERO), "loop"))
            loop.append(RvInst.J("exit"))

            exit.append(RvInst.Ret())
        }

        // Count sp subtracts (prologue) across all blocks.
        val spSubtracts = allInstructions(mf).count { inst ->
            inst is RvInst.IType
                    && inst.op == RvArithImmOp.ADDI
                    && inst.rd == p(RvPhysReg.SP)
                    && inst.rs1 == p(RvPhysReg.SP)
                    && inst.imm < 0
        }
        assertEquals(1, spSubtracts,
            "There should be exactly one sp subtract (prologue) in the entire function")
    }

    // -----------------------------------------------------------------------
    //  22. Slot with size > 4 bytes
    // -----------------------------------------------------------------------

    @Test
    fun `large slot is properly accommodated`() {
        val mf = framed {
            val bb = createBlock("entry")
            allocateStackSlot("array", 100, 4)
            bb.append(RvInst.Ret())
        }

        assertTrue(mf.frameSize >= 100,
            "Frame should be at least 100 bytes for a 100-byte slot")
        assertTrue(mf.frameSize % 16 == 0,
            "Frame size ${mf.frameSize} should be 16-byte aligned")
        // 100 aligned to 16 = 112.
        assertEquals(112, mf.frameSize,
            "100 bytes should round up to 112 (next multiple of 16)")
    }

    // -----------------------------------------------------------------------
    //  23. Multiple blocks with different slot accesses
    // -----------------------------------------------------------------------

    @Test
    fun `slot offset patching works across multiple blocks`() {
        val mf = framed {
            val entry = createBlock("entry")
            val other = createBlock("other")

            val slotIdx = allocateStackSlot("x", 4, 4)

            // Access slot in entry.
            entry.append(RvInst.Comment("slot $slotIdx"))
            entry.append(RvInst.IType(RvArithImmOp.ADDI, p(RvPhysReg.T0), p(RvPhysReg.SP), 0))
            entry.append(RvInst.J("other"))

            // Access same slot in other block.
            other.append(RvInst.Comment("slot $slotIdx"))
            other.append(RvInst.IType(RvArithImmOp.ADDI, p(RvPhysReg.T1), p(RvPhysReg.SP), 0))
            other.append(RvInst.Ret())
        }

        // No slot comments should remain in either block.
        for (block in mf.blocks) {
            for (inst in block.instructions) {
                if (inst is RvInst.Comment) {
                    assertFalse(inst.text.startsWith("slot "),
                        "Slot comment should be removed in block ${block.label}")
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    //  24. Mixed alloca and spill slots in the same function
    // -----------------------------------------------------------------------

    @Test
    fun `alloca and spill slots coexist correctly`() {
        val mf = framed {
            val bb = createBlock("entry")

            // Alloca slot (from isel).
            val allocaSlot = allocateStackSlot("local.y", 4, 4)
            bb.append(RvInst.Comment("slot $allocaSlot"))
            bb.append(RvInst.IType(RvArithImmOp.ADDI, p(RvPhysReg.T0), p(RvPhysReg.SP), 0))

            // Spill slot (from regalloc).
            val spillSlot = allocateStackSlot("spill.v5", 4, 4)
            val marker = -(spillSlot + 1) * 256
            bb.append(RvInst.Load(MemWidth.WORD, p(RvPhysReg.T1), p(RvPhysReg.SP), marker))

            bb.append(RvInst.Ret())
        }

        // Both slots should have non-overlapping offsets.
        val s0 = mf.stackSlots[0]
        val s1 = mf.stackSlots[1]
        val range0 = s0.offset until (s0.offset + s0.size)
        val range1 = s1.offset until (s1.offset + s1.size)
        assertFalse(range0.any { it in range1 },
            "Alloca and spill slots should not overlap: [$s0] vs [$s1]")
    }

    // -----------------------------------------------------------------------
    //  25. Frame layout with no callee-saved but with calls
    // -----------------------------------------------------------------------

    @Test
    fun `function with calls but no callee-saved only saves ra`() {
        val mf = framed {
            hasCalls = true
            // No callee-saved registers used.
            val bb = createBlock("entry")
            bb.append(RvInst.Li(p(RvPhysReg.A0), 1))
            bb.append(RvInst.Call("foo", argRegs = listOf(RvPhysReg.A0)))
            bb.append(RvInst.Ret(listOf(RvPhysReg.A0)))
        }

        val instrs = rendered(mf)

        // Should save ra.
        assertTrue(instrs.any { it.contains("sw  ra,") }, "Should save ra")

        // Should NOT save any callee-saved regs.
        for (reg in SAVED_REGS) {
            assertFalse(instrs.any { it.contains("sw  ${reg.abiName},") },
                "Should NOT save ${reg.abiName} when not used")
        }
    }

    // -----------------------------------------------------------------------
    //  26. Verify frame size includes everything
    // -----------------------------------------------------------------------

    @Test
    fun `frame size accounts for outgoing args, slots, callee-saved, and ra`() {
        val mf = framed {
            hasCalls = true
            outgoingArgAreaSize = 12  // 3 overflow args
            usedCalleeSaved.add(RvPhysReg.S0)
            usedCalleeSaved.add(RvPhysReg.S1)
            usedCalleeSaved.add(RvPhysReg.S2)

            val bb = createBlock("entry")
            allocateStackSlot("local", 20, 4)
            bb.append(RvInst.Call("work"))
            bb.append(RvInst.Ret())
        }

        // Minimum frame: outgoing(12) + local(20) + s0(4) + s1(4) + s2(4) + ra(4) = 48
        // Aligned to 16 = 48 (already a multiple of 16).
        assertTrue(mf.frameSize >= 48,
            "Frame size ${mf.frameSize} should be at least 48")
        assertTrue(mf.frameSize % 16 == 0,
            "Frame size ${mf.frameSize} should be 16-byte aligned")
    }

    // -----------------------------------------------------------------------
    //  27. Non-slot comments are preserved
    // -----------------------------------------------------------------------

    @Test
    fun `non-slot comments are preserved through frame layout`() {
        val mf = framed {
            val bb = createBlock("entry")
            bb.append(RvInst.Comment("This is a normal comment"))
            bb.append(RvInst.Li(p(RvPhysReg.A0), 0))
            bb.append(RvInst.Ret(listOf(RvPhysReg.A0)))
        }

        val instrs = rendered(mf)
        assertTrue(instrs.any { it == "# This is a normal comment" },
            "Non-slot comments should be preserved")
    }

    // -----------------------------------------------------------------------
    //  28. Verify slot offsets respect outgoing arg area
    // -----------------------------------------------------------------------

    @Test
    fun `slot offsets are above outgoing arg area`() {
        val mf = framed {
            outgoingArgAreaSize = 16  // 4 overflow args (16 bytes)

            val bb = createBlock("entry")
            val slot0 = allocateStackSlot("local", 4, 4)
            bb.append(RvInst.Comment("slot $slot0"))
            bb.append(RvInst.IType(RvArithImmOp.ADDI, p(RvPhysReg.T0), p(RvPhysReg.SP), 0))
            bb.append(RvInst.Ret())
        }

        // The local slot should be at offset >= 16 (above the outgoing arg area).
        assertTrue(mf.stackSlots[0].offset >= 16,
            "Local slot offset ${mf.stackSlots[0].offset} should be >= outgoing arg area (16)")
    }
}
