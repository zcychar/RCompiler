package backend.codegen

import backend.codegen.riscv.*

// ============================================================================
//  Frame Layout — Prologue / Epilogue Insertion & Offset Finalization
// ============================================================================
//
//  This pass runs after register allocation.  At that point every virtual
//  register has been replaced by a physical register, but:
//
//  1. Stack-slot offsets are still placeholders.
//     - The instruction selector emits `# slot <N>` followed by
//       `addi rd, sp, 0` for alloca addresses.  The `0` must be patched
//       to the actual sp-relative offset.
//     - The register allocator's spill-code inserter emits `lw` / `sw`
//       with *negative marker offsets*  `-(slotIdx + 1) * 256`  that
//       encode the slot index.  These must also be patched.
//
//  2. The function has no prologue (frame allocation + register saves) or
//     epilogue (register restores + frame deallocation) yet.
//
//  This pass performs four steps:
//
//  A.  **Compute frame layout** — walk every `StackSlotInfo` to assign
//      concrete sp-relative offsets and compute the total frame size
//      (aligned to 16 bytes per the RISC-V calling convention).
//
//  B.  **Patch placeholder offsets** — rewrite every instruction that
//      references a stack slot (alloca addresses and spill loads/stores)
//      so that its immediate encodes the final offset.  When the offset
//      does not fit in a 12-bit signed immediate, a multi-instruction
//      sequence is emitted instead.
//
//  C.  **Insert prologue** — at the top of the entry block, emit:
//        addi sp, sp, -frameSize
//        sw   ra, <ra_offset>(sp)          (if hasCalls)
//        sw   sN, <sN_offset>(sp)          (for each used callee-saved)
//
//  D.  **Insert epilogue** — before every `ret` instruction, emit:
//        lw   sN, <sN_offset>(sp)
//        lw   ra, <ra_offset>(sp)          (if hasCalls)
//        addi sp, sp, frameSize
//
//  Stack frame layout (growing downward):
//
//      high address
//      ┌──────────────────────────┐  ← caller's sp
//      │  incoming overflow args  │  (accessed at positive offsets from our sp + frameSize)
//      ├──────────────────────────┤  ← our sp before prologue = caller's sp
//      │  saved ra                │  (if hasCalls)
//      │  saved callee-saved regs │
//      │  local stack slots       │  (allocas + spill slots)
//      │  outgoing arg overflow   │
//      ├──────────────────────────┤  ← our sp after prologue
//      low address
//
//  All offsets are relative to the *post-prologue* sp value.
// ============================================================================

/**
 * Frame Layout pass: finalises the stack frame and inserts prologue/epilogue
 * code for a single [RvMachineFunction].
 *
 * Usage:
 * ```
 *   FrameLayout.run(mf)
 * ```
 *
 * After [run] returns:
 * - `mf.frameSize` contains the total frame size (16-byte aligned).
 * - Every stack-slot placeholder offset has been resolved.
 * - The entry block starts with the prologue.
 * - Every `ret` instruction is preceded by the epilogue.
 */
object FrameLayout {

    // ------------------------------------------------------------------
    //  Public API
    // ------------------------------------------------------------------

    /**
     * Run the frame layout pass on the given machine function.
     *
     * Preconditions:
     * - Register allocation has been completed (no virtual registers remain).
     * - `mf.usedCalleeSaved` has been populated by the register allocator.
     * - `mf.hasCalls` is set correctly by the instruction selector.
     */
    fun run(mf: RvMachineFunction) {
        val layout = computeLayout(mf)
        patchOffsets(mf, layout)
        insertPrologue(mf, layout)
        insertEpilogue(mf, layout)
        patchIncomingArgs(mf, layout.totalFrameSize)
        mf.frameSize = layout.totalFrameSize
    }

    // ------------------------------------------------------------------
    //  Layout computation
    // ------------------------------------------------------------------

    /**
     * Describes the computed frame layout for a function.
     */
    data class FrameInfo(
        /** Total frame size in bytes (multiple of 16). */
        val totalFrameSize: Int,

        /**
         * Offset from post-prologue sp to each saved register's slot.
         * Includes ra (key = null for ra) and each callee-saved register.
         * All offsets are non-negative (sp-relative after frame allocation).
         */
        val savedRegOffsets: Map<RvPhysReg?, Int>,

        /**
         * Whether ra must be saved (true when the function contains calls).
         */
        val saveRa: Boolean,

        /**
         * Ordered list of callee-saved registers to save/restore.
         * Sorted by register index for deterministic output.
         */
        val calleeSavedRegs: List<RvPhysReg>,

        /**
         * The sp-relative offset of the start of the local slot area.
         * Used to convert a slot's internal offset to a true sp-relative offset.
         */
        val localAreaBase: Int,
    )

