package backend.codegen.riscv

/**
 * RISC-V machine-level instruction representation.
 *
 * These model a subset of RV32IM sufficient to lower every IR instruction.
 * During instruction selection, operands are [RvOperand.Reg] (virtual registers).
 * After register allocation, they are replaced with [RvOperand.PhysReg].
 *
 * Every instruction knows how to [render] itself into GNU-style assembly text
 * and can report which operands it [defs] and [uses] (for liveness analysis).
 */
sealed class RvInst {

    /** Render this instruction as a single line of assembly (no leading indent). */
    abstract fun render(): String

    /**
     * Operands **defined** (written) by this instruction.
     * Returns a list of operands that are register-typed ([RvOperand.Reg] or [RvOperand.PhysReg]).
     */
    abstract fun defs(): List<RvOperand>

    /**
     * Operands **used** (read) by this instruction.
     * Returns a list of operands that are register-typed ([RvOperand.Reg] or [RvOperand.PhysReg]).
     */
    abstract fun uses(): List<RvOperand>

    /**
     * Return a copy of this instruction with register operands rewritten
     * according to the given mapping. Operands not in the map are left as-is.
     */
    abstract fun mapRegs(mapping: Map<Int, RvOperand.PhysReg>): RvInst

    /**
     * Whether this instruction is a **move** (`mv rd, rs`) that the register
     * allocator may try to coalesce.
     */
    open fun isMove(): Boolean = false

    // ===================================================================
    //  R-type: register-register arithmetic/logic
    // ===================================================================

    /**
     * R-type instruction: `<op> rd, rs1, rs2`
     *
     * Covers: add, sub, and, or, xor, sll, srl, sra, slt, sltu,
     *         mul, mulh, mulhsu, mulhu, div, divu, rem, remu
     */
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

    // ===================================================================
    //  I-type: register-immediate arithmetic/logic
    // ===================================================================

    /**
     * I-type instruction: `<op> rd, rs1, imm`
     *
     * Covers: addi, andi, ori, xori, slti, sltiu, slli, srli, srai
     */
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

    // ===================================================================
    //  Load: lb, lbu, lh, lhu, lw
    // ===================================================================

    /**
     * Load instruction: `<lw|lb|lbu|lh|lhu> rd, offset(base)`
     */
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

    // ===================================================================
    //  Store: sb, sh, sw
    // ===================================================================

    /**
     * Store instruction: `<sw|sb|sh> rs, offset(base)`
     */
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

    // ===================================================================
    //  Branch: beq, bne, blt, bge, bltu, bgeu
    // ===================================================================

    /**
     * Conditional branch: `<bcond> rs1, rs2, target`
     */
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

    // ===================================================================
    //  LUI
    // ===================================================================

    /**
     * `lui rd, imm`  — load upper immediate (bits 31:12).
     */
    data class Lui(
        val rd: RvOperand,
        val imm: RvOperand,   // Imm or Reloc(%hi(sym))
    ) : RvInst() {
        override fun render(): String =
            "lui  ${rd.asm()}, ${imm.asm()}"

        override fun defs(): List<RvOperand> = listOf(rd)
        override fun uses(): List<RvOperand> = emptyList()

        override fun mapRegs(mapping: Map<Int, RvOperand.PhysReg>): RvInst =
            copy(rd = rd.rewrite(mapping))
    }

    // ===================================================================
    //  Pseudo-instructions
    // ===================================================================

    /**
     * `li rd, value` — load immediate (assembler expands to lui+addi if needed).
     */
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

    /**
     * `la rd, symbol` — load address of a label (assembler expands to lui+addi / auipc+addi).
     */
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

    /**
     * `mv rd, rs` — register move (pseudo for `addi rd, rs, 0`).
     *
     * This is the primary candidate for **move coalescing** in the register allocator.
     */
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

    /**
     * `neg rd, rs` — negate (pseudo for `sub rd, zero, rs`).
     */
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

    /**
     * `not rd, rs` — bitwise NOT (pseudo for `xori rd, rs, -1`).
     */
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

    /**
     * `seqz rd, rs` — set if equal to zero (pseudo for `sltiu rd, rs, 1`).
     */
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

    /**
     * `snez rd, rs` — set if not equal to zero (pseudo for `sltu rd, zero, rs`).
     */
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

    // ===================================================================
    //  Control-flow pseudo-instructions
    // ===================================================================

    /**
     * `j target` — unconditional jump (pseudo for `jal zero, target`).
     */
    data class J(
        val target: String,
    ) : RvInst() {
        override fun render(): String = "j  $target"

        override fun defs(): List<RvOperand> = emptyList()
        override fun uses(): List<RvOperand> = emptyList()

        override fun mapRegs(mapping: Map<Int, RvOperand.PhysReg>): RvInst = this
    }

