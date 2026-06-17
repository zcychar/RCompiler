# Codegen Design — RISC-V Assembly Backend

> This document describes the design of the code generation stage for the RCompiler.
> The target platform is **REIMU**, a RISC-V RV32IM simulator.

---

## Table of Contents

1. [Overview](#1-overview)
2. [Target Platform: REIMU](#2-target-platform-reimu)
3. [Pipeline Architecture](#3-pipeline-architecture)
4. [RISC-V Register Model](#4-risc-v-register-model)
5. [Machine IR Design](#5-machine-ir-design)
6. [Instruction Selection](#6-instruction-selection)
7. [Liveness Analysis](#7-liveness-analysis)
8. [Graph-Coloring Register Allocation](#8-graph-coloring-register-allocation)
9. [Stack Frame Layout](#9-stack-frame-layout)
10. [Assembly Emission](#10-assembly-emission)
11. [Calling Convention & ABI](#11-calling-convention--abi)
12. [Built-in / Libc Integration](#12-built-in--libc-integration)
13. [File Structure](#13-file-structure)
14. [Implementation Order](#14-implementation-order)
15. [Open Questions & Decisions Log](#15-open-questions--decisions-log)

---

## 1. Overview

The codegen stage takes the post-optimization `IrModule` (after inline, SROA,
Mem2Reg, constant propagation, CFG simplification, DCE and conservative ADCE,
**with φ nodes still in SSA form**)
and produces RISC-V assembly text (`.s` file) that can be directly executed by the
REIMU simulator.

```
IrModule (SSA form with φ nodes, allocas for non-promoted locals)
  │
  ├─ Instruction Selection   (IR → MachineIR with virtual registers;
  │                            φ nodes lowered to register moves on edges;
  │                            local branch/address/div-rem optimizations)
  ├─ Liveness Analysis        (compute live ranges for virtual registers)
  ├─ Register Allocation      (graph-coloring: virtual → physical registers + spills)
  ├─ Frame Finalization        (compute stack layout, prologue/epilogue)
  ├─ Branch Relaxation         (expand out-of-range conditional branches)
  ├─ Fallthrough Jump Cleanup  (remove `j` to the next emitted block)
  └─ Assembly Emission         (MachineIR → .s text for REIMU)
```

### Input IR Characteristics (post-Mem2Reg)

- **φ nodes present** — Mem2Reg promotes allocas to SSA and inserts φ nodes.
  The instruction selector lowers them to register moves on predecessor edges
  (no alloca/store/load demotion), using parallel-copy sequentialization to
  handle swap/cycle cases correctly. Critical edges are split as needed.
- **Non-promoted allocas in the entry block** — aggregate locals that SROA cannot
  safely split and escaped addresses remain as alloca + load/store.
- **Aggregate returns via sret** — hidden pointer parameter at index 0.
- **Target model is RV32** — pointers are 4 bytes, `isize`/`usize` are `i32`.
- **Local instruction-selection optimizations** — dynamic GEP scales by cheap
  constants use shifts/adds instead of `mul`; compare values used only by a
  branch lower directly to RISC-V compare-branch instructions; unsigned div/rem
  by powers of two and signed div/rem by one avoid hardware divide. Explicit
  fallthrough jumps are kept until after register allocation so liveness sees
  both CFG edges, then removed by fallthrough cleanup.

---

## 2. Target Platform: REIMU

REIMU is a RISC-V Easy sIMUlator. Key constraints that shape our codegen:

### Supported ISA

- **RV32I** base integer instruction set — fully supported (user mode only).
- **RV32M** multiply/divide extension — `mul`, `mulh`, `mulhsu`, `mulhu`, `div`, `divu`, `rem`, `remu`.
- **NOT supported**: `fence`, `fence.i`, `ecall`, `ebreak`, CSR instructions, floating-point.

### Assembly Syntax

- GNU-style RISC-V assembly.
- Register names: ABI names (`a0`, `sp`, `s0`, `t0`, etc.) and numeric (`x0`–`x31`). We emit ABI names.
- Labels: `[a-zA-Z0-9_.@$]`, terminated by `:`. **No local labels** (no `1:` / `1b` syntax).
- Directives: `.text`, `.data`, `.rodata`, `.bss`, `.align n` (2^n bytes), `.globl`, `.word`, `.byte`, `.half`, `.asciz`/`.string`, `.zero`.
- Relocations: `%hi(sym)`, `%lo(sym)`, `%pcrel_hi(sym)`, `%pcrel_lo(sym)`.
- Pseudo-instructions: `li`, `la`, `mv`, `neg`, `not`, `seqz`, `snez`, `j`, `call`, `tail`, `ret`, `nop`, various `b*z` branch pseudo-ops.

### I/O and Libc

- **No syscalls** — no `ecall`/`ebreak`.
- I/O via built-in libc: `printf`, `scanf`, `puts`, `putchar`, `getchar`, `sprintf`, `sscanf`.
- Memory: `malloc`, `calloc`, `realloc`, `free` (note: `free` is a no-op).
- Memory ops: `memset`, `memcmp`, `memcpy`, `memmove`.
- String ops: `strcpy`, `strlen`, `strcat`, `strcmp`.
- These are called with `call <func>` — they follow standard RISC-V calling convention.
- `printf`/`scanf` support limited format specifiers: `%d`, `%u`, `%s`, `%c`, `%x`, `%p`, `%%`.

### Memory Layout

```
0x10000          Text section start (libc stubs at 0x10000–0x10047)
                 User .text follows
                 .data / .rodata / .bss (page-aligned)
                 Heap (grows upward via sbrk)
  ...
0x10000000       Stack bottom (grows downward)
0x20000000       Stack top (initial sp)
```

### Performance Model (Instruction Weights)

| Category   | Weight | Instructions                                   |
|------------|--------|------------------------------------------------|
| Arith      | 1      | `add`, `sub`, `addi`, etc.                     |
| Upper      | 1      | `lui`, `auipc`                                 |
| Compare    | 1      | `slt`, `sltu`, `slti`, `sltiu`                 |
| Shift      | 1      | `sll`, `srl`, `sra`, `slli`, `srli`, `srai`   |
| Bitwise    | 1      | `and`, `or`, `xor`, `andi`, `ori`, `xori`      |
| Multiply   | 4      | `mul`, `mulh`, `mulhsu`, `mulhu`               |
| Divide     | 20     | `div`, `divu`, `rem`, `remu`                   |
| **Branch** | **10** | `beq`, `bne`, `blt`, `bge`, `bltu`, `bgeu`    |
| **Load**   | **64** | `lb`, `lh`, `lw`, `lbu`, `lhu`                |
| **Store**  | **64** | `sb`, `sh`, `sw`                               |
| Jal        | 1      | `jal`                                          |
| Jalr       | 2      | `jalr`                                         |

**Key implication**: Loads/stores are 64× more expensive than ALU. Register allocation quality matters enormously. Minimizing memory traffic is the single most impactful optimization.

### Calling Convention Enforcement

REIMU's debugger mode strictly checks:
- `ret` must be exactly `jalr zero, 0(ra)`.
- `sp` must be restored to its original value before returning.
- Caller-saved registers (`t0–t6`, `a1–a7`) are **poisoned** with `0xDEADBEEF` after libc calls.

### Program Entry and Exit

- Entry point: the `main` symbol (must be `.globl`).
- `ra` is initialized to `0x4` (sentinel). When `main` returns via `ret`, `pc` becomes `0x4` and the simulator exits.
- `sp` is initialized to `0x20000000` (top of memory).

### Default Input Files

REIMU by default assembles `test.s` and `builtin.s` in the current directory. Our compiler should output a single `test.s`. The `builtin.s` can be empty or omitted if we rely solely on REIMU's built-in libc.

---

## 3. Pipeline Architecture

```
┌──────────────────────────────────────────────────────────────┐
│  Existing Pipeline                                           │
│  .rx → Preprocess → Lex → Parse → Semantic → IrBackend      │
│        (inline → mem2reg → constprop → CFG simplify          │
│         → DCE/ADCE; φ kept)                                  │
└────────────────────────┬─────────────────────────────────────┘
                         │ IrModule
                         ▼
┌──────────────────────────────────────────────────────────────┐
│  New Codegen Pipeline (backend/codegen/)                     │
│                                                              │
│  ┌─────────────────────┐                                     │
│  │ InstructionSelector  │  IR → RvMachineFunction             │
│  │                     │  (virtual registers, stack slots;    │
│  │                     │   φ → mv on predecessor edges)       │
│  └──────────┬──────────┘                                     │
│             ▼                                                │
│  ┌─────────────────────┐                                     │
│  │ LivenessAnalysis     │  Compute live-in/live-out per block │
│  │                     │  Build live intervals                │
│  └──────────┬──────────┘                                     │
│             ▼                                                │
│  ┌─────────────────────┐                                     │
│  │ GraphColorRegAlloc   │  Chaitin-Briggs with coalescing     │
│  │                     │  virtual → physical + spill code     │
│  └──────────┬──────────┘                                     │
│             ▼                                                │
│  ┌─────────────────────┐                                     │
│  │ FrameLayout          │  Compute final frame size           │
│  │                     │  Insert prologue/epilogue            │
│  └──────────┬──────────┘                                     │
│             ▼                                                │
│  ┌─────────────────────┐                                     │
│  │ BranchRelaxation     │  Keep conditional branches in range │
│  └──────────┬──────────┘                                     │
│             ▼                                                │
│  ┌─────────────────────┐                                     │
│  │ FallthroughCleanup   │  Drop `j` to next emitted block     │
│  └──────────┬──────────┘                                     │
│             ▼                                                │
│  ┌─────────────────────┐                                     │
│  │ AsmEmitter           │  Render to .s text                  │
│  └──────────┬──────────┘                                     │
│             ▼                                                │
│  Assembly text output (.s)                                   │
└──────────────────────────────────────────────────────────────┘
```

---

## 4. RISC-V Register Model

### Physical Registers (32 integer registers, RV32I)

| Register | ABI Name | Number | Role                        | Allocatable? |
|----------|----------|--------|-----------------------------|-------------|
| x0       | zero     | 0      | Hardwired zero              | No          |
| x1       | ra       | 1      | Return address              | No (special)|
| x2       | sp       | 2      | Stack pointer               | No          |
| x3       | gp       | 3      | Global pointer              | No          |
| x4       | tp       | 4      | Thread pointer              | No          |
| x5      | t0       | 5      | Frame-layout scratch        | No          |
| x6–x7   | t1–t2    | 6–7    | Temporaries (caller-saved)  | Yes         |
| x8       | s0/fp    | 8      | Saved / frame pointer       | Yes (callee)|
| x9       | s1       | 9      | Saved (callee-saved)        | Yes (callee)|
| x10–x11 | a0–a1    | 10–11  | Args / return (caller-saved)| Yes         |
| x12–x17 | a2–a7    | 12–17  | Arguments (caller-saved)    | Yes         |
| x18–x27 | s2–s11   | 18–27  | Saved (callee-saved)        | Yes (callee)|
| x28–x31 | t3–t6    | 28–31  | Temporaries (caller-saved)  | Yes         |

### Register Classes

- **Caller-saved** (15): `t0–t2`, `a0–a7`, `t3–t6` — clobbered across calls.
- **Callee-saved** (12): `s0–s11` — must be saved/restored by callee if used.
- **Total allocatable** (26): `t1–t6`, `a0–a7`, `s0–s11`. **K = 26** for graph coloring.
- **Reserved** (6): `zero`, `ra`, `sp`, `gp`, `tp`, `t0`.
- **Frame scratch**: `t0` is intentionally not allocated, so frame-layout large-offset
  sequences can materialize stack addresses without clobbering allocated values.

### Virtual Registers

During instruction selection, we use unlimited virtual registers identified by integer IDs.
Each virtual register has an associated `width` (1 = byte, 4 = word), used during spill code generation.

### Pre-Coloring Constraints

Certain virtual registers are **pre-colored** (must be assigned a specific physical register):
- Function arguments: vregs for parameters 0–7 must map to `a0–a7`.
- Return value: the vreg holding the return value must map to `a0`.
- Call instructions: arguments placed in `a0–a7`, result read from `a0`.
- `call` clobbers all caller-saved registers.

---

## 5. Machine IR Design

### Operands (`RvOperand`)

```
sealed class RvOperand
├── Reg(vreg: Int)                    // Virtual register
├── PhysReg(reg: RvPhysReg)           // Pre-colored physical register
├── Imm(value: Int)                   // Immediate constant
├── Reloc(kind: RelocKind, symbol: String)  // %hi(sym), %lo(sym)
└── Label(name: String)               // Branch/call target label
```

### Machine Instructions (`RvInst`)

Model a subset of RV32IM sufficient for all IR instructions:

```
sealed class RvInst
│
├── RType(op, rd, rs1, rs2)           // add, sub, and, or, xor, sll, srl, sra,
│                                     // slt, sltu, mul, div, divu, rem, remu
├── IType(op, rd, rs1, imm)           // addi, andi, ori, xori, slti, sltiu,
│                                     // slli, srli, srai
├── Load(width, rd, base, offset)     // lb, lbu, lh, lhu, lw
├── Store(width, rs, base, offset)    // sb, sh, sw
├── Branch(cond, rs1, rs2, target)    // beq, bne, blt, bge, bltu, bgeu
├── Lui(rd, imm)                      // lui rd, imm
│
├── // Pseudo-instructions
├── Li(rd, value)                     // li rd, value
├── La(rd, symbol)                    // la rd, symbol
├── Mv(rd, rs)                        // mv rd, rs  (important for coalescing)
├── Call(target)                      // call target
├── Ret                               // ret
├── J(target)                         // j target
├── Seqz(rd, rs)                      // seqz rd, rs
├── Snez(rd, rs)                      // snez rd, rs
├── Neg(rd, rs)                       // neg rd, rs
└── Not(rd, rs)                       // not rd, rs
```

### Machine Basic Block (`RvMachineBlock`)

```kotlin
class RvMachineBlock(val label: String) {
    val instructions: MutableList<RvInst> = mutableListOf()
    val successors: MutableList<RvMachineBlock> = mutableListOf()
    val predecessors: MutableList<RvMachineBlock> = mutableListOf()
}
```

### Machine Function (`RvMachineFunction`)

```kotlin
class RvMachineFunction(val name: String) {
    val blocks: MutableList<RvMachineBlock> = mutableListOf()
    val stackSlots: MutableList<StackSlotInfo> = mutableListOf()
    var frameSize: Int = 0
    val usedCalleeSaved: MutableSet<RvPhysReg> = mutableSetOf()
    val params: List<RvOperand> = mutableListOf()
    val hasCalls: Boolean = false  // whether this function calls others
}

data class StackSlotInfo(
    val irName: String,      // original IR alloca name
    val size: Int,           // byte size
    val alignment: Int,      // alignment requirement
    var offset: Int = 0,     // filled during frame finalization (relative to sp)
)
```

---

## 6. Instruction Selection

Walk each `IrFunction` and produce an `RvMachineFunction`. This phase uses unlimited
virtual registers — no register pressure concerns yet.

### IR → RV32 Lowering Table

| IR Instruction       | RISC-V Lowering                                                |
|----------------------|----------------------------------------------------------------|
| `IrAlloca`           | Allocate `StackSlotInfo`; pointer = `sp + offset` (resolved later) |
| `IrLoad(i32, ptr)`  | `lw rd, offset(base)`                                         |
| `IrLoad(i8, ptr)`   | `lbu rd, offset(base)` (char is unsigned)                     |
| `IrLoad(i1, ptr)`   | `lbu rd, offset(base)`, then `andi rd, rd, 1`                 |
| `IrStore(i32)`       | `sw rs, offset(base)`                                         |
| `IrStore(i8)`        | `sb rs, offset(base)`                                         |
| `IrStore(i1)`        | `andi rs, rs, 1`, then `sb rs, offset(base)`                  |
| `IrBinary ADD`       | `add rd, rs1, rs2`  (or `addi` if one operand is constant)    |
| `IrBinary SUB`       | `sub rd, rs1, rs2`                                            |
| `IrBinary MUL`       | `mul rd, rs1, rs2`                                            |
| `IrBinary SDIV`      | `div rd, rs1, rs2`                                            |
| `IrBinary UDIV`      | `divu rd, rs1, rs2`                                           |
| `IrBinary SREM`      | `rem rd, rs1, rs2`                                            |
| `IrBinary UREM`      | `remu rd, rs1, rs2`                                           |
| `IrBinary AND`       | `and rd, rs1, rs2`                                            |
| `IrBinary OR`        | `or rd, rs1, rs2`                                             |
| `IrBinary XOR`       | `xor rd, rs1, rs2`                                            |
| `IrBinary SHL`       | `sll rd, rs1, rs2`                                            |
| `IrBinary ASHR`      | `sra rd, rs1, rs2`                                            |
| `IrBinary LSHR`      | `srl rd, rs1, rs2`                                            |
| `IrCmp EQ`           | `sub t, rs1, rs2` → `seqz rd, t`                              |
| `IrCmp NE`           | `sub t, rs1, rs2` → `snez rd, t`                              |
| `IrCmp SLT`          | `slt rd, rs1, rs2`                                            |
| `IrCmp SLE`          | `slt rd, rs2, rs1` → `xori rd, rd, 1`  (NOT(rs2 < rs1))      |
| `IrCmp SGT`          | `slt rd, rs2, rs1`                                            |
| `IrCmp SGE`          | `slt rd, rs1, rs2` → `xori rd, rd, 1`                         |
| `IrCmp ULT`          | `sltu rd, rs1, rs2`                                           |
| `IrCmp ULE`          | `sltu rd, rs2, rs1` → `xori rd, rd, 1`                        |
| `IrCmp UGT`          | `sltu rd, rs2, rs1`                                           |
| `IrCmp UGE`          | `sltu rd, rs1, rs2` → `xori rd, rd, 1`                        |
| `IrUnary NEG`        | `neg rd, rs`  (pseudo for `sub rd, zero, rs`)                  |
| `IrUnary NOT(i1)`    | `xori rd, rs, 1`                                              |
| `IrUnary NOT(i32)`   | `not rd, rs`  (pseudo for `xori rd, rs, -1`)                  |
| `IrBranch`           | `bne cond, zero, trueLabel` + `j falseLabel`                  |
| `IrJump`             | `j target`                                                    |
| `IrReturn(void)`     | `ret`                                                         |
| `IrReturn(i32)`      | `mv a0, rs` → `ret`                                           |
| `IrCall`             | Move args to `a0–a7`, spill overflow to stack, `call target`, read `a0` |
| `IrGep (struct)`     | Constant offset: `addi rd, base, offset`                       |
| `IrGep (array)`      | Dynamic: `li t, elemSize` → `mul t, idx, t` → `add rd, base, t` |
| `IrCast ZEXT(i1→i32)`| `andi rd, rs, 1`                                              |
| `IrCast ZEXT(i8→i32)`| `andi rd, rs, 0xFF`                                           |
| `IrCast SEXT(i8→i32)`| `slli rd, rs, 24` → `srai rd, rd, 24`                         |
| `IrCast SEXT(i1→i32)`| `slli rd, rs, 31` → `srai rd, rd, 31`                         |
| `IrCast TRUNC(→i8)`  | `andi rd, rs, 0xFF`                                           |
| `IrCast TRUNC(→i1)`  | `andi rd, rs, 1`                                              |
| `IrCast BITCAST`     | `mv rd, rs` (no-op on RV32, everything is 32-bit reg)          |
| `IrCast PTRTOINT`    | `mv rd, rs`                                                   |
| `IrCast INTTOPTR`    | `mv rd, rs`                                                   |
| `IrConst`            | `li rd, value`                                                |
| `IrPhi`              | Register moves on predecessor edges (see below)               |

### φ-Node Lowering (Register Moves)

φ nodes survive into the instruction selector in SSA form. Instead of demoting them
to memory (the old `PhiLoweringPass` approach — alloca + store on edges + load at
merge), the instruction selector lowers each φ to **register-to-register moves**
emitted on predecessor edges. This avoids all memory traffic, which is critical on
REIMU where loads/stores cost 64× more than ALU operations.

**Algorithm (per function):**

1. **Collect φ destinations.** For each `IrPhi` in a block, allocate a fresh virtual
   register as the φ result (the vreg that replaces all uses of `%phi_name`).

2. **Build parallel-copy sets.** For each predecessor edge `pred → succ`, gather all
   `(dst_vreg ← src_operand)` pairs from the φ nodes in `succ` whose incoming list
   references `pred`. This set of assignments must execute **simultaneously** (the
   parallel-copy semantics of SSA φ).

3. **Sequentialize parallel copies.** Convert each parallel-copy set into an ordered
   sequence of `mv` / `li` instructions that can execute sequentially without
   corrupting values. The algorithm:
   - Build a dependency graph: an edge `a → b` means "copy b needs the old value of a
     as its source, but copy a overwrites a".
   - Emit non-conflicting copies first (those whose destination is not a source of
     any remaining copy) — these are safe to execute immediately.
   - For **cycles** (e.g., swap: `a ← b, b ← a`), break the cycle by introducing a
     fresh temporary virtual register: `tmp ← a; a ← b; b ← tmp`.
   - Constants (`li`) and self-copies (`a ← a`) are trivially safe and emitted
     directly / elided.

4. **Emit moves on predecessor edges.** Insert the sequentialized move instructions
   into the predecessor block, just before its terminator.

5. **Split critical edges.** When a predecessor has multiple successors (conditional
   branch) and the target block has φ nodes, the edge is critical. The selector
   splits it by inserting a new trampoline block that contains the φ moves and an
   unconditional jump to the original target. The branch in the predecessor is
   rewritten to target the trampoline.

6. **Debug assertion.** The selector asserts `require(!isAggregate(phi.type))` for
   every φ encountered — aggregates use destination-passing and should never appear
   as φ values in the current IR.

**Why this works well with register allocation:**

The `mv` instructions emitted by φ lowering are marked as move instructions
(`RvInst.Mv.isMove() == true`). The Chaitin-Briggs register allocator with
iterated register coalescing can **coalesce** the source and destination of a
move into the same physical register, eliminating the move entirely. In the
common case (no cycle, no spill pressure), most φ-related moves are coalesced
away, resulting in zero overhead — far better than the 128-weight round-trip
(store + load) of the memory-based approach.

### Large Immediates

RISC-V I-type instructions encode 12-bit signed immediates (−2048 to +2047).
For larger constants, `li` (pseudo-instruction) is used — REIMU's linker handles
expanding `li` to `lui + addi` automatically. We emit `li` pseudo-instructions and
let the assembler handle it.

### Aggregate Operations (memcpy)

The IR uses scalar leaf copies for SROA-safe aggregate copies and `llvm.memcpy`
for large aggregate copies. We lower memcpy to `call memcpy` since REIMU provides
a built-in `memcpy`. Arguments: `a0` = dest, `a1` = src, `a2` = size.

---

## 7. Liveness Analysis

Standard backward dataflow analysis on the machine-level CFG.

### Algorithm

```
For each block B (in reverse postorder):
    USE[B] = set of vregs read before being written in B
    DEF[B] = set of vregs written before being read in B

Repeat until convergence:
    For each block B (in reverse postorder):
        live_out[B] = ∪ { live_in[S] | S ∈ successors(B) }
        live_in[B]  = USE[B] ∪ (live_out[B] − DEF[B])
```

### Interference Detection

Two virtual registers **interfere** if one is live at the definition point of the other.
Specifically: after computing live sets, for each instruction that defines a vreg `d`,
every vreg in the live-out set at that point (excluding `d` itself for move-coalescing)
interferes with `d`.

---

## 8. Graph-Coloring Register Allocation

### Algorithm: Chaitin-Briggs with Iterated Register Coalescing

**K = 26** (number of allocatable physical registers; `t0` is reserved).

#### Phases (repeat until stable)

1. **BUILD**
   - Run liveness analysis.
   - Construct the interference graph: nodes = vregs, edges = interference.
   - Identify **move-related** nodes (source/dest of `mv` instructions).
   - Pre-color nodes with physical register constraints (args, return values).

2. **SIMPLIFY**
   - Repeatedly remove non-move-related nodes with degree < K.
   - Push removed nodes onto a **coloring stack**.

3. **COALESCE** (conservative, Briggs criterion)
   - For a move `rd ← rs`: if merging `rd` and `rs` produces a node with < K
     high-degree neighbors, coalesce them (merge into one node, eliminate the move).
   - This reduces copy overhead significantly.

4. **FREEZE**
   - If no simplification or coalescing is possible, **freeze** a low-degree
     move-related node (give up on coalescing it, allow it to be simplified).

5. **POTENTIAL SPILL**
   - If no freeze candidates exist, pick a high-degree node as a spill candidate.
   - Push it onto the coloring stack (optimistic spilling).
   - Heuristic: prefer nodes with long live ranges and many uses far apart.

6. **SELECT** (pop stack, assign colors)
   - Pop nodes from the stack one by one.
   - For each node, assign a physical register not used by any already-colored neighbor.
   - Prefer caller-saved registers for short-lived values (avoids save/restore cost).
   - Prefer callee-saved registers for values live across calls (only one save/restore).
   - If no color is available → **ACTUAL SPILL**.

7. **SPILL AND RETRY**
   - For each actually-spilled vreg:
     - Allocate a stack slot.
     - Insert `sw` after each definition.
     - Insert `lw` before each use.
     - Create fresh tiny-lived vregs for the loaded values.
   - **Restart from BUILD.** Convergence is guaranteed because spilled vregs have
     very short live ranges (single instruction between load and use).

### Pre-Coloring

- Vregs for function parameters → pre-colored to `a0–a7`.
- Vregs for return values → pre-colored to `a0`.
- `call` instruction → adds interference between all caller-saved regs and all
  vregs live across the call.

### Coalescing Priority

Given REIMU's performance model where loads/stores cost 64× more than ALU,
aggressive coalescing of `mv` instructions is highly valuable.

---

## 9. Stack Frame Layout

### Frame Structure (grows downward)

```
    High addresses
    ┌──────────────────────┐
    │   Caller's frame     │
    ├──────────────────────┤ ← old sp (before prologue)
    │   Saved ra           │  sp + frameSize - 4    (only if function makes calls)
    │   Saved s0           │  sp + frameSize - 8    (only if s0 is used)
    │   Saved s1           │  sp + frameSize - 12   (only if s1 is used)
    │   ...                │
    │   Saved sN           │
    ├──────────────────────┤
    │   Spill slots        │  (generated by register allocator)
    ├──────────────────────┤
    │   Local allocas      │  (from IrAlloca instructions)
    ├──────────────────────┤
    │   Outgoing args      │  (arguments 8+ that don't fit in a0–a7)
    ├──────────────────────┤ ← new sp (after prologue, 16-byte aligned)
    Low addresses
```

### Prologue

Following the REIMU test-case convention:

```asm
    sw   ra, -4(sp)           # save ra above frame (only if function calls)
    addi sp, sp, -<frameSize> # allocate frame
    # save callee-saved registers within the frame
    sw   s0, <offset>(sp)
    sw   s1, <offset>(sp)
    ...
```

Note: REIMU test cases store `ra` at `sp - 4` *before* decrementing `sp`. This is the
convention we follow.

### Epilogue

```asm
    # restore callee-saved registers
    lw   s0, <offset>(sp)
    lw   s1, <offset>(sp)
    ...
    addi sp, sp, <frameSize>  # deallocate frame
    lw   ra, -4(sp)           # restore ra
    ret
```

### Frame Size Computation

```
frameSize = align16(
    savedCalleeSavedRegsSize
  + spillSlotsTotalSize
  + localAllocasTotalSize
  + outgoingArgsOverflowSize
)
```

`ra` is stored above the frame at `old_sp - 4`, so it does NOT contribute to `frameSize`.
All offsets within the frame are `sp`-relative.

### Leaf Function Optimization

If a function makes no calls (`hasCalls == false`):
- `ra` does not need to be saved/restored.
- No caller-saved registers are clobbered by callees, so callee-saved registers may not be needed.

---

## 10. Assembly Emission

### Output Structure

```asm
    .text
    .align  2

# --- Global data (strings, constants) ---
    .data
.str.fmt.d:
    .string "%d"
.str.fmt.d_ln:
    .string "%d\n"

# --- Read-only data ---
    .rodata
# (string constants, etc.)

# --- Code ---
    .text
    .align  2
    .globl  main
main:
    # prologue
    ...
    # body
    ...
    # epilogue
    ...
    ret

    .globl  someFunction.
someFunction.:
    ...
```

### Label Naming

- `main` — the entry function (no mangling).
- Other functions: use the IR mangled name directly (e.g., `exit.`, `printInt.`, `OwnerName.methodName.`).
  These contain `.` characters which are valid in REIMU labels.
- Basic block labels: `<funcName>.<blockLabel>` to avoid collisions across functions.

### Built-in Function Calls

The IR currently emits built-in wrappers (`@exit.`, `@printInt.`, `@printlnInt.`, `@getInt.`)
as IR-level functions. For codegen, we translate these directly to REIMU libc calls:

| IR Function     | REIMU Call                                                   |
|----------------|--------------------------------------------------------------|
| `@exit.`        | `j __exit` — jumps to the exit label at the end of `main`, which performs `main`'s epilogue and `ret`, causing the simulator to halt. |
| `@printInt.`    | `mv a1, <arg>` → `la a0, .str.fmt.d` → `call printf`       |
| `@printlnInt.`  | `mv a1, <arg>` → `la a0, .str.fmt.d_ln` → `call printf`    |
| `@getInt.`      | `addi a1, sp, <slot>` → `la a0, .str.fmt.d` → `call scanf` → `lw a0, <slot>(sp)` |

---

## 11. Calling Convention & ABI

### Standard RISC-V ILP32 Calling Convention

- **Arguments**: `a0–a7` (up to 8 word-sized arguments). Overflow goes on the stack.
- **Return value**: `a0` (word-sized). Aggregates via sret pointer in `a0`.
- **Caller-saved**: `ra`, `t0–t6`, `a0–a7` — caller must assume these are destroyed.
- **Callee-saved**: `sp`, `s0–s11` — callee must preserve these.
- **Stack pointer**: Must be restored before `ret`. Should be 16-byte aligned at call sites.

### Sret (Struct Return) Convention

For functions returning aggregates (structs/arrays):
1. Caller allocates space on its own stack.
2. Caller passes pointer to that space as the first argument (`a0`).
3. Callee writes the return value through that pointer.
4. Callee returns `void`.
5. Caller reads the result from its stack after the call returns.

---

## 12. Built-in / Libc Integration

### Strategy

Instead of emitting wrapper functions as assembly, we call REIMU's built-in libc directly.
During instruction selection, when we encounter a call to an IR built-in function, we
replace it with the corresponding libc call sequence.

### Format Strings as Data

We emit format strings in the `.data` section:

```asm
    .data
.str.fmt.d:
    .string "%d"
.str.fmt.d_ln:
    .string "%d\n"
```

And reference them with `la` when calling `printf`/`scanf`.

### Mapping

| IR Built-in       | Libc Call | Notes |
|--------------------|-----------|-------|
| `@exit.(i32)`      | `j __exit` | Jumps to `main`'s exit label; epilogue + `ret` halts the simulator |
| `@printInt.(i32)`  | `printf("%d", arg)` | `la a0, fmt; mv a1, arg; call printf` |
| `@printlnInt.(i32)`| `printf("%d\n", arg)` | Same pattern with `\n` in format |
| `@getInt.()`       | `scanf("%d", &buf)` | Allocate a stack slot for the buffer |
| `@llvm.memcpy.*`   | `call memcpy` | `a0`=dst, `a1`=src, `a2`=len |

---

## 13. File Structure

```
backend/codegen/
├── riscv/
│   ├── RvRegister.kt           # Physical registers, ABI classifications, operand types
│   ├── RvInstruction.kt        # Machine instruction sealed hierarchy
│   └── RvMachineFunction.kt    # Machine function/block containers, stack slot info
├── InstructionSelector.kt      # IR → Machine IR lowering
├── LivenessAnalysis.kt         # Backward dataflow liveness computation
├── InterferenceGraph.kt        # Adjacency-set interference graph
├── GraphColorRegAlloc.kt       # Chaitin-Briggs register allocator
├── FrameLayout.kt              # Stack frame computation, prologue/epilogue insertion
├── BranchRelaxation.kt         # Expands far conditional branches
├── FallthroughJumpElimination.kt # Removes jumps to the next emitted block
├── AsmEmitter.kt               # Machine IR → assembly text rendering
└── RiscVCodegen.kt             # Top-level orchestrator
```

---

## 14. Implementation Order

| Phase | Components | Status | Testability |
|-------|-----------|--------|-------------|
| **1** | `RvRegister.kt`, `RvInstruction.kt`, `RvMachineFunction.kt` | ✅ Done | Unit tests: operand rendering, instruction rendering |
| **2** | `InstructionSelector.kt` (includes φ→mv lowering with parallel-copy sequentialization; `PhiLoweringPass` removed) | ✅ Done | Print machine IR via `debugRender()`, verify by inspection |
| **3** | `LivenessAnalysis.kt` | ✅ Done | 16 unit tests on small CFGs with known liveness |
| **4** | `InterferenceGraph.kt` + `GraphColorRegAlloc.kt` (Chaitin-Briggs with iterated register coalescing; `ParallelCopyResolver.kt` removed — inline resolver in isel suffices) | ✅ Done | 20 unit tests: coloring, coalescing, spilling, call clobbers, pre-coloring |
| **5** | `FrameLayout.kt` | ✅ Done | Unit tests cover frame size, offsets, prologue/epilogue, overflow args, and large offsets |
| **6** | `BranchRelaxation.kt` + `FallthroughJumpElimination.kt` | ✅ Done | REIMU batch validates branch range handling and jump cleanup |
| **7** | `AsmEmitter.kt` | ✅ Done | Emits single-file GNU-style assembly with used builtin wrappers |
| **8** | `RiscVCodegen.kt` + `Main.kt` integration | ✅ Done | `.rx` → `.s` is the default CLI output; REIMU batch scripts validate IR-1 |

---

## 15. Open Questions & Decisions Log

| # | Question | Status | Decision |
|---|----------|--------|----------|
| 1 | Frame pointer: reserve `s0` as FP or use SP-relative only? | **Decided** | SP-relative only. `s0` stays allocatable. |
| 2 | Target ISA: strictly `rv32im` or include Zba/Zbb bitmanip? | **Decided** | `rv32im` only (REIMU doesn't support bitmanip). |
| 3 | Assembler syntax: GNU style? | **Decided** | Yes, GNU-style `.s` for REIMU. |
| 4 | Package layout: `backend.codegen` + `backend.codegen.riscv`? | **Decided** | Yes. `backend.codegen.riscv` for RV-specific data types (registers, instructions, machine function). `backend.codegen` for passes (isel, liveness, regalloc, frame layout, asm emitter, orchestrator). |
| 5 | `builtin.s`: emit a separate file or put everything in `test.s`? | **Decided** | Single file. All code, data, and format strings go into one `.s` output. |
| 6 | How to handle `@exit.`? Return from main? Call some runtime exit? | **Decided** | `exit()` terminates the whole program. Emit a global `__exit` label at the end of `main` (epilogue + `ret`). Any call to `exit()` from any function compiles to `j __exit`. Since REIMU's `main` has `ra = 0x4`, this causes the simulator to halt. |
| 7 | Local labels: REIMU doesn't support `1:` / `1b` syntax. Use dot-prefixed names? | **Decided** | Use `<funcName>.<blockLabel>` naming. |
| 8 | Large stack frames: how to handle offsets > 2047 (12-bit immediate limit)? | **Decided** | FrameLayout materializes the offset with `li`, adds it to `sp`, and uses `0(temp)`. `t0` is reserved for this purpose. |
| 9 | φ-node lowering: memory-based (alloca/store/load) or register moves? | **Decided** | Register moves. `PhiLoweringPass` (memory demotion) removed. The instruction selector collects φ incoming values as parallel-copy sets per predecessor edge, sequentializes them (breaking cycles with temporary vregs), and emits `mv`/`li` before the predecessor's terminator. Critical edges are split with trampoline blocks. This avoids 128-weight memory round-trips on REIMU; the register allocator can coalesce most moves to zero cost. |
