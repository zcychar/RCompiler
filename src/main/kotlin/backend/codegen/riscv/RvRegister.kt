package backend.codegen.riscv

/**
 * RV32I physical registers.
 *
 * Each entry carries its ABI name (used in assembly emission) and
 * its architectural number (x0–x31).
 */
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
        /** Lookup physical register by architectural index (0–31). */
        fun fromIndex(index: Int): RvPhysReg =
            entries.first { it.index == index }
    }
}

// ---------------------------------------------------------------------------
// Register classification helpers
// ---------------------------------------------------------------------------

/** Argument / return-value registers (caller-saved). */
val ARG_REGS: List<RvPhysReg> = listOf(
    RvPhysReg.A0, RvPhysReg.A1, RvPhysReg.A2, RvPhysReg.A3,
    RvPhysReg.A4, RvPhysReg.A5, RvPhysReg.A6, RvPhysReg.A7,
)

/** Temporary registers (caller-saved). */
val TEMP_REGS: List<RvPhysReg> = listOf(
    RvPhysReg.T0, RvPhysReg.T1, RvPhysReg.T2,
    RvPhysReg.T3, RvPhysReg.T4, RvPhysReg.T5, RvPhysReg.T6,
)

/** Callee-saved ("saved") registers. */
val SAVED_REGS: List<RvPhysReg> = listOf(
    RvPhysReg.S0, RvPhysReg.S1,
    RvPhysReg.S2, RvPhysReg.S3, RvPhysReg.S4, RvPhysReg.S5,
    RvPhysReg.S6, RvPhysReg.S7, RvPhysReg.S8, RvPhysReg.S9,
    RvPhysReg.S10, RvPhysReg.S11,
)

/** All caller-saved registers (clobbered across calls). */
val CALLER_SAVED_REGS: Set<RvPhysReg> = buildSet {
    addAll(TEMP_REGS)
    addAll(ARG_REGS)
}

/** All callee-saved registers (must be preserved across calls). */
val CALLEE_SAVED_REGS: Set<RvPhysReg> = SAVED_REGS.toSet()

/**
 * All registers available for the graph-coloring allocator.
 *
 * Excludes: zero, ra, sp, gp, tp (reserved for ABI use), and t0 (reserved
 * as a scratch register for frame-layout large-offset sequences).
 *
 * **K = 26** — the chromatic number budget for Chaitin-Briggs.
 */
val ALLOCATABLE_REGS: List<RvPhysReg> = buildList {
    // t0 is reserved as a scratch register for FrameLayout (large-offset
    // spill loads/stores and SP adjustments).  Only t1–t6 are allocatable.
    addAll(TEMP_REGS.filter { it != RvPhysReg.T0 })  // t1–t6   (6)
    addAll(ARG_REGS)    // a0–a7   (8)
    addAll(SAVED_REGS)  // s0–s11  (12)
}

/** The number of colors available for graph coloring (K). */
const val NUM_ALLOCATABLE: Int = 26   // 6 + 8 + 12

/** Reserved registers — never handed out by the allocator. */
val RESERVED_REGS: Set<RvPhysReg> = setOf(
    RvPhysReg.ZERO,
    RvPhysReg.RA,
    RvPhysReg.SP,
    RvPhysReg.GP,
    RvPhysReg.TP,
    RvPhysReg.T0,   // reserved as scratch for FrameLayout large-offset sequences
)

// ---------------------------------------------------------------------------
// Operand model
// ---------------------------------------------------------------------------

/**
 * Relocation kind — corresponds to RISC-V assembler relocation operators.
 */
enum class RelocKind(val asmPrefix: String) {
    HI("%hi"),
    LO("%lo"),
    PCREL_HI("%pcrel_hi"),
    PCREL_LO("%pcrel_lo"),
}

/**
 * A machine-level operand.
 *
 * During instruction selection every value is represented as [Reg] (virtual register).
 * After register allocation, [Reg] operands are replaced with [PhysReg].
 */
sealed class RvOperand {

    /**
     * Virtual register, identified by a unique non-negative integer.
     * [width] records the data width in bytes (1 for i1/i8, 4 for i32/ptr)
     * and is used when generating spill loads/stores.
     */
    data class Reg(val id: Int, val width: Int = 4) : RvOperand() {
        override fun toString(): String = "v$id"
    }

    /** A pre-colored / resolved physical register. */
    data class PhysReg(val reg: RvPhysReg) : RvOperand() {
        override fun toString(): String = reg.abiName
    }

    /** An integer immediate (may exceed 12 bits; instruction selection decides encoding). */
    data class Imm(val value: Int) : RvOperand() {
        override fun toString(): String = value.toString()
    }

    /** A relocation expression such as `%hi(symbol)` or `%lo(symbol)`. */
    data class Reloc(val kind: RelocKind, val symbol: String) : RvOperand() {
        override fun toString(): String = "${kind.asmPrefix}($symbol)"
    }

    /** A symbolic label used as a branch or call target. */
    data class Label(val name: String) : RvOperand() {
        override fun toString(): String = name
    }
}

// ---------------------------------------------------------------------------
// Convenience helpers
// ---------------------------------------------------------------------------

/** Shorthand for creating a virtual-register operand (word-width). */
fun vreg(id: Int, width: Int = 4): RvOperand.Reg = RvOperand.Reg(id, width)

/** Shorthand for creating a physical-register operand. */
fun phys(reg: RvPhysReg): RvOperand.PhysReg = RvOperand.PhysReg(reg)

/** Shorthand for an immediate operand. */
fun imm(value: Int): RvOperand.Imm = RvOperand.Imm(value)

/** Check whether an integer fits in a 12-bit signed immediate (−2048..2047). */
fun fitsIn12Bit(value: Int): Boolean = value in -2048..2047
