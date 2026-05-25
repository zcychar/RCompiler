# RCompiler

A compiler for a subset of Rust, targeting **RISC-V RV64IM**.

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

Legacy IR-1 ASM benchmarking uses the RV32-oriented REIMU path:

```bash
./gradlew shadowJar
cp build/libs/RCompiler-1.0-SNAPSHOT-all.jar RCompiler-1.0-SNAPSHOT-all.jar
./test_asm_batch.bash --stats-only --range 1 50
```

For RV64IM ASM execution, use the qemu-based runner. It assembles the generated
RV64 assembly, links it with `runtime/rv64_linux_runtime.s`, runs the ELF under
`qemu-riscv64`, and diffs stdout against the testcase answer:

```bash
./gradlew shadowJar
./test_asm_rv64.bash comprehensive1
./test_asm_rv64_batch.bash --range 1 50 -q
```

Optional comparison runs can pass compiler flags through `make`:

```bash
COMPILER_FLAGS=--no-opt ./test_asm_batch.bash --stats-only --range 1 50
```

For backend optimization work, generated compiler-stage tasks such as
`allCompilerTests` are useful focused translation checks. For RV64IM assembly,
validate generated `.s` files with an RV64 assembler such as `llvm-mc
-triple=riscv64 -mattr=+m` or `riscv64-unknown-elf-as -march=rv64im -mabi=lp64`.
The old REIMU-based ASM scripts are RV32-oriented and are not the RV64 execution
gate.

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