    /**
     * `call target` — function call (pseudo for `auipc ra, ...` / `jalr ra, ...`).
     *
     * The register allocator must model that a `call`:
     * - **Defines** (clobbers) all caller-saved registers + `ra`.
     * - **Uses** argument registers as set up by preceding moves.
     *
     * [argRegs] lists the physical argument registers actually carrying
     * values for this particular call (subset of a0–a7), so liveness
     * analysis can track them correctly.
     *
     * [resultRegs] lists the physical registers that hold return values
     * after the call (typically just a0, or empty for void calls).
     */
    data class Call(
        val target: String,
        val argRegs: List<RvPhysReg> = emptyList(),
        val resultRegs: List<RvPhysReg> = emptyList(),
    ) : RvInst() {
        override fun render(): String = "call  $target"

        override fun defs(): List<RvOperand> = buildList {
            // The call clobbers all caller-saved registers.
            for (r in CALLER_SAVED_REGS) add(RvOperand.PhysReg(r))
            // ra is implicitly written by call.
            add(RvOperand.PhysReg(RvPhysReg.RA))
        }

        override fun uses(): List<RvOperand> =
            argRegs.map { RvOperand.PhysReg(it) }

        override fun mapRegs(mapping: Map<Int, RvOperand.PhysReg>): RvInst = this
    }

    /**
     * `ret` — return from function (pseudo for `jalr zero, ra, 0`).
     *
     * Implicitly uses `ra`. If the function returns a value, `a0` should
     * be live-in at this point (handled by the instruction selector emitting
     * a preceding `mv a0, <result>`).
     */
    data class Ret(
        /** Physical registers live at the `ret` point (typically a0 for non-void returns). */
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

    // ===================================================================
    //  Comment / label pseudo (for readability of generated assembly)
    // ===================================================================

    /**
     * Assembler comment line — not a real instruction, emitted as `# text`.
     */
    data class Comment(val text: String) : RvInst() {
        override fun render(): String = "# $text"
        override fun defs(): List<RvOperand> = emptyList()
        override fun uses(): List<RvOperand> = emptyList()
        override fun mapRegs(mapping: Map<Int, RvOperand.PhysReg>): RvInst = this
    }
}

// ===========================================================================
//  Enumerations for instruction opcodes / conditions / widths
// ===========================================================================

/**
 * R-type (register-register) arithmetic/logic opcodes — RV32IM.
 */
enum class RvArithOp(val mnemonic: String) {
    ADD("add"),
    SUB("sub"),
    AND("and"),
    OR("or"),
    XOR("xor"),
    SLL("sll"),
    SRL("srl"),
    SRA("sra"),
    SLT("slt"),
    SLTU("sltu"),

    // M-extension
    MUL("mul"),
    MULH("mulh"),
    MULHSU("mulhsu"),
    MULHU("mulhu"),
    DIV("div"),
    DIVU("divu"),
    REM("rem"),
    REMU("remu"),
}

/**
 * I-type (register-immediate) arithmetic/logic/shift opcodes.
 */
enum class RvArithImmOp(val mnemonic: String) {
    ADDI("addi"),
    ANDI("andi"),
    ORI("ori"),
    XORI("xori"),
    SLTI("slti"),
    SLTIU("sltiu"),
    SLLI("slli"),
    SRLI("srli"),
    SRAI("srai"),
}

/**
 * Branch condition opcodes.
 */
enum class RvBranchCond(val mnemonic: String) {
    BEQ("beq"),
    BNE("bne"),
    BLT("blt"),
    BGE("bge"),
    BLTU("bltu"),
    BGEU("bgeu"),
}

/**
 * Memory access width — determines which load/store mnemonic to use.
 *
 * Loads of sub-word sizes come in signed and unsigned variants.
 * For our compiler `i8` (char) is unsigned, so we use `lbu` for byte loads.
 * `i1` (bool) is stored as a byte and also loaded unsigned.
 */
enum class MemWidth(
    val bytes: Int,
    val loadMnemonic: String,
    val storeMnemonic: String,
) {
    /** Byte (8-bit) — unsigned load. Used for i1 (bool) and i8 (char). */
    BYTE(1, "lbu", "sb"),

    /** Half-word (16-bit) — unsigned load. Not used in current IR but defined for completeness. */
    HALF(2, "lhu", "sh"),

    /** Word (32-bit). Used for i32, pointers, and all 32-bit values. */
    WORD(4, "lw", "sw"),
}

// ===========================================================================
//  Operand rendering / rewriting helpers
// ===========================================================================

/**
 * Render an operand for assembly output.
 * - [RvOperand.Reg] → should not appear in final output (must be allocated first).
 * - [RvOperand.PhysReg] → ABI register name.
 * - [RvOperand.Imm] → decimal integer.
 * - [RvOperand.Reloc] → `%hi(sym)` / `%lo(sym)`.
 * - [RvOperand.Label] → bare label name.
 */
fun RvOperand.asm(): String = when (this) {
    is RvOperand.Reg -> "v$id"   // virtual — should be resolved before final emission
    is RvOperand.PhysReg -> reg.abiName
    is RvOperand.Imm -> value.toString()
    is RvOperand.Reloc -> "${kind.asmPrefix}($symbol)"
    is RvOperand.Label -> name
}

/**
 * If this operand is a virtual register whose [RvOperand.Reg.id] appears in [mapping],
 * return the corresponding [RvOperand.PhysReg]; otherwise return `this` unchanged.
 */
fun RvOperand.rewrite(mapping: Map<Int, RvOperand.PhysReg>): RvOperand = when (this) {
    is RvOperand.Reg -> mapping[id] ?: this
    else -> this
}

/**
 * Return `true` if this operand is a register (virtual or physical).
 */
fun RvOperand.isReg(): Boolean = this is RvOperand.Reg || this is RvOperand.PhysReg