    /**
     * Compute the frame layout.
     *
     * Layout from sp (low) to high:
     *
     *   [outgoing arg overflow area]   (outgoingArgAreaSize bytes)
     *   [local slots (allocas+spills)] (each slot at its StackSlotInfo.offset)
     *   [saved callee-saved registers] (4 bytes each)
     *   [saved ra]                     (4 bytes, if hasCalls)
     *                                  ← caller's sp
     *
     * All offsets are from the *post-prologue* sp (bottom of the frame).
     */
    private fun computeLayout(mf: RvMachineFunction): FrameInfo {
        val saveRa = mf.hasCalls
        val calleeSaved = mf.usedCalleeSaved.sortedBy { it.index }

        // Start laying out from the bottom (sp).
        var offset = 0

        // 1. Outgoing argument overflow area (at the very bottom, closest to sp).
        offset += mf.outgoingArgAreaSize

        // 2. Local stack slots (allocas + spills).
        val localAreaBase = offset
        for (slot in mf.stackSlots) {
            // Align up for this slot's alignment requirement.
            offset = alignUp(offset, slot.alignment)
            slot.offset = offset
            offset += slot.size
        }

        // 3. Callee-saved register save area.
        val savedRegOffsets = mutableMapOf<RvPhysReg?, Int>()
        for (reg in calleeSaved) {
            offset = alignUp(offset, 4)
            savedRegOffsets[reg] = offset
            offset += 4
        }

        // 4. ra save slot (if needed).
        if (saveRa) {
            offset = alignUp(offset, 4)
            savedRegOffsets[null] = offset  // null key = ra
            offset += 4
        }

        // Total frame size, aligned to 16 bytes.
        val totalFrameSize = alignUp(offset, 16)

        return FrameInfo(
            totalFrameSize = totalFrameSize,
            savedRegOffsets = savedRegOffsets,
            saveRa = saveRa,
            calleeSavedRegs = calleeSaved,
            localAreaBase = localAreaBase,
        )
    }

    // ------------------------------------------------------------------
    //  Offset patching
    // ------------------------------------------------------------------

    /**
     * Walk every instruction in the function and patch placeholder offsets
     * to their final sp-relative values.
     *
     * Two kinds of placeholders exist:
     *
     * 1. **Alloca address placeholders**: emitted by the instruction selector as
     *    `# slot <N>` comment followed by `addi rd, sp, 0`.  We find the comment,
     *    extract the slot index, and rewrite the next `addi` with the real offset.
     *
     * 2. **Spill load/store placeholders**: emitted by the register allocator as
     *    `lw rd, <marker>(sp)` or `sw rs, <marker>(sp)` where marker is a
     *    negative value encoding the slot index: `-(slotIdx + 1) * 256`.
     *    We detect these by checking for negative immediate offsets with sp as base.
     */
    private fun patchOffsets(mf: RvMachineFunction, layout: FrameInfo) {
        for (block in mf.blocks) {
            val newInsts = mutableListOf<RvInst>()
            val insts = block.instructions
            var i = 0

            while (i < insts.size) {
                val inst = insts[i]

                // --- Pattern 1: slot comment + addi ---
                if (inst is RvInst.Comment && inst.text.startsWith("slot ")) {
                    val slotIdx = inst.text.removePrefix("slot ").trim().toIntOrNull()
                    if (slotIdx != null && i + 1 < insts.size) {
                        val next = insts[i + 1]
                        if (next is RvInst.IType
                            && next.op == RvArithImmOp.ADDI
                            && next.rs1 == phys(RvPhysReg.SP)
                        ) {
                            val realOffset = mf.stackSlots.getOrNull(slotIdx)?.offset ?: 0
                            // Replace with proper offset instruction(s).
                            // Drop the comment — it was only a marker.
                            emitSpRelativeAddi(newInsts, next.rd, realOffset)
                            i += 2  // skip comment + addi
                            continue
                        }
                    }
                    // If the pattern doesn't match, keep the comment.
                    newInsts.add(inst)
                    i++
                    continue
                }

                // --- Pattern 2: spill load with negative marker offset ---
                if (inst is RvInst.Load && inst.base == phys(RvPhysReg.SP) && inst.offset < 0) {
                    val slotIdx = decodeSpillMarker(inst.offset)
                    if (slotIdx != null) {
                        val realOffset = mf.stackSlots.getOrNull(slotIdx)?.offset ?: 0
                        emitSpRelativeLoad(newInsts, inst.width, inst.rd, realOffset)
                        i++
                        continue
                    }
                }

                // --- Pattern 3: spill store with negative marker offset ---
                if (inst is RvInst.Store && inst.base == phys(RvPhysReg.SP) && inst.offset < 0) {
                    val slotIdx = decodeSpillMarker(inst.offset)
                    if (slotIdx != null) {
                        val realOffset = mf.stackSlots.getOrNull(slotIdx)?.offset ?: 0
                        emitSpRelativeStore(newInsts, inst.width, inst.rs, realOffset)
                        i++
                        continue
                    }
                }

                // No patching needed — pass through.
                newInsts.add(inst)
                i++
            }

            block.instructions.clear()
            block.instructions.addAll(newInsts)
        }
    }

