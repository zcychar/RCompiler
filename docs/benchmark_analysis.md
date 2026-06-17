# REIMU Benchmark & Cycle Calculation Analysis

Based on the `REIMU` manual and the test scripts (`test_asm_batch.bash`, `test_asm.bash`), here is how the benchmark tests are processed and how cycles are calculated.

## 1. How REIMU Calculates Cycles (Weights)

REIMU doesn't just count the number of instructions executed. It assigns a **weight** (or penalty) to different classes of instructions when calculating total cycles. These weights represent the typical latency of these operations on real hardware.

The weights are defined in `REIMU/include/config/counter.h` and documented in the manual:

- **Heavy Operations:**
  - `Load` (`lb`, `lh`, `lw`, etc.): **64 cycles**
  - `Store` (`sb`, `sh`, `sw`): **64 cycles**
  - `Divide` (`div`, `rem`, etc.): **20 cycles**
  - `Branch` (`beq`, `bne`, etc.): **10 cycles**
- **Medium Operations:**
  - `Multiply` (`mul` etc.): **4 cycles**
  - `Jalr` (indirect jump, like a return): **2 cycles**
- **Light Operations:**
  - `Arith` (`add`, `sub`), `Compare` (`slt`), `Shift`, `Bitwise`, `Jal`: **1 cycle**

### Why Memory Traffic Dominates Optimization Results
This weight system explains why an optimization can regress even when it removes
calls or lowers raw instruction count. Extra `Load` and `Store` instructions cost
64 cycles each, so IR optimizations must be validated with REIMU stats rather
than static instruction counts alone. The current pipeline keeps Mem2Reg-created
φ nodes in SSA until codegen and lowers them to register moves to avoid
alloca/store/load demotion.

## 2. How Benchmark Tests are Processed

The benchmarking pipeline works in two layers:

### The Inner Script: `test_asm.bash`
This script executes a single test case (e.g., `comprehensive1`).
1. **Compile**: It runs `make run-asm < testcase.rx > test.s` to use the compiler to generate RISC-V assembly.
2. **Execute**: It runs `reimu -f=test.s -i=test.in -o=test.out`. REIMU executes the generated assembly and automatically handles the built-in C library functions (like `printf` or `malloc`) internally without needing clang or `builtin.c`.
3. **Capture Stats**: The simulator writes its output to stdout/stderr. If `ASM_SHOW_STATS=1` or `ASM_STATS_FILE` is set, the script extracts the stats printed by REIMU at the end of execution (which includes "Total cycles" and instruction counts per class like `# mem`, `# jump`, `# branch`).
4. **diff**: It compares `test.out` against the reference `test.ans`.

### The Outer Script: `test_asm_batch.bash`
This script runs a batch of tests (like `--range 1 50`) and aggregates the results.

**Preparation (Critical):** Before running this script, you must manually build and position the compiler fat jar:
1. Run `./gradlew shadowJar`
2. Move the output fat jar from `build/libs` to the repository root and rename it if needed (`cp build/libs/RCompiler-1.0-SNAPSHOT-all.jar RCompiler-1.0-SNAPSHOT-all.jar`).

Once prepared, the batch script:
1. Iterates over all designated test cases, running `test_asm.bash` for each one.
2. It parses the stats files generated for each successful test run, extracting `Total cycles` and counts like `# mem`, `# jump`, etc., from each test case.
3. It keeps a running sum of `TOTAL_CYCLES` and total counts for each instruction category (`STAT_SUMS`).
4. Finally, it uses `print_stats_summary()` to output the aggregated benchmark scores across the entire batch (average cycles per passed case, total loads/stores, etc.). 

This batch summary makes it very easy to verify optimizations at scale by directly comparing total cycles before and after a change.

## 4. Current Optimization Validation Policy

Correctness is the first gate. For the current backend optimization phase, the
primary correctness and performance check is:

```bash
./gradlew shadowJar
cp build/libs/RCompiler-1.0-SNAPSHOT-all.jar RCompiler-1.0-SNAPSHOT-all.jar
./test_asm_batch.bash --stats-only --range 1 50
```

Unit tests remain useful focused checks. `allCompilerTests` can be run for broad
translation coverage, but it is not the main optimization gate because it does
not measure REIMU cycle behavior for `comprehensive1..50`.

For optimization changes, report the aggregate `comprehensive1..50` REIMU
totals, including total cycles and memory-operation counts. Individual cases may
move in different directions, so the report should call out major changes rather
than relying only on a single static inspection.

To intentionally compare with optimization disabled:

```bash
COMPILER_FLAGS=--no-opt ./test_asm_batch.bash --stats-only --range 1 50
```

Latest validated default run:

- Result: `pass=50 fail=0`
- Total cycles: `28,503,304,519`
- Parsed instructions: `74,490`
- Instruction counts: `simple=992,796,794`, `mul=11,492,123`,
  `div=4,435,514`, `mem=405,371,596`, `branch=133,306,939`,
  `jump=80,599,817`, `jalr=856,042`, `libcIO=1,631`,
  `libcOp=14,039`

LLVM comparison feasibility check:

- `llc -mtriple=riscv32 -mattr=+m -O2` can compile the compiler's emitted
  optimized LLVM-style IR for `comprehensive1..50`.
- A comparison run passed all 50 cases and measured `27,401,287,782` total
  cycles with `55,839` parsed instructions. The largest remaining clues are
  loop/address induction, direct memory-address folding, and stronger
  div/rem-by-constant lowering.
- Direct GCC comparison from the compiler's IR is not direct because GCC does
  not consume LLVM IR. A GCC comparison would need a separate C lowering path or
  a source-level C equivalent benchmark.
