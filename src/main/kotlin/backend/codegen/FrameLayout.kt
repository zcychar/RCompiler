package backend.codegen

// Finalizes stack frames and inserts prologue/epilogue code.

import backend.TargetLayout
import backend.codegen.riscv.*

object FrameLayout {

    fun run(mf: RvMachineFunction) {
        val layout = computeLayout(mf)
        patchOffsets(mf, layout)
        insertPrologue(mf, layout)
        insertEpilogue(mf, layout)
        patchIncomingArgs(mf, layout.totalFrameSize)
        mf.frameSize = layout.totalFrameSize
    }

    data class FrameInfo(

        val totalFrameSize: Int,

        val savedRegOffsets: Map<RvPhysReg?, Int>,

        val saveRa: Boolean,

        val calleeSavedRegs: List<RvPhysReg>,

        val localAreaBase: Int,
    )

    private fun computeLayout(mf: RvMachineFunction): FrameInfo {
        val saveRa = mf.hasCalls
        val calleeSaved = mf.usedCalleeSaved.sortedBy { it.index }

        var offset = 0

        offset += mf.outgoingArgAreaSize

        val localAreaBase = offset
        for (slot in mf.stackSlots) {

            offset = alignUp(offset, slot.alignment)
            slot.offset = offset
            offset += slot.size
        }

        val savedRegOffsets = mutableMapOf<RvPhysReg?, Int>()
        for (reg in calleeSaved) {
            offset = alignUp(offset, TargetLayout.REGISTER_BYTES)
            savedRegOffsets[reg] = offset
            offset += TargetLayout.REGISTER_BYTES
        }

        if (saveRa) {
            offset = alignUp(offset, TargetLayout.REGISTER_BYTES)
            savedRegOffsets[null] = offset
            offset += TargetLayout.REGISTER_BYTES
        }

        val totalFrameSize = alignUp(offset, 16)

        return FrameInfo(
            totalFrameSize = totalFrameSize,
            savedRegOffsets = savedRegOffsets,
            saveRa = saveRa,
            calleeSavedRegs = calleeSaved,
            localAreaBase = localAreaBase,
        )
    }

    private fun patchOffsets(mf: RvMachineFunction, layout: FrameInfo) {
        for (block in mf.blocks) {
            val newInsts = mutableListOf<RvInst>()
            val insts = block.instructions
            var i = 0

            while (i < insts.size) {
                val inst = insts[i]

                if (inst is RvInst.Comment && inst.text.startsWith("slot ")) {
                    val slotIdx = inst.text.removePrefix("slot ").trim().toIntOrNull()
                    if (slotIdx != null && i + 1 < insts.size) {
                        val next = insts[i + 1]
                        if (next is RvInst.IType
                            && next.op == RvArithImmOp.ADDI
                            && next.rs1 == phys(RvPhysReg.SP)
                        ) {
                            val realOffset = mf.stackSlots.getOrNull(slotIdx)?.offset ?: 0

                            emitSpRelativeAddi(newInsts, next.rd, realOffset)
                            i += 2
                            continue
                        }
                    }

                    newInsts.add(inst)
                    i++
                    continue
                }

                if (inst is RvInst.Load && inst.base == phys(RvPhysReg.SP) && inst.offset < 0) {
                    val slotIdx = decodeSpillMarker(inst.offset)
                    if (slotIdx != null) {
                        val realOffset = mf.stackSlots.getOrNull(slotIdx)?.offset ?: 0
                        emitSpRelativeLoad(newInsts, inst.width, inst.rd, realOffset)
                        i++
                        continue
                    }
                }

                if (inst is RvInst.Store && inst.base == phys(RvPhysReg.SP) && inst.offset < 0) {
                    val slotIdx = decodeSpillMarker(inst.offset)
                    if (slotIdx != null) {
                        val realOffset = mf.stackSlots.getOrNull(slotIdx)?.offset ?: 0
                        emitSpRelativeStore(newInsts, inst.width, inst.rs, realOffset)
                        i++
                        continue
                    }
                }

                newInsts.add(inst)
                i++
            }

            block.instructions.clear()
            block.instructions.addAll(newInsts)
        }
    }

    private fun decodeSpillMarker(offset: Int): Int? {
        if (offset >= 0) return null
        val neg = -offset
        if (neg % 256 != 0) return null
        val idx = neg / 256 - 1
        return if (idx >= 0) idx else null
    }

    private fun emitSpRelativeAddi(
        out: MutableList<RvInst>,
        rd: RvOperand,
        offset: Int,
    ) {
        if (fitsIn12Bit(offset)) {
            out.add(RvInst.IType(RvArithImmOp.ADDI, rd, phys(RvPhysReg.SP), offset))
        } else {

            out.add(RvInst.Li(rd, offset))
            out.add(RvInst.RType(RvArithOp.ADD, rd, phys(RvPhysReg.SP), rd))
        }
    }

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

    private fun emitSpRelativeStore(
        out: MutableList<RvInst>,
        width: MemWidth,
        rs: RvOperand,
        offset: Int,
    ) {
        if (fitsIn12Bit(offset)) {
            out.add(RvInst.Store(width, rs, phys(RvPhysReg.SP), offset))
        } else {

            val scratch = pickScratchForStore(rs)
            out.add(RvInst.Li(phys(scratch), offset))
            out.add(RvInst.RType(RvArithOp.ADD, phys(scratch), phys(RvPhysReg.SP), phys(scratch)))
            out.add(RvInst.Store(width, rs, phys(scratch), 0))
        }
    }

