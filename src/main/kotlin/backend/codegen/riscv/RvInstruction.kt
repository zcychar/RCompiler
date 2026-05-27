package backend.codegen.riscv

// Defines RISC-V machine instruction nodes and rendering helpers.

sealed class RvInst {

    abstract fun render(): String

    abstract fun defs(): List<RvOperand>

    abstract fun uses(): List<RvOperand>

    abstract fun mapRegs(mapping: Map<Int, RvOperand.PhysReg>): RvInst

    open fun isMove(): Boolean = false

    data class RType(
        val op: RvArithOp,
        val rd: RvOperand,
        val rs1: RvOperand,
        val rs2: RvOperand,
    ) : RvInst() {
        override fun render(): String =
            "${op.mnemonic}  ${rd.asm()}, ${rs1.asm()}, ${rs2.asm()}"

        override fun defs(): List<RvOperand> = listOf(rd)
        override fun uses(): List<RvOperand> = listOf(rs1, rs2)

        override fun mapRegs(mapping: Map<Int, RvOperand.PhysReg>): RvInst =
            copy(rd = rd.rewrite(mapping), rs1 = rs1.rewrite(mapping), rs2 = rs2.rewrite(mapping))
    }

    data class IType(
        val op: RvArithImmOp,
        val rd: RvOperand,
        val rs1: RvOperand,
        val imm: Int,
    ) : RvInst() {
        override fun render(): String =
            "${op.mnemonic}  ${rd.asm()}, ${rs1.asm()}, $imm"

        override fun defs(): List<RvOperand> = listOf(rd)
        override fun uses(): List<RvOperand> = listOf(rs1)

        override fun mapRegs(mapping: Map<Int, RvOperand.PhysReg>): RvInst =
            copy(rd = rd.rewrite(mapping), rs1 = rs1.rewrite(mapping))
    }

    data class Load(
        val width: MemWidth,
        val rd: RvOperand,
        val base: RvOperand,
        val offset: Int,
    ) : RvInst() {
        override fun render(): String =
            "${width.loadMnemonic}  ${rd.asm()}, $offset(${base.asm()})"

        override fun defs(): List<RvOperand> = listOf(rd)
        override fun uses(): List<RvOperand> = listOf(base)

        override fun mapRegs(mapping: Map<Int, RvOperand.PhysReg>): RvInst =
            copy(rd = rd.rewrite(mapping), base = base.rewrite(mapping))
    }

    data class Store(
        val width: MemWidth,
        val rs: RvOperand,
        val base: RvOperand,
        val offset: Int,
    ) : RvInst() {
        override fun render(): String =
            "${width.storeMnemonic}  ${rs.asm()}, $offset(${base.asm()})"

        override fun defs(): List<RvOperand> = emptyList()
        override fun uses(): List<RvOperand> = listOf(rs, base)

        override fun mapRegs(mapping: Map<Int, RvOperand.PhysReg>): RvInst =
            copy(rs = rs.rewrite(mapping), base = base.rewrite(mapping))
    }

    data class Branch(
        val cond: RvBranchCond,
        val rs1: RvOperand,
        val rs2: RvOperand,
        val target: String,
    ) : RvInst() {
        override fun render(): String =
            "${cond.mnemonic}  ${rs1.asm()}, ${rs2.asm()}, $target"

        override fun defs(): List<RvOperand> = emptyList()
        override fun uses(): List<RvOperand> = listOf(rs1, rs2)

        override fun mapRegs(mapping: Map<Int, RvOperand.PhysReg>): RvInst =
            copy(rs1 = rs1.rewrite(mapping), rs2 = rs2.rewrite(mapping))
    }

    data class Lui(
        val rd: RvOperand,
        val imm: RvOperand,
    ) : RvInst() {
        override fun render(): String =
            "lui  ${rd.asm()}, ${imm.asm()}"

        override fun defs(): List<RvOperand> = listOf(rd)
        override fun uses(): List<RvOperand> = emptyList()

        override fun mapRegs(mapping: Map<Int, RvOperand.PhysReg>): RvInst =
            copy(rd = rd.rewrite(mapping))
    }