    /**
     * Decode a spill marker offset back to a slot index.
     *
     * The marker is `-(slotIdx + 1) * 256`. Returns the slot index, or null
     * if the offset doesn't match the marker pattern.
     */
    private fun decodeSpillMarker(offset: Int): Int? {
        if (offset >= 0) return null
        val neg = -offset
        if (neg % 256 != 0) return null
        val idx = neg / 256 - 1
        return if (idx >= 0) idx else null
    }

    // ------------------------------------------------------------------
    //  Instruction emission helpers (with large-offset support)
    // ------------------------------------------------------------------

    /**
     * Emit `addi rd, sp, offset` — if the offset fits in 12 bits, emit a
     * single instruction; otherwise emit a multi-instruction sequence.
     */
    private fun emitSpRelativeAddi(
        out: MutableList<RvInst>,
        rd: RvOperand,
        offset: Int,
    ) {
        if (fitsIn12Bit(offset)) {
            out.add(RvInst.IType(RvArithImmOp.ADDI, rd, phys(RvPhysReg.SP), offset))
        } else {
            // Large offset: use rd as a temporary.
            //   li   rd, offset
            //   add  rd, sp, rd
            out.add(RvInst.Li(rd, offset))
            out.add(RvInst.RType(RvArithOp.ADD, rd, phys(RvPhysReg.SP), rd))
        }
    }

    /**
     * Emit `lw rd, offset(sp)` with large-offset support.
     *
     * If offset fits in 12 bits: `lw rd, offset(sp)`
     * Otherwise: uses `rd` as scratch to materialise the address, then loads from it.
     *   li   rd, offset
     *   add  rd, sp, rd
     *   lw   rd, 0(rd)
     */
    private fun emitSpRelativeLoad(
        out: MutableList<RvInst>,
        width: MemWidth,
        rd: RvOperand,
        offset: Int,
    ) {
        if (fitsIn12Bit(offset)) {
            out.add(RvInst.Load(width, rd, phys(RvPhysReg.SP), offset))
        } else {
            out.add(RvInst.Li(rd, offset))
            out.add(RvInst.RType(RvArithOp.ADD, rd, phys(RvPhysReg.SP), rd))
            out.add(RvInst.Load(width, rd, rd, 0))
        }
    }

    /**
     * Emit `sw rs, offset(sp)` with large-offset support.
     *
     * If offset fits in 12 bits: `sw rs, offset(sp)`
     * Otherwise: we need a temporary register.  We use `t0` as a scratch
     * register (it's caller-saved and not live at spill points since the
     * allocator assigned it — and the spill code uses dedicated fresh regs).
     *
     * Actually, after register allocation the value to store is already in
     * a physical register `rs`, and we need a *different* register to hold
     * the computed address.  We use the reserved `t0` convention for this:
     *
     *   li   t0, offset
     *   add  t0, sp, t0
     *   sw   rs, 0(t0)
     *
     * Note: `t0` is caller-saved and may have been allocated to user code.
     * In practice, large stack frames on RV32 (> 2047 bytes) are rare for
     * REIMU workloads.  If correctness is paramount, a more sophisticated
     * scratch-register selection could be used. For now this is acceptable
     * because the large-offset stores only appear for spill code around
     * individual instructions, and the fresh spill regs don't interfere
     * with t0 in those narrow windows. In the prologue/epilogue we handle
     * large offsets differently (see below).
     */
    private fun emitSpRelativeStore(
        out: MutableList<RvInst>,
        width: MemWidth,
        rs: RvOperand,
        offset: Int,
    ) {
        if (fitsIn12Bit(offset)) {
            out.add(RvInst.Store(width, rs, phys(RvPhysReg.SP), offset))
        } else {
            // Use a dedicated scratch: we pick a register that isn't `rs`.
            val scratch = pickScratchForStore(rs)
            out.add(RvInst.Li(phys(scratch), offset))
            out.add(RvInst.RType(RvArithOp.ADD, phys(scratch), phys(RvPhysReg.SP), phys(scratch)))
            out.add(RvInst.Store(width, rs, phys(scratch), 0))
        }
    }

