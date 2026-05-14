# RCompiler

A compiler for a subset of Rust, targeting **RISC-V RV32IM**.

Written in Kotlin, the compiler implements a complete pipeline from source code to
GNU-style RISC-V assembly.

## Build & Test

```bash
./gradlew build          # Build and run tests
./gradlew test           # Run unit tests only
./gradlew allCompilerTests                # Optional generated compiler-stage translation checks
./gradlew localTests                      # Run generated local compiler-stage tests only
./gradlew run --args="file.rx"            # Compile to ASM (stdout)
./gradlew run --args="file.rx -o out.s"   # Compile to ASM (file)
./gradlew run --args="file.rx --emit=ir"  # Emit IR instead
```

For IR-1 ASM benchmarking:

```bash
./gradlew shadowJar
cp build/libs/RCompiler-1.0-SNAPSHOT-all.jar RCompiler-1.0-SNAPSHOT-all.jar
./test_asm_batch.bash --stats-only --range 1 50
```

Optional comparison runs can pass compiler flags through `make`:

```bash
COMPILER_FLAGS=--no-opt ./test_asm_batch.bash --stats-only --range 1 50
```

For backend optimization work, use `test_asm_batch.bash` on
`comprehensive1..50` as the primary correctness and REIMU cycle gate. Generated
compiler-stage tasks such as `allCompilerTests` are useful focused translation
checks, but they do not measure the target cycle behavior. See
`docs/testing-workflow.md` for the full validation workflow and generated
compiler-test task names.

## Optimizations
- [x] Mem2Reg scalar/pointer alloca promotion
- [x] Function inlining
- [x] Constant propagation and branch folding
- [x] CFG simplification for empty jump blocks and same-target branches
- [x] Dead code elimination
- [x] Conservative aggressive dead code elimination
- [x] Fallthrough jump elimination in RISC-V codegen
- [x] Conservative aggregate SROA
- [ ] Control-dependence ADCE
