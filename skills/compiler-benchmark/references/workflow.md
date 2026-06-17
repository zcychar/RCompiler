# Benchmark Workflow

This skill targets the benchmark flow used by this repository.

## Default benchmark set

- Stage: `src/main/resources/RCompiler-Testcases/IR-1`
- Cases: `comprehensive1..50`

## Benchmark command path

The raw benchmark flow is:

1. Run `./gradlew shadowJar`
2. Copy `build/libs/RCompiler-1.0-SNAPSHOT-all.jar` to repo root as `RCompiler-1.0-SNAPSHOT-all.jar`
3. Run `./test_asm_batch.bash --stats-only --stats-dir <dir> --range 1 50`

`test_asm_batch.bash` invokes `test_asm.bash` for each testcase. Each testcase:
- compiles the source via `make run-asm`
- runs the generated assembly with `reimu`
- diffs program output against expected output
- writes extracted REIMU stats when `--stats-dir` is used

## Stats format

Each per-case `.stats` file contains entries such as:
- `Total cycles: ...`
- `Instruction parsed: ...`
- `# simple = ...`
- `# arith = ...`
- `# mul = ...`
- `# div = ...`
- `# mem = ...`
- `# load = ...`
- `# store = ...`
- `# branch = ...`
- `# jump = ...`
- `# jalr = ...`
- `# libcMem = ...`
- `# libcIO = ...`
- `# libcOp = ...`

## Interpretation

REIMU cycle totals are weighted rather than simple instruction counts.
Loads and stores are especially expensive, so an optimization can reduce calls or total instructions and still regress total cycles if memory traffic increases.