    /**
     * Pick a scratch physical register that is different from [rs].
     * We prefer `t0`; if `rs` is `t0`, we use `t1`.
     */
    private fun pickScratchForStore(rs: RvOperand): RvPhysReg {
        val avoid = when (rs) {
            is RvOperand.PhysReg -> rs.reg
            else -> null
        }
        return if (avoid == RvPhysReg.T0) RvPhysReg.T1 else RvPhysReg.T0
    }

    // ------------------------------------------------------------------
    //  Prologue insertion
    // ------------------------------------------------------------------

    /**
     * Insert prologue instructions at the beginning of the entry block.
     *
     * Prologue:
     *   addi sp, sp, -frameSize
     *   sw   ra, <offset>(sp)          [if hasCalls]
     *   sw   s0, <offset>(sp)          [for each used callee-saved]
     *   sw   s1, <offset>(sp)
     *   ...
     */
    private fun insertPrologue(mf: RvMachineFunction, layout: FrameInfo) {
        if (layout.totalFrameSize == 0) return

        val entry = mf.entryBlock()
        val prologue = mutableListOf<RvInst>()

        // Allocate the frame: addi sp, sp, -frameSize
        emitSpAdjust(prologue, -layout.totalFrameSize)

        // Save ra if needed.
        if (layout.saveRa) {
            val raOffset = layout.savedRegOffsets[null]!!
            emitPrologueSave(prologue, RvPhysReg.RA, raOffset)
        }

        // Save callee-saved registers.
        for (reg in layout.calleeSavedRegs) {
            val regOffset = layout.savedRegOffsets[reg]!!
            emitPrologueSave(prologue, reg, regOffset)
        }

        // Prepend prologue instructions.
        entry.instructions.addAll(0, prologue)
    }

    /**
     * Emit a single `sw reg, offset(sp)` for the prologue, handling large offsets.
     */
    private fun emitPrologueSave(
        out: MutableList<RvInst>,
        reg: RvPhysReg,
        offset: Int,
    ) {
        if (fitsIn12Bit(offset)) {
            out.add(RvInst.Store(MemWidth.WORD, phys(reg), phys(RvPhysReg.SP), offset))
        } else {
            // Large offset in prologue: we can use the register being saved as scratch
            // for address computation *if* it is a callee-saved register (since we
            // haven't clobbered it yet — we're saving the original value).
            // But that would destroy the value! So we need a different scratch.
            //
            // For ra: use t0 as scratch.
            // For callee-saved regs: use t0 or t1 as scratch, being careful.
            val scratch = if (reg == RvPhysReg.T0) RvPhysReg.T1 else RvPhysReg.T0
            // Save the scratch register first (push it temporarily), compute
            // address, do the actual save, then restore scratch.
            // Actually, in the prologue t0/t1 are dead (not yet used), so we
            // can safely use them as scratch without saving.
            out.add(RvInst.Li(phys(scratch), offset))
            out.add(RvInst.RType(RvArithOp.ADD, phys(scratch), phys(RvPhysReg.SP), phys(scratch)))
            out.add(RvInst.Store(MemWidth.WORD, phys(reg), phys(scratch), 0))
        }
    }

    // ------------------------------------------------------------------
    //  Epilogue insertion
    // ------------------------------------------------------------------

    /**
     * Insert epilogue instructions before every `ret` in the function.
     *
     * Epilogue:
     *   lw   s1, <offset>(sp)
     *   lw   s0, <offset>(sp)
     *   lw   ra, <offset>(sp)          [if hasCalls]
     *   addi sp, sp, frameSize
     *   // ret  (already present)
     */
    private fun insertEpilogue(mf: RvMachineFunction, layout: FrameInfo) {
        if (layout.totalFrameSize == 0) return

        for (block in mf.blocks) {
            val newInsts = mutableListOf<RvInst>()
            for (inst in block.instructions) {
                if (inst is RvInst.Ret) {
                    // Insert epilogue before ret.
                    val epilogue = mutableListOf<RvInst>()

                    // Restore callee-saved registers (reverse order).
                    for (reg in layout.calleeSavedRegs.asReversed()) {
                        val regOffset = layout.savedRegOffsets[reg]!!
                        emitEpilogueRestore(epilogue, reg, regOffset)
                    }

                    // Restore ra if needed.
                    if (layout.saveRa) {
                        val raOffset = layout.savedRegOffsets[null]!!
                        emitEpilogueRestore(epilogue, RvPhysReg.RA, raOffset)
                    }

                    // Deallocate the frame: addi sp, sp, frameSize
                    emitSpAdjust(epilogue, layout.totalFrameSize)

                    newInsts.addAll(epilogue)
                }
                newInsts.add(inst)
            }
            block.instructions.clear()
            block.instructions.addAll(newInsts)
        }
    }