    private fun pickScratchForStore(rs: RvOperand): RvPhysReg {
        val avoid = when (rs) {
            is RvOperand.PhysReg -> rs.reg
            else -> null
        }
        return if (avoid == RvPhysReg.T0) RvPhysReg.T1 else RvPhysReg.T0
    }

    private fun insertPrologue(mf: RvMachineFunction, layout: FrameInfo) {
        if (layout.totalFrameSize == 0) return

        val entry = mf.entryBlock()
        val prologue = mutableListOf<RvInst>()

        emitSpAdjust(prologue, -layout.totalFrameSize)

        if (layout.saveRa) {
            val raOffset = layout.savedRegOffsets[null]!!
            emitPrologueSave(prologue, RvPhysReg.RA, raOffset)
        }

        for (reg in layout.calleeSavedRegs) {
            val regOffset = layout.savedRegOffsets[reg]!!
            emitPrologueSave(prologue, reg, regOffset)
        }

        entry.instructions.addAll(0, prologue)
    }

    private fun emitPrologueSave(
        out: MutableList<RvInst>,
        reg: RvPhysReg,
        offset: Int,
    ) {
        if (fitsIn12Bit(offset)) {
            out.add(RvInst.Store(MemWidth.DWORD, phys(reg), phys(RvPhysReg.SP), offset))
        } else {

            val scratch = if (reg == RvPhysReg.T0) RvPhysReg.T1 else RvPhysReg.T0

            out.add(RvInst.Li(phys(scratch), offset))
            out.add(RvInst.RType(RvArithOp.ADD, phys(scratch), phys(RvPhysReg.SP), phys(scratch)))
            out.add(RvInst.Store(MemWidth.DWORD, phys(reg), phys(scratch), 0))
        }
    }

    private fun insertEpilogue(mf: RvMachineFunction, layout: FrameInfo) {
        if (layout.totalFrameSize == 0) return

        for (block in mf.blocks) {
            val newInsts = mutableListOf<RvInst>()
            for (inst in block.instructions) {
                if (inst is RvInst.Ret) {

                    val epilogue = mutableListOf<RvInst>()

                    for (reg in layout.calleeSavedRegs.asReversed()) {
                        val regOffset = layout.savedRegOffsets[reg]!!
                        emitEpilogueRestore(epilogue, reg, regOffset)
                    }

                    if (layout.saveRa) {
                        val raOffset = layout.savedRegOffsets[null]!!
                        emitEpilogueRestore(epilogue, RvPhysReg.RA, raOffset)
                    }

                    emitSpAdjust(epilogue, layout.totalFrameSize)

                    newInsts.addAll(epilogue)
                }
                newInsts.add(inst)
            }
            block.instructions.clear()
            block.instructions.addAll(newInsts)
        }
    }

    private fun emitEpilogueRestore(
        out: MutableList<RvInst>,
        reg: RvPhysReg,
        offset: Int,
    ) {
        if (fitsIn12Bit(offset)) {
            out.add(RvInst.Load(MemWidth.DWORD, phys(reg), phys(RvPhysReg.SP), offset))
        } else {

            out.add(RvInst.Li(phys(reg), offset))
            out.add(RvInst.RType(RvArithOp.ADD, phys(reg), phys(RvPhysReg.SP), phys(reg)))
            out.add(RvInst.Load(MemWidth.DWORD, phys(reg), phys(reg), 0))
        }
    }

    private fun emitSpAdjust(out: MutableList<RvInst>, delta: Int) {
        if (delta == 0) return
        if (fitsIn12Bit(delta)) {
            out.add(RvInst.IType(RvArithImmOp.ADDI, phys(RvPhysReg.SP), phys(RvPhysReg.SP), delta))
        } else {
            out.add(RvInst.Li(phys(RvPhysReg.T0), delta))
            out.add(RvInst.RType(RvArithOp.ADD, phys(RvPhysReg.SP), phys(RvPhysReg.SP), phys(RvPhysReg.T0)))
        }
    }

    internal fun patchIncomingArgs(mf: RvMachineFunction, totalFrameSize: Int) {
        if (totalFrameSize == 0) return
        if (mf.blocks.isEmpty()) return
        val entry = mf.entryBlock()
        val newInsts = mutableListOf<RvInst>()
        val insts = entry.instructions
        var i = 0

        while (i < insts.size) {
            val inst = insts[i]

            if (inst is RvInst.Comment && inst.text.startsWith("overflow_arg ")) {
                val originalOffset = inst.text.removePrefix("overflow_arg ").trim().toIntOrNull()
                if (originalOffset != null && i + 1 < insts.size) {
                    val load = insts[i + 1]
                    if (load is RvInst.Load
                        && load.base == phys(RvPhysReg.SP)
                        && load.offset == originalOffset
                    ) {

                        val adjustedOffset = totalFrameSize + originalOffset

                        if (fitsIn12Bit(adjustedOffset)) {
                            newInsts.add(load.copy(offset = adjustedOffset))
                        } else {

                            newInsts.add(RvInst.Li(load.rd, adjustedOffset))
                            newInsts.add(RvInst.RType(RvArithOp.ADD, load.rd, phys(RvPhysReg.SP), load.rd))
                            newInsts.add(RvInst.Load(load.width, load.rd, load.rd, 0))
                        }
                        i += 2
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
