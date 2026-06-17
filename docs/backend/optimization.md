# Backend Optimization Pipeline

The default optimized pipeline is enabled by `--opt=on` and disabled entirely by
`--no-opt`:

1. `InlinePass`
2. `SroaPass`
3. `Mem2RegPass`
4. `ConstantPropagationPass`
5. `CfgSimplificationPass`
6. `DeadCodeEliminationPass`
7. `AggressiveDeadCodeEliminationPass`
8. `CfgSimplificationPass`
9. Final `DeadCodeEliminationPass`

RISC-V codegen also applies local instruction-selection optimizations before
register allocation: dynamic GEP scaling by cheap constants uses shifts/adds
instead of `mul`, branch-only compares lower directly to RISC-V compare-branch
instructions, and conservative constant div/rem cases lower to cheap
instructions. It then runs `FallthroughJumpElimination` after branch relaxation
to remove explicit `j` instructions whose target is already the next emitted
block.

## Pass Behavior

- `InlinePass` runs at module scope before function passes. It skips recursive
  SCCs and inlines eligible callees bottom-up.
- `SroaPass` runs before Mem2Reg. It splits aggregate allocas when all derived
  pointer uses are constant-index GEPs ending at scalar or pointer leaves. Safe
  whole-aggregate load-to-store copies are first expanded into scalar leaf
  loads at the original load point and scalar stores at each copy destination,
  preserving the aggregate load snapshot. The pass rejects dynamic indices,
  pointer escapes, non-store aggregate-value uses, unsupported leaf types, and
  aggregates above the split-slot threshold.
- `Mem2RegPass` promotes non-escaping scalar and pointer allocas into SSA.
  Aggregate locals that SROA cannot prove safe remain in memory and are handled
  by destination-passing and aggregate-copy lowering.
- `ConstantPropagationPass` folds scalar binary/unary operations, comparisons,
  simple casts, same-constant phi nodes, and constant branches. It avoids
  unsafe folds such as division or remainder by zero and does not treat loads,
  stores, calls, or pointer memory effects as foldable.
- `CfgSimplificationPass` removes unreachable blocks, folds conditional
  branches whose true/false targets are identical, and bypasses empty jump-only
  blocks when phi predecessor rewrites are unambiguous.
- `DeadCodeEliminationPass` removes unused side-effect-free IR instructions.
  Calls, stores, and terminators are observable roots and are preserved.
- `AggressiveDeadCodeEliminationPass` is currently a conservative whole-function
  mark/sweep from observable roots. It removes dead pure dependency chains but
  intentionally does not rewrite non-constant control dependence. Full
  postdominator/control-dependence ADCE is deferred.
- RISC-V instruction selection keeps explicit fallthrough jumps until after
  register allocation so machine CFG and liveness remain correct. The
  post-relaxation fallthrough cleanup removes those jumps once CFG-sensitive
  phases are done.

## Validation Workflow

Correctness and performance are validated separately:

```bash
./gradlew test
./gradlew shadowJar
cp build/libs/RCompiler-1.0-SNAPSHOT-all.jar RCompiler-1.0-SNAPSHOT-all.jar
./test_asm_batch.bash --stats-only --range 1 50
```

`allCompilerTests` is optional context for generated translation coverage; it is
not the primary optimization gate. The main optimization result is correctness
plus aggregate REIMU cycle behavior for `comprehensive1..50`.

Use `COMPILER_FLAGS=--no-opt ./test_asm_batch.bash --stats-only --range 1 50`
for comparison runs that intentionally disable the whole IR optimization
pipeline.

The REIMU benchmark report should include total cycles, parsed instruction
count, and instruction categories. Memory operations are especially important
because REIMU weights each load/store at 64 cycles.

Latest validated default run:

- Command: `./test_asm_batch.bash --stats-only --range 1 50`
- Result: `pass=50 fail=0`
- Total cycles: `28,503,304,519`
- Parsed instructions: `74,490`
- Key counts: `mem=405,371,596`, `branch=133,306,939`,
  `jump=80,599,817`, `mul=11,492,123`, `div=4,435,514`

The local toolchain can use LLVM `llc` for backend-quality comparisons from the
compiler's emitted LLVM-style IR. Direct GCC comparison is not available from IR
because GCC does not consume LLVM IR, but `llc -mtriple=riscv32 -mattr=+m -O1`
and `-O2` can generate REIMU-runnable assembly for at least simple benchmark
cases.

## Review Notes

- IR phi nodes remain in SSA until instruction selection. They are lowered to
  register moves on predecessor edges, not demoted to memory.
- The RISC-V allocator has 26 colors because `t0` is reserved as a frame-layout
  scratch register for large stack offsets.
- The upstream `RCompiler-Testcases` tree is treated as read-only during
  optimization work; local cases should be added under `RCompiler-Local-Testcases`.
