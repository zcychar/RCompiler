# Testing Workflow

This project uses three validation layers. For the current backend optimization
work, the primary validation target is the IR-1 ASM benchmark over
`comprehensive1..50`: cases must compile, run correctly, and report useful
REIMU cycle totals. Unit and generated stage tests are still useful focused
checks, but `allCompilerTests` is optional context rather than the main gate.

## Unit Tests

```bash
./gradlew test
```

Use focused filters during backend work:

```bash
./gradlew test --tests 'backend.*'
./gradlew test --tests backend.ir.OptimizationPassesTest
```

## Generated Compiler-Stage Tests

Gradle scans testcase roots and creates one task per stage and per case:

- Upstream root: `src/main/resources/RCompiler-Testcases`
- Local root: `src/main/resources/RCompiler-Local-Testcases`

Common commands:

```bash
./gradlew allCompilerTests
./gradlew localTests
./gradlew IR1
./gradlew Semantic1
./gradlew Semantic2
./gradlew localFrontend
```

These tasks are useful for translation coverage and focused regressions. They do
not replace the ASM benchmark when evaluating backend optimizations because they
do not report REIMU cycle behavior for `comprehensive1..50`.

Debug output:

```bash
./gradlew IR1 -PcompilerDebug=true
./gradlew IR1 -PcompilerDebugStages=ir,codegen
```

## IR-1 ASM Benchmark

The REIMU benchmark requires the runnable fat jar at the repository root because
`make run-asm` executes `RCompiler-1.0-SNAPSHOT-all.jar` directly.

```bash
./gradlew shadowJar
cp build/libs/RCompiler-1.0-SNAPSHOT-all.jar RCompiler-1.0-SNAPSHOT-all.jar
./test_asm_batch.bash --stats-only --range 1 50
```

To compare another compiler mode without editing scripts, pass flags through
`make`:

```bash
COMPILER_FLAGS=--no-opt ./test_asm_batch.bash --stats-only --range 1 50
```

`test_asm_batch.bash` compiles each IR-1 testcase to assembly, runs it with
REIMU, diffs the output, and aggregates simulator stats. Treat correctness and
performance separately: all cases must pass, and optimization reports should
include total cycles plus instruction-category counts, especially memory ops.