    data class Li(
        val rd: RvOperand,
        val value: Int,
    ) : RvInst() {
        override fun render(): String =
            "li  ${rd.asm()}, $value"

        override fun defs(): List<RvOperand> = listOf(rd)
        override fun uses(): List<RvOperand> = emptyList()

        override fun mapRegs(mapping: Map<Int, RvOperand.PhysReg>): RvInst =
            copy(rd = rd.rewrite(mapping))
    }

    data class La(
        val rd: RvOperand,
        val symbol: String,
    ) : RvInst() {
        override fun render(): String =
            "la  ${rd.asm()}, $symbol"

        override fun defs(): List<RvOperand> = listOf(rd)
        override fun uses(): List<RvOperand> = emptyList()

        override fun mapRegs(mapping: Map<Int, RvOperand.PhysReg>): RvInst =
            copy(rd = rd.rewrite(mapping))
    }

    data class Mv(
        val rd: RvOperand,
        val rs: RvOperand,
    ) : RvInst() {
        override fun render(): String =
            "mv  ${rd.asm()}, ${rs.asm()}"

        override fun defs(): List<RvOperand> = listOf(rd)
        override fun uses(): List<RvOperand> = listOf(rs)
        override fun isMove(): Boolean = true

        override fun mapRegs(mapping: Map<Int, RvOperand.PhysReg>): RvInst =
            copy(rd = rd.rewrite(mapping), rs = rs.rewrite(mapping))
    }

    data class Neg(
        val rd: RvOperand,
        val rs: RvOperand,
    ) : RvInst() {
        override fun render(): String =
            "neg  ${rd.asm()}, ${rs.asm()}"

        override fun defs(): List<RvOperand> = listOf(rd)
        override fun uses(): List<RvOperand> = listOf(rs)

        override fun mapRegs(mapping: Map<Int, RvOperand.PhysReg>): RvInst =
            copy(rd = rd.rewrite(mapping), rs = rs.rewrite(mapping))
    }

    data class Not(
        val rd: RvOperand,
        val rs: RvOperand,
    ) : RvInst() {
        override fun render(): String =
            "not  ${rd.asm()}, ${rs.asm()}"

        override fun defs(): List<RvOperand> = listOf(rd)
        override fun uses(): List<RvOperand> = listOf(rs)

        override fun mapRegs(mapping: Map<Int, RvOperand.PhysReg>): RvInst =
            copy(rd = rd.rewrite(mapping), rs = rs.rewrite(mapping))
    }

    data class Seqz(
        val rd: RvOperand,
        val rs: RvOperand,
    ) : RvInst() {
        override fun render(): String =
            "seqz  ${rd.asm()}, ${rs.asm()}"

        override fun defs(): List<RvOperand> = listOf(rd)
        override fun uses(): List<RvOperand> = listOf(rs)

        override fun mapRegs(mapping: Map<Int, RvOperand.PhysReg>): RvInst =
            copy(rd = rd.rewrite(mapping), rs = rs.rewrite(mapping))
    }

    data class Snez(
        val rd: RvOperand,
        val rs: RvOperand,
    ) : RvInst() {
        override fun render(): String =
            "snez  ${rd.asm()}, ${rs.asm()}"

        override fun defs(): List<RvOperand> = listOf(rd)
        override fun uses(): List<RvOperand> = listOf(rs)

        override fun mapRegs(mapping: Map<Int, RvOperand.PhysReg>): RvInst =
            copy(rd = rd.rewrite(mapping), rs = rs.rewrite(mapping))
    }

    data class J(
        val target: String,
    ) : RvInst() {
        override fun render(): String = "j  $target"

        override fun defs(): List<RvOperand> = emptyList()
        override fun uses(): List<RvOperand> = emptyList()

        override fun mapRegs(mapping: Map<Int, RvOperand.PhysReg>): RvInst = this
    }