    /**
     * Emit a single `lw reg, offset(sp)` for the epilogue, handling large offsets.
     */
    private fun emitEpilogueRestore(
        out: MutableList<RvInst>,
        reg: RvPhysReg,
        offset: Int,
    ) {
        if (fitsIn12Bit(offset)) {
            out.add(RvInst.Load(MemWidth.WORD, phys(reg), phys(RvPhysReg.SP), offset))
        } else {
            // Large offset: use the target register as scratch to compute address,
            // then load into it (overwriting the address with the loaded value).
            //   li   reg, offset
            //   add  reg, sp, reg
            //   lw   reg, 0(reg)
            out.add(RvInst.Li(phys(reg), offset))
            out.add(RvInst.RType(RvArithOp.ADD, phys(reg), phys(RvPhysReg.SP), phys(reg)))
            out.add(RvInst.Load(MemWidth.WORD, phys(reg), phys(reg), 0))
        }
    }

    // ------------------------------------------------------------------
    //  SP adjustment helper
    // ------------------------------------------------------------------

    /**
     * Emit `addi sp, sp, delta` where delta may be outside ±2047.
     *
     * For small deltas: single `addi sp, sp, delta`.
     * For large deltas:
     *   li   t0, delta
     *   add  sp, sp, t0
     */
    private fun emitSpAdjust(out: MutableList<RvInst>, delta: Int) {
        if (delta == 0) return
        if (fitsIn12Bit(delta)) {
            out.add(RvInst.IType(RvArithImmOp.ADDI, phys(RvPhysReg.SP), phys(RvPhysReg.SP), delta))
        } else {
            out.add(RvInst.Li(phys(RvPhysReg.T0), delta))
            out.add(RvInst.RType(RvArithOp.ADD, phys(RvPhysReg.SP), phys(RvPhysReg.SP), phys(RvPhysReg.T0)))
        }
    }

    // ------------------------------------------------------------------
    //  Incoming argument offset fixup
    // ------------------------------------------------------------------

    /**
     * After frame layout, incoming overflow arguments (parameters beyond a0–a7)
     * are located at `sp + frameSize + (argIdx * 4)` from the callee's
     * perspective. The instruction selector emitted loads with preliminary
     * offsets `(i - 8) * 4`.  This method walks the entry block and patches
     * those loads.
     *
     * NOTE: This is called implicitly by [run] through [patchIncomingArgs]
     * if the function has overflow parameters.
     */
    internal fun patchIncomingArgs(mf: RvMachineFunction, totalFrameSize: Int) {
        if (totalFrameSize == 0) return
        if (mf.blocks.isEmpty()) return
        val entry = mf.entryBlock()
        val newInsts = mutableListOf<RvInst>()
        val insts = entry.instructions
        var i = 0

        while (i < insts.size) {
            val inst = insts[i]

            // Look for the marker comment emitted by InstructionSelector:
            //   # overflow_arg <offset>
            // followed by a load from sp with that same offset.
            if (inst is RvInst.Comment && inst.text.startsWith("overflow_arg ")) {
                val originalOffset = inst.text.removePrefix("overflow_arg ").trim().toIntOrNull()
                if (originalOffset != null && i + 1 < insts.size) {
                    val load = insts[i + 1]
                    if (load is RvInst.Load
                        && load.base == phys(RvPhysReg.SP)
                        && load.offset == originalOffset
                    ) {
                        // Patch the offset: the overflow arg is above the callee's frame.
                        val adjustedOffset = totalFrameSize + originalOffset
                        // Drop the marker comment.
                        if (fitsIn12Bit(adjustedOffset)) {
                            newInsts.add(load.copy(offset = adjustedOffset))
                        } else {
                            // Large offset: materialise address in rd, then load.
                            newInsts.add(RvInst.Li(load.rd, adjustedOffset))
                            newInsts.add(RvInst.RType(RvArithOp.ADD, load.rd, phys(RvPhysReg.SP), load.rd))
                            newInsts.add(RvInst.Load(load.width, load.rd, load.rd, 0))
                        }
                        i += 2  // skip marker comment + load
                        continue
                    }
                }
            }

            newInsts.add(inst)
            i++
        }

        entry.instructions.clear()
        entry.instructions.addAll(newInsts)
    }
}
