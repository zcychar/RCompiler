package backend.codegen.riscv

// Defines RV64 registers, register classes, and machine operands.

enum class RvPhysReg(val abiName: String, val index: Int) {
    ZERO("zero", 0),
    RA("ra", 1),
    SP("sp", 2),
    GP("gp", 3),
    TP("tp", 4),

    T0("t0", 5),
    T1("t1", 6),
    T2("t2", 7),

    S0("s0", 8),
    S1("s1", 9),

    A0("a0", 10),
    A1("a1", 11),
    A2("a2", 12),
    A3("a3", 13),
    A4("a4", 14),
    A5("a5", 15),
    A6("a6", 16),
    A7("a7", 17),

    S2("s2", 18),
    S3("s3", 19),
    S4("s4", 20),
    S5("s5", 21),
    S6("s6", 22),
    S7("s7", 23),
    S8("s8", 24),
    S9("s9", 25),
    S10("s10", 26),
    S11("s11", 27),

    T3("t3", 28),
    T4("t4", 29),
    T5("t5", 30),
    T6("t6", 31),
    ;

    override fun toString(): String = abiName

    companion object {

        fun fromIndex(index: Int): RvPhysReg =
            entries.first { it.index == index }
    }
}

val ARG_REGS: List<RvPhysReg> = listOf(
    RvPhysReg.A0, RvPhysReg.A1, RvPhysReg.A2, RvPhysReg.A3,
    RvPhysReg.A4, RvPhysReg.A5, RvPhysReg.A6, RvPhysReg.A7,
)

val TEMP_REGS: List<RvPhysReg> = listOf(
    RvPhysReg.T0, RvPhysReg.T1, RvPhysReg.T2,
    RvPhysReg.T3, RvPhysReg.T4, RvPhysReg.T5, RvPhysReg.T6,
)

val SAVED_REGS: List<RvPhysReg> = listOf(
    RvPhysReg.S0, RvPhysReg.S1,
    RvPhysReg.S2, RvPhysReg.S3, RvPhysReg.S4, RvPhysReg.S5,
    RvPhysReg.S6, RvPhysReg.S7, RvPhysReg.S8, RvPhysReg.S9,
    RvPhysReg.S10, RvPhysReg.S11,
)

val CALLER_SAVED_REGS: Set<RvPhysReg> = buildSet {
    addAll(TEMP_REGS)
    addAll(ARG_REGS)
}

val CALLEE_SAVED_REGS: Set<RvPhysReg> = SAVED_REGS.toSet()

val ALLOCATABLE_REGS: List<RvPhysReg> = buildList {

    addAll(TEMP_REGS.filter { it != RvPhysReg.T0 })
    addAll(ARG_REGS)
    addAll(SAVED_REGS)
}

const val NUM_ALLOCATABLE: Int = 26

val RESERVED_REGS: Set<RvPhysReg> = setOf(
    RvPhysReg.ZERO,
    RvPhysReg.RA,
    RvPhysReg.SP,
    RvPhysReg.GP,
    RvPhysReg.TP,
    RvPhysReg.T0,
)

enum class RelocKind(val asmPrefix: String) {
    HI("%hi"),
    LO("%lo"),
    PCREL_HI("%pcrel_hi"),
    PCREL_LO("%pcrel_lo"),
}

sealed class RvOperand {

    data class Reg(val id: Int, val width: Int = 4) : RvOperand() {
        override fun toString(): String = "v$id"
    }

    data class PhysReg(val reg: RvPhysReg) : RvOperand() {
        override fun toString(): String = reg.abiName
    }

    data class Imm(val value: Int) : RvOperand() {
        override fun toString(): String = value.toString()
    }

    data class Reloc(val kind: RelocKind, val symbol: String) : RvOperand() {
        override fun toString(): String = "${kind.asmPrefix}($symbol)"
    }

    data class Label(val name: String) : RvOperand() {
        override fun toString(): String = name
    }
}

fun vreg(id: Int, width: Int = 4): RvOperand.Reg = RvOperand.Reg(id, width)

fun phys(reg: RvPhysReg): RvOperand.PhysReg = RvOperand.PhysReg(reg)

fun imm(value: Int): RvOperand.Imm = RvOperand.Imm(value)

fun fitsIn12Bit(value: Int): Boolean = value in -2048..2047