    data class Call(
        val target: String,
        val argRegs: List<RvPhysReg> = emptyList(),
        val resultRegs: List<RvPhysReg> = emptyList(),
    ) : RvInst() {
        override fun render(): String = "call  $target"

        override fun defs(): List<RvOperand> = buildList {

            for (r in CALLER_SAVED_REGS) add(RvOperand.PhysReg(r))

            add(RvOperand.PhysReg(RvPhysReg.RA))
        }

        override fun uses(): List<RvOperand> =
            argRegs.map { RvOperand.PhysReg(it) }

        override fun mapRegs(mapping: Map<Int, RvOperand.PhysReg>): RvInst = this
    }

    data class Ret(

        val liveRegs: List<RvPhysReg> = emptyList(),
    ) : RvInst() {
        override fun render(): String = "ret"

        override fun defs(): List<RvOperand> = emptyList()
        override fun uses(): List<RvOperand> = buildList {
            add(RvOperand.PhysReg(RvPhysReg.RA))
            for (r in liveRegs) add(RvOperand.PhysReg(r))
        }

        override fun mapRegs(mapping: Map<Int, RvOperand.PhysReg>): RvInst = this
    }

    data class Comment(val text: String) : RvInst() {
        override fun render(): String = "# $text"
        override fun defs(): List<RvOperand> = emptyList()
        override fun uses(): List<RvOperand> = emptyList()
        override fun mapRegs(mapping: Map<Int, RvOperand.PhysReg>): RvInst = this
    }
}

enum class RvArithOp(val mnemonic: String) {
    ADD("add"),
    SUB("sub"),
    ADDW("addw"),
    SUBW("subw"),
    AND("and"),
    OR("or"),
    XOR("xor"),
    SLL("sll"),
    SRL("srl"),
    SRA("sra"),
    SLLW("sllw"),
    SRLW("srlw"),
    SRAW("sraw"),
    SLT("slt"),
    SLTU("sltu"),

    MUL("mul"),
    MULW("mulw"),
    MULH("mulh"),
    MULHSU("mulhsu"),
    MULHU("mulhu"),
    DIV("div"),
    DIVW("divw"),
    DIVU("divu"),
    DIVUW("divuw"),
    REM("rem"),
    REMW("remw"),
    REMU("remu"),
    REMUW("remuw"),
}

enum class RvArithImmOp(val mnemonic: String) {
    ADDI("addi"),
    ADDIW("addiw"),
    ANDI("andi"),
    ORI("ori"),
    XORI("xori"),
    SLTI("slti"),
    SLTIU("sltiu"),
    SLLI("slli"),
    SLLIW("slliw"),
    SRLI("srli"),
    SRLIW("srliw"),
    SRAI("srai"),
    SRAIW("sraiw"),
}

enum class RvBranchCond(val mnemonic: String) {
    BEQ("beq"),
    BNE("bne"),
    BLT("blt"),
    BGE("bge"),
    BLTU("bltu"),
    BGEU("bgeu"),
}

enum class MemWidth(
    val bytes: Int,
    val loadMnemonic: String,
    val storeMnemonic: String,
) {

    BYTE(1, "lbu", "sb"),

    HALF(2, "lhu", "sh"),

    WORD(4, "lw", "sw"),

    DWORD(8, "ld", "sd"),
}

fun memWidthForBytes(bytes: Int): MemWidth = when (bytes) {
    1 -> MemWidth.BYTE
    2 -> MemWidth.HALF
    4 -> MemWidth.WORD
    8 -> MemWidth.DWORD
    else -> error("unsupported memory width: $bytes bytes")
}

fun RvOperand.asm(): String = when (this) {
    is RvOperand.Reg -> "v$id"
    is RvOperand.PhysReg -> reg.abiName
    is RvOperand.Imm -> value.toString()
    is RvOperand.Reloc -> "${kind.asmPrefix}($symbol)"
    is RvOperand.Label -> name
}

fun RvOperand.rewrite(mapping: Map<Int, RvOperand.PhysReg>): RvOperand = when (this) {
    is RvOperand.Reg -> mapping[id] ?: this
    else -> this
}

fun RvOperand.isReg(): Boolean = this is RvOperand.Reg || this is RvOperand.PhysReg
