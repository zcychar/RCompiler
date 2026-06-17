# Repository Guidelines

## PRINCIPLE
- do not make any change to code before asking for clear permission.
- you may update `AGENTS.md` to reflect agreed project process or current progress; if an update is uncertain or changes policy, ask first.

## Current target
- The compiler pipeline is functionally complete; the current focus is optimization, not finishing frontend or missing backend stages.
- Prioritize IR and codegen optimization work that preserves correctness for the current RV64IM backend path.
- The main benchmark target is `src/main/resources/RCompiler-Testcases/IR-1`, especially `comprehensive1..50` under `test_asm_rv64_batch.bash`.
- For current backend optimization work, the primary correctness gate is the RV64IM flow in `test_asm_rv64_batch.bash --range 1 50`; `allCompilerTests` is optional context because it mainly checks whether generated frontend/backend translation tasks complete.
- The default optimized IR pipeline is inline -> conservative aggregate SROA -> mem2reg -> constant propagation -> CFG simplification -> DCE -> conservative ADCE -> CFG simplification -> final DCE; codegen then applies local GEP strength reduction, direct branch-on-compare lowering, conservative constant div/rem lowering, and removes fallthrough jumps after branch relaxation.
- The design doc lies under `docs/backend`; any design change should be reflected there, and docs and code should stay consistent.
- There's a language spec in `RCompiler-Spec`, and a reference implement written in Cpp in `ref`, but most times they are not needed; if you need to read them, tell me first.

## Project Structure & Module Organization
- Source lives in `src/main/kotlin`: `frontend/` holds lexer, parser, and preprocessor;`backend/` holds ir, optimization and codegen; semantic passes and helpers live in `utils/`. `Main.kt` wires the pipeline.
- Compiler test data sits in `src/main/resources`: `RCompiler-Testcases/` (synced upstream, treat as read-only) and `RCompiler-Local-Testcases/` (add your own stages and cases here).
- Unit tests are under `src/test/kotlin`, mirroring the production package layout. Build outputs land in `build/`.

## Build, Test, and Development Commands
- `./gradlew build` compiles against Kotlin 2.2/JVM 24 and runs unit tests.
- `./gradlew test` runs only unit tests on the JUnit Platform.
- `./gradlew run --args="FILE_NAME"` runs the program, the input file should be in src/main/resources, we just need its relevant path to the folder.
- Stage-specific compiler tests are generated from resource folders, e.g. `./gradlew Semantic1`, `./gradlew Semantic2`, `./gradlew Ir1`; local mirrors are prefixed with `local`, e.g. `./gradlew localFrontend`.
- Run all generated stages with `./gradlew allCompilerTests` when broad translation coverage is useful, but do not treat it as the main optimization gate; local-only with `./gradlew localTests`. Add `-PcompilerDebug=true` for all debug dumps, or `-PcompilerDebugStages=ir,codegen` for selected stages.

## Coding Style & Naming Conventions
- Kotlin 2.2, 4-space indentation, trailing commas avoided; prefer expression-bodied functions when clear.
- Use `PascalCase` for classes/objects, `camelCase` for functions/values, SCREAMING_SNAKE for constants. Align new files with existing package structure (e.g., new semantic utilities in `utils`).
- Tests use backticked `@Test` names for readability; keep assertions descriptive and favor `kotlin.test` helpers already in use.

## Testing Guidelines
- Unit tests rely on `kotlin.test` + JUnit Platform; place fixtures alongside relevant packages.
- Compiler integration tests expect a folder per stage and per case under `src/main/resources/RCompiler-Local-Testcases`. Each case directory must contain `<case>.rx` and `testcase_info.json` with `compileexitcode` to match Gradle’s expectations.
- When adding semantics or token changes, cover both unit cases in `src/test/kotlin` and at least one compiled sample in the local test tree.

## Benchmark Workflow
- For IR-1 performance benchmarking, use `comprehensive1..50` from `src/main/resources/RCompiler-Testcases/IR-1`.
- Build the fat jar with `./gradlew shadowJar`.
- Current correctness testing uses the RV64IM path: run one case with `./test_asm_rv64.bash <testcase>` or the batch with `./test_asm_rv64_batch.bash --range 1 50`.
- `test_asm_rv64.bash` compiles each testcase with the fat jar, assembles with `riscv64-unknown-elf-as -march=rv64im -mabi=lp64`, links `runtime/rv64_linux_runtime.s` into a static ELF, runs it with `qemu-riscv64`, and diffs the output.
- The RV64IM scripts find the fat jar at `build/libs/RCompiler-1.0-SNAPSHOT-all.jar` or `RCompiler-1.0-SNAPSHOT-all.jar`; copying the jar to the repo root is only needed for older `make run-asm`/REIMU scripts.
- Keep `docs/testing-workflow.md` current when changing validation commands or benchmark scripts.

## Validation Strategy
- Treat correctness validation and performance validation as separate checks.
- For correctness, use the relevant unit tests, stage tests, or focused testcase runs for the area being changed.
- For optimization work, do not claim improvement from static IR inspection alone; validate correctness with the RV64IM workflow above and use an agreed performance measurement when cycle counts are needed.
- When reporting optimization results, prefer aggregate comparisons across `comprehensive1..50`, and call out major changes in instruction or memory-operation counts when relevant.

## Commit & Pull Request Guidelines
- Follow the existing Conventional Commit style (`feat:`, `chore:`, `fix:`, etc.); keep subjects imperative and scoped when helpful.
- For PRs, include a short problem/solution summary, affected stages or modules, and how you validated (commands run, specific test stages). Link issues when applicable and attach relevant logs for failing/passing compiler cases.

## Security & Configuration Tips
- Use the provided Gradle wrapper; avoid modifying upstream `RCompiler-Testcases/` contents—add new scenarios under the local directory instead.
- The toolchain targets JVM 24; check `gradle.properties` if adjusting memory or parallelism for large test suites.
