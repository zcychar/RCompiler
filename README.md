# RCompiler

A compiler for a subset of Rust, targeting **RISC-V RV32IM**.

Written in Kotlin, the compiler implements a complete pipeline from source code to
GNU-style RISC-V assembly.

## Build & Test

```bash
./gradlew build          # Build and run tests
./gradlew test           # Run unit tests only
./gradlew run --args="file.rx"            # Compile to asm (stdout)
./gradlew run --args="file.rx -o out.s"   # Compile to asm (file)
./gradlew run --args="file.rx --emit=ir"  # Emit IR instead
```

## Optimizations
- [ ] inlining 
- [ ] dead code elimination
- [ ] ...
