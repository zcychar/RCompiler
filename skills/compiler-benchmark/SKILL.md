---
name: compiler-benchmark
description: Build, run, and compare compiler benchmark runs for this repository's IR-1 REIMU workflow. Use when Codex needs to compile the fat jar, run `test_asm_batch.bash` for `comprehensive1..50`, capture per-case stats, compare a new run against a previous baseline, or interpret benchmark regressions driven by REIMU cycle counts.
---

# Compiler Benchmark

Use this skill for repeatable benchmark runs in this repository.

Prefer the bundled scripts over retyping the workflow:
- Use `scripts/run_ir1_benchmark.sh` to build the fat jar, copy it to repo root, run the IR-1 batch benchmark, and save logs plus per-case stats into one result directory.
- Use `scripts/compare_stats.py` to compare two saved benchmark result directories or two raw stats directories.

## Workflow

1. Run the benchmark:

```bash
skills/compiler-benchmark/scripts/run_ir1_benchmark.sh --repo /path/to/RCompiler
```

2. Save a run as a reusable named baseline:

```bash
skills/compiler-benchmark/scripts/run_ir1_benchmark.sh \
  --repo /path/to/RCompiler \
  --label baseline-before-inline-fix \
  --save-baseline before-inline-fix
```

3. Compare to a previous run:

```bash
skills/compiler-benchmark/scripts/run_ir1_benchmark.sh \
  --repo /path/to/RCompiler \
  --compare /path/to/older-result
```

or:

```bash
skills/compiler-benchmark/scripts/run_ir1_benchmark.sh \
  --repo /path/to/RCompiler \
  --compare-baseline before-inline-fix
```

or:

```bash
python3 skills/compiler-benchmark/scripts/compare_stats.py \
  /path/to/older-result \
  /path/to/newer-result
```

## What The Runner Saves

Each run creates a result directory under `benchmark-results/` by default:
- `build.log`
- `run.log`
- `stats/` with one `.stats` file per testcase
- optional named baseline symlinks under `benchmark-results/baselines/`

Prefer comparing saved result directories, not pasted terminal output.

## Preconditions

- Run this skill at a repo with `gradlew`, `test_asm_batch.bash`, and the `IR-1` testcase tree.
- Ensure `reimu` is available in `PATH`.
- The benchmark expects the fat jar to be available at repo root as `RCompiler-1.0-SNAPSHOT-all.jar`; the runner script handles the copy step.

## Gradle Cache Note

If Gradle cannot write to its default cache location, set a writable cache explicitly:

```bash
skills/compiler-benchmark/scripts/run_ir1_benchmark.sh \
  --repo /path/to/RCompiler \
  --gradle-user-home /tmp/gradle-home
```

## Interpreting Results

- Compare total cycles first.
- Then inspect per-case regressions from `compare_stats.py`.
- If cycles regress while raw instruction count does not grow much, inspect memory traffic: REIMU heavily penalizes loads and stores.

For benchmark format details and REIMU stat meanings, read `references/workflow.md`.
