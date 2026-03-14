package backend.codegen

import backend.codegen.riscv.*
import backend.ir.*

/**
 * Instruction Selector: lowers an [IrFunction] into an [RvMachineFunction] with
 * unlimited virtual registers.
 *
 * Key responsibilities:
 * - Map every IR value to a virtual register (or constant).
 * - Lower each IR instruction per the RV32IM lowering table.
 * - Handle φ nodes by emitting register moves on predecessor edges,
 *   using parallel-copy sequentialization with cycle breaking.
 * - Split critical edges where needed for φ lowering.
 * - Allocate stack slots for IR allocas.
 */
class InstructionSelector(
    private val irModule: IrModule,
) {

    /** Select instructions for every declared function in the module. */
    fun selectAll(): List<RvMachineFunction> =
        irModule.declaredFunctions()
            .filter { it.blocks.isNotEmpty() }
            .map { select(it) }

    /** Select instructions for a single IR function. */
    fun select(irFunc: IrFunction): RvMachineFunction {
        val ctx = FunctionSelectionContext(irFunc)
        ctx.run()
        return ctx.mf
    }

    // ===================================================================
    //  Per-function selection context
    // ===================================================================

    private inner class FunctionSelectionContext(
        val irFunc: IrFunction,
    ) {
        val mf = RvMachineFunction(irFunc.name)

        /** IR value name → virtual register that holds its value. */
        val valueMap = mutableMapOf<String, RvOperand.Reg>()

        /** IR alloca name → stack slot index in [mf.stackSlots]. */
        val slotMap = mutableMapOf<String, Int>()

        /** IR block label → machine block. */
        val blockMap = mutableMapOf<String, RvMachineBlock>()

        /**
         * Pending φ-move sets, keyed by (predecessor label → successor label).
         * Each entry is a list of (dst vreg, src IR value) pairs representing
         * the parallel copy that must be emitted on that edge.
         */
        val phiEdgeCopies = mutableMapOf<EdgeKey, MutableList<PhiCopy>>()

        /** Labels of blocks that contain φ nodes (used for critical-edge detection). */
        val blocksWithPhis = mutableSetOf<String>()

        fun run() {
            // Phase 0: Pre-create all machine blocks so forward references work.
            for (irBlock in irFunc.blocks) {
                val label = machineLabel(irBlock.label)
                val mb = mf.createBlock(label)
                blockMap[irBlock.label] = mb
            }

            // Phase 1: Scan φ nodes to build edge copy sets and mark φ-bearing blocks.
            collectPhiCopies()

            // Phase 2: Lower parameters.
            lowerParameters()

            // Phase 3: Lower each block's instructions and terminator.
            for (irBlock in irFunc.blocks) {
                lowerBlock(irBlock)
            }

            // Phase 4: Emit φ moves on predecessor edges (possibly splitting critical edges).
            emitPhiMoves()

            // Phase 5: Rebuild CFG edges from the final instruction stream.
            mf.rebuildCfgEdges()
        }

        // ---------------------------------------------------------------
        //  Machine label naming: <funcName>.<blockLabel>
        //  except the entry block which uses the function name directly.
        // ---------------------------------------------------------------

        private fun machineLabel(irLabel: String): String {
            // The first block's label becomes the function name itself.
            if (irFunc.blocks.isNotEmpty() && irFunc.blocks[0].label == irLabel) {
                return irFunc.name
            }
            return "${irFunc.name}.$irLabel"
        }

        // ---------------------------------------------------------------
        //  Width helper: IR type → byte width for register / mem ops
        // ---------------------------------------------------------------

        private fun widthOf(type: IrType): Int = when (type) {
            is IrPrimitive -> when (type.kind) {
                PrimitiveKind.BOOL -> 1
                PrimitiveKind.CHAR -> 1
                PrimitiveKind.I32, PrimitiveKind.U32,
                PrimitiveKind.ISIZE, PrimitiveKind.USIZE -> 4
                PrimitiveKind.UNIT, PrimitiveKind.NEVER -> 4
            }
            is IrPointer -> 4
            else -> 4
        }

        private fun memWidthOf(type: IrType): MemWidth = when (widthOf(type)) {
            1 -> MemWidth.BYTE
            2 -> MemWidth.HALF
            else -> MemWidth.WORD
        }

        // ---------------------------------------------------------------
        //  Value mapping: IR value → RvOperand
        // ---------------------------------------------------------------

        /**
         * Get or create the virtual register for a named IR value (IrLocal produced
         * by an instruction). If it doesn't exist yet, allocate a fresh vreg.
         */
        private fun vregFor(name: String, type: IrType): RvOperand.Reg {
            return valueMap.getOrPut(name) { mf.newVreg(widthOf(type)) }
        }

        /**
         * Materialise an [IrValue] into an [RvOperand.Reg], emitting instructions
         * into [block] as needed (e.g. `li` for constants, `la` for globals).
         */
        private fun operandOf(value: IrValue, block: RvMachineBlock): RvOperand.Reg = when (value) {
            is IrLocal -> vregFor(value.name, value.type)

            is IrParameter -> {
                // Parameters should already be mapped during lowerParameters.
                val paramKey = value.name.ifEmpty { "arg${value.index}" }
                valueMap[paramKey]
                    ?: error("ISel: unmapped parameter '${paramKey}'")
            }

            is IrConstant -> {
                val rd = mf.newVreg(widthOf(value.type))
                block.append(RvInst.Li(rd, value.value.toInt()))
                rd
            }

            is IrUndef -> {
                // Undef → just give it a fresh vreg; its value is don't-care.
                val rd = mf.newVreg(widthOf(value.type))
                block.append(RvInst.Li(rd, 0))
                rd
            }

            is IrGlobalRef -> {
                val rd = mf.newVreg(4)
                block.append(RvInst.La(rd, value.name))
                rd
            }

            is IrFunctionRef -> {
                val rd = mf.newVreg(4)
                block.append(RvInst.La(rd, value.name))
                rd
            }

            else -> error("ISel: unsupported IrValue type: ${value::class.simpleName}")
        }

        // ---------------------------------------------------------------
        //  Stack slot address materialisation
        // ---------------------------------------------------------------

        /**
         * Emit instructions to compute the address of a stack slot into a vreg.
         * During isel the offset is 0 (placeholder); frame finalization resolves it.
         * We emit `addi rd, sp, 0` — the `0` will be patched during frame layout.
         *
         * We use a **slot-reference instruction** pattern: we store the slot index
         * in a side table and the frame finalizer rewrites the immediate.
         */
        private fun slotAddress(slotIndex: Int, block: RvMachineBlock): RvOperand.Reg {
            val rd = mf.newVreg(4)
            // Emit addi rd, sp, 0 as a placeholder. Frame layout will patch the immediate
            // to the actual sp-relative offset. We record slot index via a comment.
            block.append(RvInst.Comment("slot $slotIndex"))
            block.append(RvInst.IType(RvArithImmOp.ADDI, rd, phys(RvPhysReg.SP), 0))
            return rd
        }

        // ---------------------------------------------------------------
        //  Phase 1: Collect φ copies
        // ---------------------------------------------------------------

        private fun collectPhiCopies() {
            for (irBlock in irFunc.blocks) {
                val phis = irBlock.instructions.filterIsInstance<IrPhi>()
                if (phis.isEmpty()) continue

                blocksWithPhis.add(irBlock.label)

                for (phi in phis) {
                    // Defensive: φ must not carry aggregates.
                    require(!isAggregate(phi.type)) {
                        "ISel: φ node '${phi.name}' has aggregate type ${phi.type.render()}. " +
                            "Aggregates must use destination-passing, not φ nodes."
                    }

                    // Allocate the destination vreg for this φ.
                    val dstReg = vregFor(phi.name, phi.type)

                    for (incoming in phi.incoming) {
                        val key = EdgeKey(incoming.predecessor, irBlock.label)
                        phiEdgeCopies.getOrPut(key) { mutableListOf() }
                            .add(PhiCopy(dstReg, incoming.value))
                    }
                }
            }
        }

        // ---------------------------------------------------------------
        //  Phase 2: Lower parameters
        // ---------------------------------------------------------------

        private fun lowerParameters() {
            val sig = irFunc.signature
            val entryBlock = blockMap[irFunc.blocks[0].label]!!
            val argRegs = ARG_REGS

            for (i in sig.parameters.indices) {
                val paramName = irFunc.parameterNames.getOrElse(i) { "arg$i" }
                val paramType = sig.parameters[i]
                val dstReg = mf.newVreg(widthOf(paramType))
                valueMap[paramName] = dstReg

                if (i < argRegs.size) {
                    // Argument comes in a physical register: emit mv vreg, aX.
                    entryBlock.append(RvInst.Mv(dstReg, phys(argRegs[i])))
                } else {
                    // Argument is on the stack (overflow area). The frame layout will
                    // place these at known offsets above the caller's sp. For now emit
                    // a load from a placeholder offset. Frame finalization patches it.
                    val overflowOffset = (i - argRegs.size) * 4
                    entryBlock.append(
                        RvInst.Load(MemWidth.WORD, dstReg, phys(RvPhysReg.SP), overflowOffset)
                    )
                    // Track max outgoing arg area needed by the *callee* perspective.
                }
            }
        }

        // ---------------------------------------------------------------
        //  Phase 3: Lower a basic block
        // ---------------------------------------------------------------

        private fun lowerBlock(irBlock: IrBasicBlock) {
            val mb = blockMap[irBlock.label]!!

            for (inst in irBlock.instructions) {
                // Skip φ nodes — handled separately.
                if (inst is IrPhi) continue
                lowerInstruction(inst, mb)
            }

            irBlock.terminator?.let { lowerTerminator(it, mb) }
        }

        // ---------------------------------------------------------------
        //  Instruction lowering
        // ---------------------------------------------------------------

        private fun lowerInstruction(inst: IrInstruction, mb: RvMachineBlock) {
            when (inst) {
                is IrAlloca -> lowerAlloca(inst, mb)
                is IrLoad -> lowerLoad(inst, mb)
                is IrStore -> lowerStore(inst, mb)
                is IrBinary -> lowerBinary(inst, mb)
                is IrUnary -> lowerUnary(inst, mb)
                is IrCmp -> lowerCmp(inst, mb)
                is IrCall -> lowerCall(inst, mb)
                is IrGep -> lowerGep(inst, mb)
                is IrCast -> lowerCast(inst, mb)
                is IrConst -> lowerConst(inst, mb)
                is IrPhi -> { /* already handled */ }
                is IrTerminator -> { /* handled in lowerTerminator */ }
            }
        }

        // -- IrAlloca --------------------------------------------------

        private fun lowerAlloca(inst: IrAlloca, mb: RvMachineBlock) {
            val allocType = inst.allocatedType
            val (size, align) = typeLayout(allocType)
            val slotIdx = mf.allocateStackSlot(inst.name, size, align)
            slotMap[inst.name] = slotIdx

            // The alloca produces a pointer → materialise the address.
            val addrReg = slotAddress(slotIdx, mb)
            valueMap[inst.name] = addrReg
        }

        // -- IrLoad ----------------------------------------------------

        private fun lowerLoad(inst: IrLoad, mb: RvMachineBlock) {
            val addrReg = operandOf(inst.address, mb)
            val rd = vregFor(inst.name, inst.type)
            val mw = memWidthOf(inst.type)
            mb.append(RvInst.Load(mw, rd, addrReg, 0))

            // For i1 loads, mask to 1 bit.
            if (inst.type is IrPrimitive && inst.type.kind == PrimitiveKind.BOOL) {
                mb.append(RvInst.IType(RvArithImmOp.ANDI, rd, rd, 1))
            }
        }

        // -- IrStore ---------------------------------------------------

        private fun lowerStore(inst: IrStore, mb: RvMachineBlock) {
            val valueType = inst.value.type
            var valReg = operandOf(inst.value, mb)
            val addrReg = operandOf(inst.address, mb)
            val mw = memWidthOf(valueType)

            // For i1 stores, mask before storing.
            if (valueType is IrPrimitive && valueType.kind == PrimitiveKind.BOOL) {
                val masked = mf.newVreg(1)
                mb.append(RvInst.IType(RvArithImmOp.ANDI, masked, valReg, 1))
                valReg = masked
            }

            mb.append(RvInst.Store(mw, valReg, addrReg, 0))
        }

        // -- IrBinary --------------------------------------------------

        private fun lowerBinary(inst: IrBinary, mb: RvMachineBlock) {
            val rd = vregFor(inst.name, inst.type)

            // Try to use I-type (immediate) form when RHS is a small constant.
            if (inst.rhs is IrConstant) {
                val immVal = inst.rhs.value.toInt()
                val immOp = binaryToImm(inst.operator, immVal)
                if (immOp != null) {
                    val lhs = operandOf(inst.lhs, mb)
                    mb.append(RvInst.IType(immOp, rd, lhs, immVal))
                    return
                }
            }
            // Similarly for LHS constant on commutative ops.
            if (inst.lhs is IrConstant && isCommutative(inst.operator)) {
                val immVal = inst.lhs.value.toInt()
                val immOp = binaryToImm(inst.operator, immVal)
                if (immOp != null) {
                    val rhs = operandOf(inst.rhs, mb)
                    mb.append(RvInst.IType(immOp, rd, rhs, immVal))
                    return
                }
            }

            val lhs = operandOf(inst.lhs, mb)
            val rhs = operandOf(inst.rhs, mb)
            val rvOp = binaryToRType(inst.operator)
            mb.append(RvInst.RType(rvOp, rd, lhs, rhs))
        }

        private fun binaryToRType(op: BinaryOperator): RvArithOp = when (op) {
            BinaryOperator.ADD -> RvArithOp.ADD
            BinaryOperator.SUB -> RvArithOp.SUB
            BinaryOperator.MUL -> RvArithOp.MUL
            BinaryOperator.SDIV -> RvArithOp.DIV
            BinaryOperator.UDIV -> RvArithOp.DIVU
            BinaryOperator.SREM -> RvArithOp.REM
            BinaryOperator.UREM -> RvArithOp.REMU
            BinaryOperator.AND -> RvArithOp.AND
            BinaryOperator.OR -> RvArithOp.OR
            BinaryOperator.XOR -> RvArithOp.XOR
            BinaryOperator.SHL -> RvArithOp.SLL
            BinaryOperator.ASHR -> RvArithOp.SRA
            BinaryOperator.LSHR -> RvArithOp.SRL
        }

        private fun binaryToImm(op: BinaryOperator, immVal: Int): RvArithImmOp? {
            if (!fitsIn12Bit(immVal)) return null
            return when (op) {
                BinaryOperator.ADD -> RvArithImmOp.ADDI
                BinaryOperator.AND -> RvArithImmOp.ANDI
                BinaryOperator.OR -> RvArithImmOp.ORI
                BinaryOperator.XOR -> RvArithImmOp.XORI
                BinaryOperator.SHL -> RvArithImmOp.SLLI
                BinaryOperator.ASHR -> RvArithImmOp.SRAI
                BinaryOperator.LSHR -> RvArithImmOp.SRLI
                else -> null
            }
        }

        private fun isCommutative(op: BinaryOperator): Boolean = when (op) {
            BinaryOperator.ADD, BinaryOperator.MUL,
            BinaryOperator.AND, BinaryOperator.OR, BinaryOperator.XOR -> true
            else -> false
        }

        // -- IrUnary ---------------------------------------------------

        private fun lowerUnary(inst: IrUnary, mb: RvMachineBlock) {
            val rd = vregFor(inst.name, inst.type)
            val rs = operandOf(inst.operand, mb)

            when (inst.operator) {
                UnaryOperator.NEG -> {
                    mb.append(RvInst.Neg(rd, rs))
                }
                UnaryOperator.NOT -> {
                    val isBool = inst.type is IrPrimitive && inst.type.kind == PrimitiveKind.BOOL
                    if (isBool) {
                        // Logical NOT for i1: xori rd, rs, 1
                        mb.append(RvInst.IType(RvArithImmOp.XORI, rd, rs, 1))
                    } else {
                        // Bitwise NOT: not rd, rs  (xori rd, rs, -1)
                        mb.append(RvInst.Not(rd, rs))
                    }
                }
            }
        }

        // -- IrCmp -----------------------------------------------------

        private fun lowerCmp(inst: IrCmp, mb: RvMachineBlock) {
            val rd = vregFor(inst.name, inst.type)
            val lhs = operandOf(inst.lhs, mb)
            val rhs = operandOf(inst.rhs, mb)

            when (inst.predicate) {
                ComparePredicate.EQ -> {
                    // sub t, lhs, rhs; seqz rd, t
                    val t = mf.newVreg()
                    mb.append(RvInst.RType(RvArithOp.SUB, t, lhs, rhs))
                    mb.append(RvInst.Seqz(rd, t))
                }
                ComparePredicate.NE -> {
                    val t = mf.newVreg()
                    mb.append(RvInst.RType(RvArithOp.SUB, t, lhs, rhs))
                    mb.append(RvInst.Snez(rd, t))
                }
                ComparePredicate.SLT -> {
                    mb.append(RvInst.RType(RvArithOp.SLT, rd, lhs, rhs))
                }
                ComparePredicate.SLE -> {
                    // !(rhs < lhs) → slt rd, rhs, lhs; xori rd, rd, 1
                    mb.append(RvInst.RType(RvArithOp.SLT, rd, rhs, lhs))
                    mb.append(RvInst.IType(RvArithImmOp.XORI, rd, rd, 1))
                }
                ComparePredicate.SGT -> {
                    mb.append(RvInst.RType(RvArithOp.SLT, rd, rhs, lhs))
                }
                ComparePredicate.SGE -> {
                    mb.append(RvInst.RType(RvArithOp.SLT, rd, lhs, rhs))
                    mb.append(RvInst.IType(RvArithImmOp.XORI, rd, rd, 1))
                }
                ComparePredicate.ULT -> {
                    mb.append(RvInst.RType(RvArithOp.SLTU, rd, lhs, rhs))
                }
                ComparePredicate.ULE -> {
                    mb.append(RvInst.RType(RvArithOp.SLTU, rd, rhs, lhs))
                    mb.append(RvInst.IType(RvArithImmOp.XORI, rd, rd, 1))
                }
                ComparePredicate.UGT -> {
                    mb.append(RvInst.RType(RvArithOp.SLTU, rd, rhs, lhs))
                }
                ComparePredicate.UGE -> {
                    mb.append(RvInst.RType(RvArithOp.SLTU, rd, lhs, rhs))
                    mb.append(RvInst.IType(RvArithImmOp.XORI, rd, rd, 1))
                }
            }
        }

        // -- IrCall ----------------------------------------------------

        private fun lowerCall(inst: IrCall, mb: RvMachineBlock) {
            mf.hasCalls = true
            val calleeName = inst.callee.name
            val argPhysRegs = mutableListOf<RvPhysReg>()

            // Check for memcpy lowering.
            val isMemcpy = calleeName == "llvm.memcpy.p0.p0.i32"

            val actualCallee = if (isMemcpy) "memcpy" else calleeName

            // Filter out the volatile flag (4th arg) for memcpy.
            val args = if (isMemcpy && inst.arguments.size == 4) {
                inst.arguments.take(3)
            } else {
                inst.arguments
            }

            // Move arguments into a0–a7 or onto the stack.
            for ((idx, arg) in args.withIndex()) {
                val argReg = operandOf(arg, mb)
                if (idx < ARG_REGS.size) {
                    mb.append(RvInst.Mv(phys(ARG_REGS[idx]), argReg))
                    argPhysRegs.add(ARG_REGS[idx])
                } else {
                    // Overflow argument: store to stack.
                    val overflowOffset = (idx - ARG_REGS.size) * 4
                    mb.append(RvInst.Store(MemWidth.WORD, argReg, phys(RvPhysReg.SP), overflowOffset))
                    // Track outgoing arg area.
                    val needed = (idx - ARG_REGS.size + 1) * 4
                    if (needed > mf.outgoingArgAreaSize) {
                        mf.outgoingArgAreaSize = needed
                    }
                }
            }

            // Emit the call.
            val returnsVoid = inst.type is IrPrimitive &&
                (inst.type as IrPrimitive).kind == PrimitiveKind.UNIT
            val resultPhysRegs = if (returnsVoid) emptyList() else listOf(RvPhysReg.A0)

            mb.append(RvInst.Call(actualCallee, argPhysRegs, resultPhysRegs))

            // If non-void, move a0 to the destination vreg.
            if (!returnsVoid && inst.name.isNotBlank()) {
                val rd = vregFor(inst.name, inst.type)
                mb.append(RvInst.Mv(rd, phys(RvPhysReg.A0)))
            }
        }

        // -- IrGep -----------------------------------------------------

        private fun lowerGep(inst: IrGep, mb: RvMachineBlock) {
            val rd = vregFor(inst.name, inst.type)
            val baseReg = operandOf(inst.base, mb)

            // The IR GEP has indices: typically [0, fieldIdx] for structs
            // or [0, elemIdx] for arrays.
            val baseType = inst.base.type
            val pointee = (baseType as? IrPointer)?.pointee ?: baseType

            val offset = computeGepOffset(pointee, inst.indices, mb)
            if (offset is GepResult.Constant) {
                if (offset.value == 0) {
                    mb.append(RvInst.Mv(rd, baseReg))
                } else if (fitsIn12Bit(offset.value)) {
                    mb.append(RvInst.IType(RvArithImmOp.ADDI, rd, baseReg, offset.value))
                } else {
                    val t = mf.newVreg()
                    mb.append(RvInst.Li(t, offset.value))
                    mb.append(RvInst.RType(RvArithOp.ADD, rd, baseReg, t))
                }
            } else if (offset is GepResult.Register) {
                mb.append(RvInst.RType(RvArithOp.ADD, rd, baseReg, offset.reg))
            }
        }



        private fun computeGepOffset(
            basePointee: IrType,
            indices: List<IrValue>,
            mb: RvMachineBlock,
        ): GepResult {
            if (indices.isEmpty()) return GepResult.Constant(0)

            // First index: scales by sizeof(basePointee).
            var currentType = basePointee
            var accumulatedConst = 0
            var dynamicReg: RvOperand.Reg? = null

            fun addConstant(c: Int) {
                if (dynamicReg == null) {
                    accumulatedConst += c
                } else {
                    if (c != 0) {
                        val t = mf.newVreg()
                        if (fitsIn12Bit(c)) {
                            mb.append(RvInst.IType(RvArithImmOp.ADDI, t, dynamicReg!!, c))
                        } else {
                            val ci = mf.newVreg()
                            mb.append(RvInst.Li(ci, c))
                            mb.append(RvInst.RType(RvArithOp.ADD, t, dynamicReg!!, ci))
                        }
                        dynamicReg = t
                    }
                }
            }

            fun addDynamic(reg: RvOperand.Reg) {
                if (dynamicReg == null) {
                    if (accumulatedConst == 0) {
                        dynamicReg = reg
                    } else {
                        val t = mf.newVreg()
                        if (fitsIn12Bit(accumulatedConst)) {
                            mb.append(RvInst.IType(RvArithImmOp.ADDI, t, reg, accumulatedConst))
                        } else {
                            val ci = mf.newVreg()
                            mb.append(RvInst.Li(ci, accumulatedConst))
                            mb.append(RvInst.RType(RvArithOp.ADD, t, reg, ci))
                        }
                        dynamicReg = t
                        accumulatedConst = 0
                    }
                } else {
                    val t = mf.newVreg()
                    mb.append(RvInst.RType(RvArithOp.ADD, t, dynamicReg!!, reg))
                    dynamicReg = t
                }
            }

            for ((i, idx) in indices.withIndex()) {
                if (i == 0) {
                    // First index: offset = idx * sizeof(currentType)
                    val elemSize = typeSize(currentType)
                    if (idx is IrConstant) {
                        addConstant(idx.value.toInt() * elemSize)
                    } else {
                        val idxReg = operandOf(idx, mb)
                        if (elemSize == 1) {
                            addDynamic(idxReg)
                        } else {
                            val sizeReg = mf.newVreg()
                            mb.append(RvInst.Li(sizeReg, elemSize))
                            val product = mf.newVreg()
                            mb.append(RvInst.RType(RvArithOp.MUL, product, idxReg, sizeReg))
                            addDynamic(product)
                        }
                    }
                } else {
                    // Subsequent indices: depends on current type.
                    when (currentType) {
                        is IrStruct -> {
                            // Index must be constant (field index).
                            val fieldIdx = (idx as IrConstant).value.toInt()
                            var fieldOffset = 0
                            for (fi in 0 until fieldIdx) {
                                val (fSize, fAlign) = typeLayout(currentType.fields[fi])
                                fieldOffset = alignUp(fieldOffset, fAlign) + fSize
                            }
                            val (_, fAlign) = typeLayout(currentType.fields[fieldIdx])
                            fieldOffset = alignUp(fieldOffset, fAlign)
                            addConstant(fieldOffset)
                            currentType = currentType.fields[fieldIdx]
                        }
                        is IrArray -> {
                            val elemSize = typeSize(currentType.element)
                            if (idx is IrConstant) {
                                addConstant(idx.value.toInt() * elemSize)
                            } else {
                                val idxReg = operandOf(idx, mb)
                                if (elemSize == 1) {
                                    addDynamic(idxReg)
                                } else {
                                    val sizeReg = mf.newVreg()
                                    mb.append(RvInst.Li(sizeReg, elemSize))
                                    val product = mf.newVreg()
                                    mb.append(RvInst.RType(RvArithOp.MUL, product, idxReg, sizeReg))
                                    addDynamic(product)
                                }
                            }
                            currentType = currentType.element
                        }
                        else -> {
                            // Scalar pointer dereference — treat like array element access.
                            val elemSize = typeSize(currentType)
                            if (idx is IrConstant) {
                                addConstant(idx.value.toInt() * elemSize)
                            } else {
                                val idxReg = operandOf(idx, mb)
                                if (elemSize == 1) {
                                    addDynamic(idxReg)
                                } else {
                                    val sizeReg = mf.newVreg()
                                    mb.append(RvInst.Li(sizeReg, elemSize))
                                    val product = mf.newVreg()
                                    mb.append(RvInst.RType(RvArithOp.MUL, product, idxReg, sizeReg))
                                    addDynamic(product)
                                }
                            }
                        }
                    }
                }
            }

            return if (dynamicReg != null) GepResult.Register(dynamicReg!!)
            else GepResult.Constant(accumulatedConst)
        }

        // -- IrCast ----------------------------------------------------

        private fun lowerCast(inst: IrCast, mb: RvMachineBlock) {
            val rd = vregFor(inst.name, inst.type)
            val rs = operandOf(inst.value, mb)
            val srcType = inst.value.type
            val dstType = inst.type

            when (inst.kind) {
                CastKind.BITCAST, CastKind.PTRTOINT, CastKind.INTTOPTR -> {
                    mb.append(RvInst.Mv(rd, rs))
                }

                CastKind.ZEXT -> {
                    val srcWidth = widthOf(srcType)
                    if (srcWidth == 1) {
                        // i1 → i32: andi rd, rs, 1
                        mb.append(RvInst.IType(RvArithImmOp.ANDI, rd, rs, 1))
                    } else if (srcWidth == 1 || (srcType is IrPrimitive && srcType.kind == PrimitiveKind.CHAR)) {
                        // i8 → i32: andi rd, rs, 0xFF
                        mb.append(RvInst.IType(RvArithImmOp.ANDI, rd, rs, 0xFF))
                    } else {
                        mb.append(RvInst.Mv(rd, rs))
                    }
                }

                CastKind.SEXT -> {
                    val srcWidth = widthOf(srcType)
                    if (srcWidth == 1 || (srcType is IrPrimitive && srcType.kind == PrimitiveKind.BOOL)) {
                        // i1 → i32: slli rd, rs, 31; srai rd, rd, 31
                        mb.append(RvInst.IType(RvArithImmOp.SLLI, rd, rs, 31))
                        mb.append(RvInst.IType(RvArithImmOp.SRAI, rd, rd, 31))
                    } else if (srcType is IrPrimitive && srcType.kind == PrimitiveKind.CHAR) {
                        // i8 → i32: slli rd, rs, 24; srai rd, rd, 24
                        mb.append(RvInst.IType(RvArithImmOp.SLLI, rd, rs, 24))
                        mb.append(RvInst.IType(RvArithImmOp.SRAI, rd, rd, 24))
                    } else {
                        mb.append(RvInst.Mv(rd, rs))
                    }
                }

                CastKind.TRUNC -> {
                    val dstWidth = widthOf(dstType)
                    if (dstWidth == 1 || (dstType is IrPrimitive && dstType.kind == PrimitiveKind.BOOL)) {
                        mb.append(RvInst.IType(RvArithImmOp.ANDI, rd, rs, 1))
                    } else if (dstType is IrPrimitive && dstType.kind == PrimitiveKind.CHAR) {
                        mb.append(RvInst.IType(RvArithImmOp.ANDI, rd, rs, 0xFF))
                    } else {
                        mb.append(RvInst.Mv(rd, rs))
                    }
                }
            }
        }

        // -- IrConst ---------------------------------------------------

        private fun lowerConst(inst: IrConst, mb: RvMachineBlock) {
            val rd = vregFor(inst.name, inst.type)
            mb.append(RvInst.Li(rd, inst.constant.value.toInt()))
        }

        // ---------------------------------------------------------------
        //  Terminator lowering
        // ---------------------------------------------------------------

        private fun lowerTerminator(term: IrTerminator, mb: RvMachineBlock) {
            when (term) {
                is IrReturn -> lowerReturn(term, mb)
                is IrBranch -> lowerBranch(term, mb)
                is IrJump -> lowerJump(term, mb)
            }
        }

        private fun lowerReturn(ret: IrReturn, mb: RvMachineBlock) {
            if (ret.value != null) {
                val valType = ret.value.type
                val isVoid = valType is IrPrimitive &&
                    (valType.kind == PrimitiveKind.UNIT || valType.kind == PrimitiveKind.NEVER)
                if (!isVoid) {
                    val rs = operandOf(ret.value, mb)
                    mb.append(RvInst.Mv(phys(RvPhysReg.A0), rs))
                    mb.append(RvInst.Ret(listOf(RvPhysReg.A0)))
                    return
                }
            }
            mb.append(RvInst.Ret())
        }

        private fun lowerBranch(br: IrBranch, mb: RvMachineBlock) {
            val condReg = operandOf(br.condition, mb)
            val trueLabel = machineLabel(br.trueTarget)
            val falseLabel = machineLabel(br.falseTarget)

            mb.append(RvInst.Branch(RvBranchCond.BNE, condReg, phys(RvPhysReg.ZERO), trueLabel))
            mb.append(RvInst.J(falseLabel))
        }

        private fun lowerJump(jmp: IrJump, mb: RvMachineBlock) {
            val target = machineLabel(jmp.target)
            mb.append(RvInst.J(target))
        }

        // ---------------------------------------------------------------
        //  Phase 4: Emit φ moves with parallel-copy sequentialization
        // ---------------------------------------------------------------

        private fun emitPhiMoves() {
            // Process each edge that has pending φ copies.
            for ((edge, copies) in phiEdgeCopies) {
                val predLabel = edge.predecessor
                val succLabel = edge.successor
                val predBlock = blockMap[predLabel]
                    ?: error("ISel: φ edge predecessor '$predLabel' not found")

                // Check if this is a critical edge:
                // Predecessor has multiple successors AND successor has φ nodes (multiple preds).
                val predTerminator = irFunc.blocks.find { it.label == predLabel }?.terminator
                val isCritical = predTerminator is IrBranch &&
                    predTerminator.trueTarget != predTerminator.falseTarget &&
                    blocksWithPhis.contains(succLabel)

                val targetBlock: RvMachineBlock
                if (isCritical) {
                    // Split the critical edge: create a trampoline block.
                    targetBlock = splitCriticalEdge(predLabel, succLabel)
                } else {
                    targetBlock = predBlock
                }

                // Resolve the parallel copy into an ordered sequence of moves.
                val resolvedCopies = resolveParallelCopies(copies, targetBlock)

                // Emit the resolved moves.
                for (move in resolvedCopies) {
                    if (isCritical) {
                        // Insert at end (before the jump we added during splitting).
                        targetBlock.insertBeforeTerminator(move)
                    } else {
                        targetBlock.insertBeforeTerminator(move)
                    }
                }
            }
        }

        /**
         * Split a critical edge from [predLabel] to [succLabel] by inserting a
         * trampoline block. Returns the new trampoline block.
         */
        private fun splitCriticalEdge(predLabel: String, succLabel: String): RvMachineBlock {
            val predMb = blockMap[predLabel]!!
            val succMachineLabel = machineLabel(succLabel)
            val trampolineLabel = "${machineLabel(predLabel)}.to.${succLabel}"

            val trampoline = mf.createBlock(trampolineLabel)
            trampoline.append(RvInst.J(succMachineLabel))

            // Rewrite the predecessor's branch target from succLabel to trampoline.
            val insts = predMb.instructions
            for (i in insts.indices) {
                val inst = insts[i]
                when (inst) {
                    is RvInst.Branch -> {
                        if (inst.target == succMachineLabel) {
                            insts[i] = inst.copy(target = trampolineLabel)
                        }
                    }
                    is RvInst.J -> {
                        if (inst.target == succMachineLabel) {
                            insts[i] = inst.copy(target = trampolineLabel)
                        }
                    }
                    else -> { /* skip */ }
                }
            }

            // Register the trampoline in the block map so it can be found.
            blockMap["$predLabel.to.$succLabel"] = trampoline
            return trampoline
        }

        /**
         * Resolve a parallel-copy set into a sequentially-correct list of
         * [RvInst] (mostly [RvInst.Mv] and [RvInst.Li]).
         *
         * Handles:
         * - Self-copies (a ← a): elided.
         * - Constants (dst ← constant): emitted as `li` directly (no conflict).
         * - Acyclic chains: topologically ordered.
         * - Cycles: broken with a fresh temporary virtual register.
         */
        private fun resolveParallelCopies(
            copies: List<PhiCopy>,
            emitBlock: RvMachineBlock,
        ): List<RvInst> {
            val result = mutableListOf<RvInst>()

            // Separate constant sources (always safe) from register-to-register copies.
            val constCopies = mutableListOf<PhiCopy>()
            val regCopies = mutableListOf<Pair<RvOperand.Reg, RvOperand.Reg>>() // dst, src

            for (copy in copies) {
                val dst = copy.dst
                val srcVal = copy.srcValue

                when (srcVal) {
                    is IrConstant -> {
                        constCopies.add(copy)
                    }
                    is IrUndef -> {
                        // Don't-care value: emit li 0.
                        result.add(RvInst.Li(dst, 0))
                    }
                    else -> {
                        val srcReg = operandOf(srcVal, emitBlock)
                        // Self-copy: elide.
                        if (srcReg == dst) continue
                        regCopies.add(dst to srcReg)
                    }
                }
            }

            // Emit constant copies — these never conflict with anything.
            for (copy in constCopies) {
                result.add(RvInst.Li(copy.dst, (copy.srcValue as IrConstant).value.toInt()))
            }

            // Sequentialize register-to-register copies.
            //
            // We use the standard algorithm:
            // 1. Build a map: dst → src for the remaining copies.
            // 2. A copy dst ← src is "ready" if dst is not used as a source by any
            //    other pending copy.
            // 3. Emit ready copies, removing them and potentially freeing up new ones.
            // 4. If no copy is ready, we have a cycle — break it with a temp.

            val pending = regCopies.toMutableList()

            while (pending.isNotEmpty()) {
                // Find a copy whose destination is not a source of any other pending copy.
                val readyIdx = pending.indexOfFirst { (dst, _) ->
                    pending.none { (_, src) -> src == dst }
                }

                if (readyIdx >= 0) {
                    val (dst, src) = pending.removeAt(readyIdx)
                    result.add(RvInst.Mv(dst, src))
                } else {
                    // All remaining copies form one or more cycles. Break one cycle.
                    val (dst, src) = pending.removeAt(0)
                    val tmp = mf.newVreg(dst.width)
                    result.add(RvInst.Mv(tmp, dst))
                    result.add(RvInst.Mv(dst, src))
                    // Rewrite all remaining copies that used `dst` as source to use `tmp`.
                    for (i in pending.indices) {
                        if (pending[i].second == dst) {
                            pending[i] = pending[i].first to tmp
                        }
                    }
                }
            }

            return result
        }
    }

    // ===================================================================
    //  Helper data classes
    // ===================================================================

    private sealed class GepResult {
        data class Constant(val value: Int) : GepResult()
        data class Register(val reg: RvOperand.Reg) : GepResult()
    }

    private data class EdgeKey(
        val predecessor: String,
        val successor: String,
    )

    private data class PhiCopy(
        val dst: RvOperand.Reg,
        val srcValue: IrValue,
    )
}
