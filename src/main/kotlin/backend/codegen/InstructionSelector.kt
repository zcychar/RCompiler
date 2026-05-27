package backend.codegen

// Lowers IR functions into RISC-V machine functions with virtual registers.

import backend.TargetLayout
import backend.codegen.riscv.*
import backend.ir.*

class InstructionSelector(
    private val irModule: IrModule,
) {

    fun selectAll(): List<RvMachineFunction> =
        irModule.declaredFunctions()
            .filter { it.blocks.isNotEmpty() }
            .map { select(it) }

    fun select(irFunc: IrFunction): RvMachineFunction {
        val ctx = FunctionSelectionContext(irFunc)
        ctx.run()
        return ctx.mf
    }

    private inner class FunctionSelectionContext(
        val irFunc: IrFunction,
    ) {
        val mf = RvMachineFunction(irFunc.name)

        val valueMap = mutableMapOf<String, RvOperand.Reg>()

        val slotMap = mutableMapOf<String, Int>()

        val blockMap = mutableMapOf<String, RvMachineBlock>()

        val phiEdgeCopies = mutableMapOf<EdgeKey, MutableList<PhiCopy>>()

        val blocksWithPhis = mutableSetOf<String>()

        private val irDefinitions = collectIrDefinitions()
        private val irUseCounts = collectIrUseCounts()

        fun run() {

            for (irBlock in irFunc.blocks) {
                val label = machineLabel(irBlock.label)
                val mb = mf.createBlock(label)
                blockMap[irBlock.label] = mb
            }

            collectPhiCopies()

            lowerParameters()

            for (irBlock in irFunc.blocks) {
                lowerBlock(irBlock)
            }

            emitPhiMoves()

            mf.rebuildCfgEdges()
        }

        private fun machineLabel(irLabel: String): String {

            if (irFunc.blocks.isNotEmpty() && irFunc.blocks[0].label == irLabel) {
                return irFunc.name
            }
            return "${irFunc.name}.$irLabel"
        }

        private fun widthOf(type: IrType): Int = when (type) {
            is IrPrimitive -> when (type.kind) {
                PrimitiveKind.BOOL -> 1
                PrimitiveKind.CHAR -> 1
                PrimitiveKind.I32, PrimitiveKind.U32,
                PrimitiveKind.ISIZE, PrimitiveKind.USIZE -> TargetLayout.INT_BYTES
                PrimitiveKind.UNIT, PrimitiveKind.NEVER -> 4
            }
            is IrPointer -> TargetLayout.POINTER_BYTES
            else -> 4
        }

        private fun memWidthOf(type: IrType): MemWidth = memWidthForBytes(widthOf(type))

        private fun isWordScalar(type: IrType): Boolean =
            (type as? IrPrimitive)?.kind in setOf(
                PrimitiveKind.I32,
                PrimitiveKind.U32,
                PrimitiveKind.ISIZE,
                PrimitiveKind.USIZE,
            )

        private fun isUnsignedScalar(type: IrType): Boolean =
            (type as? IrPrimitive)?.kind in setOf(
                PrimitiveKind.BOOL,
                PrimitiveKind.CHAR,
                PrimitiveKind.U32,
                PrimitiveKind.USIZE,
            )

        private fun isUnsignedWordScalar(type: IrType): Boolean =
            (type as? IrPrimitive)?.kind in setOf(
                PrimitiveKind.U32,
                PrimitiveKind.USIZE,
            )

        private fun collectIrDefinitions(): Map<String, IrInstruction> {
            val definitions = linkedMapOf<String, IrInstruction>()
            irFunc.blocks.forEach { block ->
                block.instructions.forEach { inst ->
                    definedName(inst)?.let { definitions[it] = inst }
                }
            }
            return definitions
        }

        private fun collectIrUseCounts(): Map<String, Int> {
            val counts = mutableMapOf<String, Int>()

            fun record(value: IrValue?) {
                val local = value as? IrLocal ?: return
                counts[local.name] = counts.getOrDefault(local.name, 0) + 1
            }

            irFunc.blocks.forEach { block ->
                block.instructions.forEach { inst ->
                    usedValues(inst).forEach(::record)
                }
                block.terminator?.let { term ->
                    usedValues(term).forEach(::record)
                }
            }
            return counts
        }

        private fun definedName(inst: IrInstruction): String? {
            if (inst.name.isBlank()) return null
            return when (inst) {
                is IrStore, is IrReturn, is IrBranch, is IrJump -> null
                is IrCall -> inst.name.takeUnless { isVoidType(inst.type) }
                else -> inst.name
            }
        }

        private fun usedValues(inst: IrInstruction): List<IrValue> = when (inst) {
            is IrAlloca -> emptyList()
            is IrConst -> emptyList()
            is IrLoad -> listOf(inst.address)
            is IrStore -> listOf(inst.address, inst.value)
            is IrBinary -> listOf(inst.lhs, inst.rhs)
            is IrUnary -> listOf(inst.operand)
            is IrCmp -> listOf(inst.lhs, inst.rhs)
            is IrCall -> inst.arguments
            is IrGep -> listOf(inst.base) + inst.indices
            is IrPhi -> inst.incoming.map { it.value }
            is IrCast -> listOf(inst.value)
            is IrReturn -> inst.value?.let(::listOf) ?: emptyList()
            is IrBranch -> listOf(inst.condition)
            is IrJump -> emptyList()
        }

        private fun isVoidType(type: IrType): Boolean =
            (type as? IrPrimitive)?.kind == PrimitiveKind.UNIT ||
                (type as? IrPrimitive)?.kind == PrimitiveKind.NEVER

        private fun vregFor(name: String, type: IrType): RvOperand.Reg {
            return valueMap.getOrPut(name) { mf.newVreg(widthOf(type)) }
        }

        private fun operandOf(value: IrValue, block: RvMachineBlock): RvOperand.Reg = when (value) {
            is IrLocal -> vregFor(value.name, value.type)

            is IrParameter -> {

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

                val rd = mf.newVreg(widthOf(value.type))
                block.append(RvInst.Li(rd, 0))
                rd
            }

            is IrGlobalRef -> {
                val rd = mf.newVreg(TargetLayout.POINTER_BYTES)
                block.append(RvInst.La(rd, value.name))
                rd
            }

            is IrFunctionRef -> {
                val rd = mf.newVreg(TargetLayout.POINTER_BYTES)
                block.append(RvInst.La(rd, value.name))
                rd
            }

            else -> error("ISel: unsupported IrValue type: ${value::class.simpleName}")
        }

        private fun slotAddress(slotIndex: Int, block: RvMachineBlock): RvOperand.Reg {
            val rd = mf.newVreg(TargetLayout.POINTER_BYTES)

            block.append(RvInst.Comment("slot $slotIndex"))
            block.append(RvInst.IType(RvArithImmOp.ADDI, rd, phys(RvPhysReg.SP), 0))
            return rd
        }

        private fun collectPhiCopies() {
            for (irBlock in irFunc.blocks) {
                val phis = irBlock.instructions.filterIsInstance<IrPhi>()
                if (phis.isEmpty()) continue

                blocksWithPhis.add(irBlock.label)

                for (phi in phis) {

                    require(!isAggregate(phi.type)) {
                        "ISel: φ node '${phi.name}' has aggregate type ${phi.type.render()}. " +
                            "Aggregates must use destination-passing, not φ nodes."
                    }

                    val dstReg = vregFor(phi.name, phi.type)

                    for (incoming in phi.incoming) {
                        val key = EdgeKey(incoming.predecessor, irBlock.label)
                        phiEdgeCopies.getOrPut(key) { mutableListOf() }
                            .add(PhiCopy(dstReg, incoming.value))
                    }
                }
            }
        }

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

                    entryBlock.append(RvInst.Mv(dstReg, phys(argRegs[i])))
                } else {

                    val overflowOffset = (i - argRegs.size) * TargetLayout.ABI_STACK_SLOT_BYTES
                    entryBlock.append(RvInst.Comment("overflow_arg $overflowOffset"))
                    entryBlock.append(
                        RvInst.Load(memWidthOf(paramType), dstReg, phys(RvPhysReg.SP), overflowOffset)
                    )

                }
            }
        }

        private fun lowerBlock(irBlock: IrBasicBlock) {
            val mb = blockMap[irBlock.label]!!

            for (inst in irBlock.instructions) {

                if (inst is IrPhi) continue
                if (inst is IrCmp && isBranchOnlyCmp(inst, irBlock)) continue
                lowerInstruction(inst, mb)
            }

            irBlock.terminator?.let { lowerTerminator(it, mb, irBlock) }
        }

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
                is IrPhi -> {  }
                is IrTerminator -> {  }
            }
        }

        private fun lowerAlloca(inst: IrAlloca, mb: RvMachineBlock) {
            val allocType = inst.allocatedType
            val (size, align) = typeLayout(allocType)
            val slotIdx = mf.allocateStackSlot(inst.name, size, align)
            slotMap[inst.name] = slotIdx

            val addrReg = slotAddress(slotIdx, mb)
            valueMap[inst.name] = addrReg
        }

        private fun lowerLoad(inst: IrLoad, mb: RvMachineBlock) {
            val addrReg = operandOf(inst.address, mb)
            val rd = vregFor(inst.name, inst.type)
            val mw = memWidthOf(inst.type)
            mb.append(RvInst.Load(mw, rd, addrReg, 0))

            if (inst.type is IrPrimitive && inst.type.kind == PrimitiveKind.BOOL) {
                mb.append(RvInst.IType(RvArithImmOp.ANDI, rd, rd, 1))
            }
        }

        private fun lowerStore(inst: IrStore, mb: RvMachineBlock) {
            val valueType = inst.value.type
            var valReg = operandOf(inst.value, mb)
            val addrReg = operandOf(inst.address, mb)
            val mw = memWidthOf(valueType)

            if (valueType is IrPrimitive && valueType.kind == PrimitiveKind.BOOL) {
                val masked = mf.newVreg(1)
                mb.append(RvInst.IType(RvArithImmOp.ANDI, masked, valReg, 1))
                valReg = masked
            }

            mb.append(RvInst.Store(mw, valReg, addrReg, 0))
        }

        private fun lowerBinary(inst: IrBinary, mb: RvMachineBlock) {
            val rd = vregFor(inst.name, inst.type)

            if (tryLowerBinaryByConstant(inst, rd, mb)) return

            if (inst.rhs is IrConstant) {
                val immVal = inst.rhs.value.toInt()
                val immOp = binaryToImm(inst.operator, immVal, inst.type)
                if (immOp != null) {
                    val lhs = operandOf(inst.lhs, mb)
                    mb.append(RvInst.IType(immOp, rd, lhs, immVal))
                    return
                }
            }

            if (inst.lhs is IrConstant && isCommutative(inst.operator)) {
                val immVal = inst.lhs.value.toInt()
                val immOp = binaryToImm(inst.operator, immVal, inst.type)
                if (immOp != null) {
                    val rhs = operandOf(inst.rhs, mb)
                    mb.append(RvInst.IType(immOp, rd, rhs, immVal))
                    return
                }
            }

            val lhs = operandOf(inst.lhs, mb)
            val rhs = operandOf(inst.rhs, mb)
            val rvOp = binaryToRType(inst.operator, inst.type)
            mb.append(RvInst.RType(rvOp, rd, lhs, rhs))
        }

        private fun tryLowerBinaryByConstant(
            inst: IrBinary,
            rd: RvOperand.Reg,
            mb: RvMachineBlock,
        ): Boolean {
            val rhs = inst.rhs as? IrConstant ?: return false
            val rhsValue = rhs.value.toInt()

            when (inst.operator) {
                BinaryOperator.UDIV -> {
                    if (rhsValue == 1) {
                        mb.append(RvInst.Mv(rd, operandOf(inst.lhs, mb)))
                        return true
                    }
                    if (isPositivePowerOfTwo(rhsValue)) {
                        mb.append(
                            RvInst.IType(
                                if (isWordScalar(inst.type)) RvArithImmOp.SRLIW else RvArithImmOp.SRLI,
                                rd,
                                operandOf(inst.lhs, mb),
                                log2(rhsValue)
                            )
                        )
                        return true
                    }
                }
                BinaryOperator.UREM -> {
                    if (rhsValue == 1) {
                        mb.append(RvInst.Li(rd, 0))
                        return true
                    }
                    if (isPositivePowerOfTwo(rhsValue)) {
                        val mask = rhsValue - 1
                        val lhs = operandOf(inst.lhs, mb)
                        if (fitsIn12Bit(mask)) {
                            mb.append(RvInst.IType(RvArithImmOp.ANDI, rd, lhs, mask))
                        } else {
                            val maskReg = mf.newVreg(widthOf(inst.type))
                            mb.append(RvInst.Li(maskReg, mask))
                            mb.append(RvInst.RType(RvArithOp.AND, rd, lhs, maskReg))
                        }
                        return true
                    }
                }
                BinaryOperator.SDIV -> {
                    if (rhsValue == 1) {
                        mb.append(RvInst.Mv(rd, operandOf(inst.lhs, mb)))
                        return true
                    }
                }
                BinaryOperator.SREM -> {
                    if (rhsValue == 1) {
                        mb.append(RvInst.Li(rd, 0))
                        return true
                    }
                }
                else -> Unit
            }

            return false
        }

        private fun binaryToRType(op: BinaryOperator, type: IrType): RvArithOp = when (op) {
            BinaryOperator.ADD -> if (isWordScalar(type)) RvArithOp.ADDW else RvArithOp.ADD
            BinaryOperator.SUB -> if (isWordScalar(type)) RvArithOp.SUBW else RvArithOp.SUB
            BinaryOperator.MUL -> if (isWordScalar(type)) RvArithOp.MULW else RvArithOp.MUL
            BinaryOperator.SDIV -> if (isWordScalar(type)) RvArithOp.DIVW else RvArithOp.DIV
            BinaryOperator.UDIV -> if (isWordScalar(type)) RvArithOp.DIVUW else RvArithOp.DIVU
            BinaryOperator.SREM -> if (isWordScalar(type)) RvArithOp.REMW else RvArithOp.REM
            BinaryOperator.UREM -> if (isWordScalar(type)) RvArithOp.REMUW else RvArithOp.REMU
            BinaryOperator.AND -> RvArithOp.AND
            BinaryOperator.OR -> RvArithOp.OR
            BinaryOperator.XOR -> RvArithOp.XOR
            BinaryOperator.SHL -> if (isWordScalar(type)) RvArithOp.SLLW else RvArithOp.SLL
            BinaryOperator.ASHR -> if (isWordScalar(type)) RvArithOp.SRAW else RvArithOp.SRA
            BinaryOperator.LSHR -> if (isWordScalar(type)) RvArithOp.SRLW else RvArithOp.SRL
        }

        private fun binaryToImm(op: BinaryOperator, immVal: Int): RvArithImmOp? {
            if (op in SHIFT_OPERATORS) {
                if (immVal !in 0..63) return null
            } else if (!fitsIn12Bit(immVal)) {
                return null
            }
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

        private fun binaryToImm(op: BinaryOperator, immVal: Int, type: IrType): RvArithImmOp? {
            val opImm = binaryToImm(op, immVal) ?: return null
            if (!isWordScalar(type)) return opImm
            if (op in SHIFT_OPERATORS && immVal !in 0..31) return null
            return when (opImm) {
                RvArithImmOp.ADDI -> RvArithImmOp.ADDIW
                RvArithImmOp.SLLI -> RvArithImmOp.SLLIW
                RvArithImmOp.SRLI -> RvArithImmOp.SRLIW
                RvArithImmOp.SRAI -> RvArithImmOp.SRAIW
                else -> opImm
            }
        }

        private fun isCommutative(op: BinaryOperator): Boolean = when (op) {
            BinaryOperator.ADD, BinaryOperator.MUL,
            BinaryOperator.AND, BinaryOperator.OR, BinaryOperator.XOR -> true
            else -> false
        }

        private fun lowerUnary(inst: IrUnary, mb: RvMachineBlock) {
            val rd = vregFor(inst.name, inst.type)
            val rs = operandOf(inst.operand, mb)

            when (inst.operator) {
                UnaryOperator.NEG -> {
                    if (isWordScalar(inst.type)) {
                        mb.append(RvInst.RType(RvArithOp.SUBW, rd, phys(RvPhysReg.ZERO), rs))
                    } else {
                        mb.append(RvInst.Neg(rd, rs))
                    }
                }
                UnaryOperator.NOT -> {
                    val isBool = inst.type is IrPrimitive && inst.type.kind == PrimitiveKind.BOOL
                    if (isBool) {

                        mb.append(RvInst.IType(RvArithImmOp.XORI, rd, rs, 1))
                    } else {

                        mb.append(RvInst.Not(rd, rs))
                    }
                }
            }
        }

        private fun lowerCmp(inst: IrCmp, mb: RvMachineBlock) {
            val rd = vregFor(inst.name, inst.type)
            val lhs = operandOf(inst.lhs, mb)
            val rhs = operandOf(inst.rhs, mb)
            val (cmpLhs, cmpRhs) = unsignedCompareOperands(inst, lhs, rhs, mb)

            when (inst.predicate) {
                ComparePredicate.EQ -> {

                    val t = mf.newVreg(widthOf(inst.lhs.type))
                    mb.append(RvInst.RType(RvArithOp.SUB, t, lhs, rhs))
                    mb.append(RvInst.Seqz(rd, t))
                }
                ComparePredicate.NE -> {
                    val t = mf.newVreg(widthOf(inst.lhs.type))
                    mb.append(RvInst.RType(RvArithOp.SUB, t, lhs, rhs))
                    mb.append(RvInst.Snez(rd, t))
                }
                ComparePredicate.SLT -> {
                    mb.append(RvInst.RType(RvArithOp.SLT, rd, lhs, rhs))
                }
                ComparePredicate.SLE -> {

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
                    mb.append(RvInst.RType(RvArithOp.SLTU, rd, cmpLhs, cmpRhs))
                }
                ComparePredicate.ULE -> {
                    mb.append(RvInst.RType(RvArithOp.SLTU, rd, cmpRhs, cmpLhs))
                    mb.append(RvInst.IType(RvArithImmOp.XORI, rd, rd, 1))
                }
                ComparePredicate.UGT -> {
                    mb.append(RvInst.RType(RvArithOp.SLTU, rd, cmpRhs, cmpLhs))
                }
                ComparePredicate.UGE -> {
                    mb.append(RvInst.RType(RvArithOp.SLTU, rd, cmpLhs, cmpRhs))
                    mb.append(RvInst.IType(RvArithImmOp.XORI, rd, rd, 1))
                }
            }
        }

        private fun lowerCall(inst: IrCall, mb: RvMachineBlock) {
            mf.hasCalls = true
            val calleeName = inst.callee.name
            val argPhysRegs = mutableListOf<RvPhysReg>()

            val isMemcpy = calleeName == "llvm.memcpy.p0.p0.i32"

            val actualCallee = if (isMemcpy) "memcpy" else calleeName

            val args = if (isMemcpy && inst.arguments.size == 4) {
                inst.arguments.take(3)
            } else {
                inst.arguments
            }

            for ((idx, arg) in args.withIndex()) {
                val argReg = operandOf(arg, mb)
                if (idx < ARG_REGS.size) {
                    mb.append(RvInst.Mv(phys(ARG_REGS[idx]), argReg))
                    argPhysRegs.add(ARG_REGS[idx])
                } else {

                    val overflowOffset = (idx - ARG_REGS.size) * TargetLayout.ABI_STACK_SLOT_BYTES
                    mb.append(RvInst.Store(MemWidth.DWORD, argReg, phys(RvPhysReg.SP), overflowOffset))

                    val needed = (idx - ARG_REGS.size + 1) * TargetLayout.ABI_STACK_SLOT_BYTES
                    if (needed > mf.outgoingArgAreaSize) {
                        mf.outgoingArgAreaSize = needed
                    }
                }
            }

            val returnsVoid = inst.type is IrPrimitive &&
                (inst.type as IrPrimitive).kind == PrimitiveKind.UNIT
            val resultPhysRegs = if (returnsVoid) emptyList() else listOf(RvPhysReg.A0)

            mb.append(RvInst.Call(actualCallee, argPhysRegs, resultPhysRegs))

            if (!returnsVoid && inst.name.isNotBlank()) {
                val rd = vregFor(inst.name, inst.type)
                mb.append(RvInst.Mv(rd, phys(RvPhysReg.A0)))
            }
        }

        private fun lowerGep(inst: IrGep, mb: RvMachineBlock) {
            val rd = vregFor(inst.name, inst.type)
            val baseReg = operandOf(inst.base, mb)

            val baseType = inst.base.type
            val pointee = (baseType as? IrPointer)?.pointee ?: baseType

            val offset = computeGepOffset(pointee, inst.indices, mb)
            if (offset is GepResult.Constant) {
                if (offset.value == 0) {
                    mb.append(RvInst.Mv(rd, baseReg))
                } else if (fitsIn12Bit(offset.value)) {
                    mb.append(RvInst.IType(RvArithImmOp.ADDI, rd, baseReg, offset.value))
                } else {
                    val t = mf.newVreg(TargetLayout.POINTER_BYTES)
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

            var currentType = basePointee
            var accumulatedConst = 0
            var dynamicReg: RvOperand.Reg? = null

            fun addConstant(c: Int) {
                if (dynamicReg == null) {
                    accumulatedConst += c
                } else {
                    if (c != 0) {
                        val t = mf.newVreg(TargetLayout.POINTER_BYTES)
                        if (fitsIn12Bit(c)) {
                            mb.append(RvInst.IType(RvArithImmOp.ADDI, t, dynamicReg!!, c))
                        } else {
                            val ci = mf.newVreg(TargetLayout.POINTER_BYTES)
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
                        val t = mf.newVreg(TargetLayout.POINTER_BYTES)
                        if (fitsIn12Bit(accumulatedConst)) {
                            mb.append(RvInst.IType(RvArithImmOp.ADDI, t, reg, accumulatedConst))
                        } else {
                            val ci = mf.newVreg(TargetLayout.POINTER_BYTES)
                            mb.append(RvInst.Li(ci, accumulatedConst))
                            mb.append(RvInst.RType(RvArithOp.ADD, t, reg, ci))
                        }
                        dynamicReg = t
                        accumulatedConst = 0
                    }
                } else {
                    val t = mf.newVreg(TargetLayout.POINTER_BYTES)
                    mb.append(RvInst.RType(RvArithOp.ADD, t, dynamicReg!!, reg))
                    dynamicReg = t
                }
            }

            for ((i, idx) in indices.withIndex()) {
                if (i == 0) {

                    val elemSize = typeSize(currentType)
                    if (idx is IrConstant) {
                        addConstant(idx.value.toInt() * elemSize)
                    } else {
                        val idxReg = operandOf(idx, mb)
                        if (elemSize == 1) {
                            addDynamic(idxReg)
                        } else {
                            addDynamic(scaleByConstant(idxReg, elemSize, mb))
                        }
                    }
                } else {

                    when (currentType) {
                        is IrStruct -> {

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
                                    addDynamic(scaleByConstant(idxReg, elemSize, mb))
                                }
                            }
                            currentType = currentType.element
                        }
                        else -> {

                            val elemSize = typeSize(currentType)
                            if (idx is IrConstant) {
                                addConstant(idx.value.toInt() * elemSize)
                            } else {
                                val idxReg = operandOf(idx, mb)
                                if (elemSize == 1) {
                                    addDynamic(idxReg)
                                } else {
                                    addDynamic(scaleByConstant(idxReg, elemSize, mb))
                                }
                            }
                        }
                    }
                }
            }

            return if (dynamicReg != null) GepResult.Register(dynamicReg!!)
            else GepResult.Constant(accumulatedConst)
        }

        private fun scaleByConstant(
            value: RvOperand.Reg,
            factor: Int,
            mb: RvMachineBlock,
        ): RvOperand.Reg {
            if (factor == 1) return value
            if (factor <= 0) {
                val zero = mf.newVreg(TargetLayout.POINTER_BYTES)
                mb.append(RvInst.Li(zero, 0))
                return zero
            }
            if (isPositivePowerOfTwo(factor)) {
                val shifted = mf.newVreg(TargetLayout.POINTER_BYTES)
                mb.append(RvInst.IType(RvArithImmOp.SLLI, shifted, value, log2(factor)))
                return shifted
            }
            if (Integer.bitCount(factor) <= MAX_SHIFT_ADD_TERMS) {
                var acc: RvOperand.Reg? = null
                for (bit in 0 until 32) {
                    if ((factor and (1 shl bit)) == 0) continue
                    val term = if (bit == 0) {
                        value
                    } else {
                        val shifted = mf.newVreg(TargetLayout.POINTER_BYTES)
                        mb.append(RvInst.IType(RvArithImmOp.SLLI, shifted, value, bit))
                        shifted
                    }
                    acc = if (acc == null) {
                        term
                    } else {
                        val sum = mf.newVreg(TargetLayout.POINTER_BYTES)
                        mb.append(RvInst.RType(RvArithOp.ADD, sum, acc, term))
                        sum
                    }
                }
                return acc ?: value
            }

            val sizeReg = mf.newVreg(TargetLayout.POINTER_BYTES)
            mb.append(RvInst.Li(sizeReg, factor))
            val product = mf.newVreg(TargetLayout.POINTER_BYTES)
            mb.append(RvInst.RType(RvArithOp.MUL, product, value, sizeReg))
            return product
        }

        private fun lowerCast(inst: IrCast, mb: RvMachineBlock) {
            val rd = vregFor(inst.name, inst.type)
            val rs = operandOf(inst.value, mb)
            val srcType = inst.value.type
            val dstType = inst.type

            when (inst.kind) {
                CastKind.BITCAST -> {
                    if (isWordScalar(dstType)) {
                        mb.append(RvInst.IType(RvArithImmOp.ADDIW, rd, rs, 0))
                    } else if (dstType is IrPointer && isUnsignedScalar(srcType)) {
                        zeroExtendWord(rd, rs, mb)
                    } else {
                        mb.append(RvInst.Mv(rd, rs))
                    }
                }

                CastKind.PTRTOINT -> {
                    if (isWordScalar(dstType)) {
                        mb.append(RvInst.IType(RvArithImmOp.ADDIW, rd, rs, 0))
                    } else {
                        mb.append(RvInst.Mv(rd, rs))
                    }
                }

                CastKind.INTTOPTR -> {
                    if (dstType is IrPointer && isUnsignedScalar(srcType)) {
                        zeroExtendWord(rd, rs, mb)
                    } else {
                        mb.append(RvInst.Mv(rd, rs))
                    }
                }

                CastKind.ZEXT -> {
                    when ((srcType as? IrPrimitive)?.kind) {
                        PrimitiveKind.BOOL -> mb.append(RvInst.IType(RvArithImmOp.ANDI, rd, rs, 1))
                        PrimitiveKind.CHAR -> mb.append(RvInst.IType(RvArithImmOp.ANDI, rd, rs, 0xFF))
                        PrimitiveKind.U32, PrimitiveKind.USIZE -> {
                            if (dstType is IrPointer) zeroExtendWord(rd, rs, mb)
                            else mb.append(RvInst.Mv(rd, rs))
                        }
                        else -> mb.append(RvInst.Mv(rd, rs))
                    }
                }

                CastKind.SEXT -> {
                    when ((srcType as? IrPrimitive)?.kind) {
                        PrimitiveKind.BOOL -> {
                            val shift = TargetLayout.REGISTER_BYTES * 8 - 1
                            mb.append(RvInst.IType(RvArithImmOp.SLLI, rd, rs, shift))
                            mb.append(RvInst.IType(RvArithImmOp.SRAI, rd, rd, shift))
                        }
                        PrimitiveKind.CHAR -> {
                            val shift = TargetLayout.REGISTER_BYTES * 8 - 8
                            mb.append(RvInst.IType(RvArithImmOp.SLLI, rd, rs, shift))
                            mb.append(RvInst.IType(RvArithImmOp.SRAI, rd, rd, shift))
                        }
                        else -> mb.append(RvInst.Mv(rd, rs))
                    }
                }

                CastKind.TRUNC -> {
                    when ((dstType as? IrPrimitive)?.kind) {
                        PrimitiveKind.BOOL -> mb.append(RvInst.IType(RvArithImmOp.ANDI, rd, rs, 1))
                        PrimitiveKind.CHAR -> mb.append(RvInst.IType(RvArithImmOp.ANDI, rd, rs, 0xFF))
                        PrimitiveKind.I32, PrimitiveKind.U32,
                        PrimitiveKind.ISIZE, PrimitiveKind.USIZE ->
                            mb.append(RvInst.IType(RvArithImmOp.ADDIW, rd, rs, 0))
                        else -> mb.append(RvInst.Mv(rd, rs))
                    }
                }
            }
        }

        private fun lowerConst(inst: IrConst, mb: RvMachineBlock) {
            val rd = vregFor(inst.name, inst.type)
            mb.append(RvInst.Li(rd, inst.constant.value.toInt()))
        }

        private fun zeroExtendWord(rd: RvOperand.Reg, rs: RvOperand.Reg, mb: RvMachineBlock) {
            val shift = TargetLayout.REGISTER_BYTES * 8 - TargetLayout.INT_BYTES * 8
            mb.append(RvInst.IType(RvArithImmOp.SLLI, rd, rs, shift))
            mb.append(RvInst.IType(RvArithImmOp.SRLI, rd, rd, shift))
        }

        private fun unsignedCompareOperands(
            cmp: IrCmp,
            lhs: RvOperand.Reg,
            rhs: RvOperand.Reg,
            mb: RvMachineBlock,
        ): Pair<RvOperand.Reg, RvOperand.Reg> {
            if (!isUnsignedCompare(cmp.predicate) || !isUnsignedWordScalar(cmp.lhs.type)) {
                return lhs to rhs
            }

            val lhsZext = mf.newVreg(TargetLayout.POINTER_BYTES)
            val rhsZext = mf.newVreg(TargetLayout.POINTER_BYTES)
            zeroExtendWord(lhsZext, lhs, mb)
            zeroExtendWord(rhsZext, rhs, mb)
            return lhsZext to rhsZext
        }

        private fun lowerTerminator(term: IrTerminator, mb: RvMachineBlock, irBlock: IrBasicBlock) {
            when (term) {
                is IrReturn -> lowerReturn(term, mb)
                is IrBranch -> lowerBranch(term, mb, irBlock)
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

        private fun lowerBranch(br: IrBranch, mb: RvMachineBlock, irBlock: IrBasicBlock) {
            if (tryLowerBranchOnCmp(br, mb, irBlock)) return

            val condReg = operandOf(br.condition, mb)
            val trueLabel = machineLabel(br.trueTarget)
            val falseLabel = machineLabel(br.falseTarget)

            emitConditionalBranch(
                RvBranchCond.BNE,
                condReg,
                phys(RvPhysReg.ZERO),
                trueLabel,
                falseLabel,
                nextMachineLabel(irBlock),
                mb,
            )
        }

        private fun tryLowerBranchOnCmp(
            br: IrBranch,
            mb: RvMachineBlock,
            irBlock: IrBasicBlock,
        ): Boolean {
            val cond = br.condition as? IrLocal ?: return false
            val cmp = irDefinitions[cond.name] as? IrCmp ?: return false
            if (!isBranchOnlyCmp(cmp, irBlock)) return false

            val selected = selectBranch(cmp)
            val trueLabel = machineLabel(br.trueTarget)
            val falseLabel = machineLabel(br.falseTarget)
            val lhs = operandOf(selected.lhs, mb)
            val rhs = operandOf(selected.rhs, mb)
            val (branchLhs, branchRhs) = unsignedCompareOperands(cmp, lhs, rhs, mb)
            emitConditionalBranch(
                selected.cond,
                branchLhs,
                branchRhs,
                trueLabel,
                falseLabel,
                nextMachineLabel(irBlock),
                mb,
            )
            return true
        }

        private fun isBranchOnlyCmp(cmp: IrCmp, irBlock: IrBasicBlock): Boolean {
            if (cmp.name.isBlank()) return false
            if (irBlock.instructions.none { it === cmp }) return false
            val branch = irBlock.terminator as? IrBranch ?: return false
            val condition = branch.condition as? IrLocal ?: return false
            return condition.name == cmp.name && irUseCounts.getOrDefault(cmp.name, 0) == 1
        }

        private fun emitConditionalBranch(
            cond: RvBranchCond,
            lhs: RvOperand,
            rhs: RvOperand,
            trueLabel: String,
            falseLabel: String,
            fallthroughLabel: String?,
            mb: RvMachineBlock,
        ) {
            when {
                trueLabel == falseLabel -> mb.append(RvInst.J(trueLabel))
                trueLabel == fallthroughLabel -> {
                    mb.append(RvInst.Branch(invertCondition(cond), lhs, rhs, falseLabel))
                    mb.append(RvInst.J(trueLabel))
                }
                else -> {
                    mb.append(RvInst.Branch(cond, lhs, rhs, trueLabel))
                    mb.append(RvInst.J(falseLabel))
                }
            }
        }

        private fun selectBranch(cmp: IrCmp): BranchSelection = when (cmp.predicate) {
            ComparePredicate.EQ -> BranchSelection(RvBranchCond.BEQ, cmp.lhs, cmp.rhs)
            ComparePredicate.NE -> BranchSelection(RvBranchCond.BNE, cmp.lhs, cmp.rhs)
            ComparePredicate.SLT -> BranchSelection(RvBranchCond.BLT, cmp.lhs, cmp.rhs)
            ComparePredicate.SLE -> BranchSelection(RvBranchCond.BGE, cmp.rhs, cmp.lhs)
            ComparePredicate.SGT -> BranchSelection(RvBranchCond.BLT, cmp.rhs, cmp.lhs)
            ComparePredicate.SGE -> BranchSelection(RvBranchCond.BGE, cmp.lhs, cmp.rhs)
            ComparePredicate.ULT -> BranchSelection(RvBranchCond.BLTU, cmp.lhs, cmp.rhs)
            ComparePredicate.ULE -> BranchSelection(RvBranchCond.BGEU, cmp.rhs, cmp.lhs)
            ComparePredicate.UGT -> BranchSelection(RvBranchCond.BLTU, cmp.rhs, cmp.lhs)
            ComparePredicate.UGE -> BranchSelection(RvBranchCond.BGEU, cmp.lhs, cmp.rhs)
        }

        private fun invertCondition(cond: RvBranchCond): RvBranchCond = when (cond) {
            RvBranchCond.BEQ -> RvBranchCond.BNE
            RvBranchCond.BNE -> RvBranchCond.BEQ
            RvBranchCond.BLT -> RvBranchCond.BGE
            RvBranchCond.BGE -> RvBranchCond.BLT
            RvBranchCond.BLTU -> RvBranchCond.BGEU
            RvBranchCond.BGEU -> RvBranchCond.BLTU
        }

        private fun nextMachineLabel(irBlock: IrBasicBlock): String? {
            val index = irFunc.blocks.indexOf(irBlock)
            val next = irFunc.blocks.getOrNull(index + 1) ?: return null
            return machineLabel(next.label)
        }

        private fun lowerJump(jmp: IrJump, mb: RvMachineBlock) {
            val target = machineLabel(jmp.target)
            mb.append(RvInst.J(target))
        }

        private fun emitPhiMoves() {

            for ((edge, copies) in phiEdgeCopies) {
                val predLabel = edge.predecessor
                val succLabel = edge.successor
                val predBlock = blockMap[predLabel]
                    ?: error("ISel: φ edge predecessor '$predLabel' not found")

                val predTerminator = irFunc.blocks.find { it.label == predLabel }?.terminator
                val isCritical = predTerminator is IrBranch &&
                    predTerminator.trueTarget != predTerminator.falseTarget &&
                    blocksWithPhis.contains(succLabel)

                val targetBlock: RvMachineBlock
                if (isCritical) {

                    targetBlock = splitCriticalEdge(predLabel, succLabel)
                } else {
                    targetBlock = predBlock
                }

                val resolvedCopies = resolveParallelCopies(copies, targetBlock)

                for (move in resolvedCopies) {
                    if (isCritical) {

                        targetBlock.insertBeforeTerminator(move)
                    } else {
                        targetBlock.insertBeforeTerminator(move)
                    }
                }
            }
        }

        private fun splitCriticalEdge(predLabel: String, succLabel: String): RvMachineBlock {
            val predMb = blockMap[predLabel]!!
            val succMachineLabel = machineLabel(succLabel)
            val trampolineLabel = "${machineLabel(predLabel)}.to.${succLabel}"

            val trampoline = mf.createBlock(trampolineLabel)
            trampoline.append(RvInst.J(succMachineLabel))

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
                    else -> {  }
                }
            }

            blockMap["$predLabel.to.$succLabel"] = trampoline
            return trampoline
        }

        private fun resolveParallelCopies(
            copies: List<PhiCopy>,
            emitBlock: RvMachineBlock,
        ): List<RvInst> {
            val result = mutableListOf<RvInst>()

            val constCopies = mutableListOf<PhiCopy>()
            val regCopies = mutableListOf<Pair<RvOperand.Reg, RvOperand.Reg>>()

            for (copy in copies) {
                val dst = copy.dst
                val srcVal = copy.srcValue

                when (srcVal) {
                    is IrConstant -> {
                        constCopies.add(copy)
                    }
                    is IrUndef -> {

                        result.add(RvInst.Li(dst, 0))
                    }
                    else -> {
                        val srcReg = operandOf(srcVal, emitBlock)

                        if (srcReg == dst) continue
                        regCopies.add(dst to srcReg)
                    }
                }
            }

            for (copy in constCopies) {
                result.add(RvInst.Li(copy.dst, (copy.srcValue as IrConstant).value.toInt()))
            }

            val pending = regCopies.toMutableList()

            while (pending.isNotEmpty()) {

                val readyIdx = pending.indexOfFirst { (dst, _) ->
                    pending.none { (_, src) -> src == dst }
                }

                if (readyIdx >= 0) {
                    val (dst, src) = pending.removeAt(readyIdx)
                    result.add(RvInst.Mv(dst, src))
                } else {

                    val (dst, src) = pending.removeAt(0)
                    val tmp = mf.newVreg(dst.width)
                    result.add(RvInst.Mv(tmp, dst))
                    result.add(RvInst.Mv(dst, src))

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

    private data class BranchSelection(
        val cond: RvBranchCond,
        val lhs: IrValue,
        val rhs: IrValue,
    )

    private companion object {
        const val MAX_SHIFT_ADD_TERMS = 3

        val SHIFT_OPERATORS = setOf(
            BinaryOperator.SHL,
            BinaryOperator.ASHR,
            BinaryOperator.LSHR,
        )

        fun isPositivePowerOfTwo(value: Int): Boolean =
            value > 0 && (value and (value - 1)) == 0

        fun isUnsignedCompare(predicate: ComparePredicate): Boolean = when (predicate) {
            ComparePredicate.ULT,
            ComparePredicate.ULE,
            ComparePredicate.UGT,
            ComparePredicate.UGE -> true
            else -> false
        }

        fun log2(value: Int): Int = Integer.numberOfTrailingZeros(value)
    }
}
