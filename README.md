
## Usage
- Run: `./gradlew run --args="INPUT_PATH [--debug[=parse,semantic,ir|all]] [--ir-out=PATH|-]"`
- Input:
  - `INPUT_PATH` is a resource or filesystem path. Use `-` or omit the positional argument to read from stdin.
- Flags:
  - `--debug` or `--debug=all`: enable all debug output.
  - `--debug=parse`: show preprocess/lex/parse debug.
  - `--debug=semantic`: show semantic passes (collector/resolver/impl-inject/checker).
  - `--debug=ir`: print generated IR.
  - Comma-separate to combine (e.g., `--debug=parse,ir`).
  - `--ir-out=PATH`: write the generated IR to the given path, creating parent directories if needed.
  - `--ir-out=-`: write the generated IR to stdout (quiet: no banners, just IR).
- Exit codes:
  - `0` on successful compilation.
  - `1` on compile error or internal error.

## Build & Tests
- Build: `./gradlew build`
- Run compiler: `./gradlew run --args="path/to/file.rx [flags]"`

## Notes
- Debug output is controlled by `--debug` (or Gradle properties: `-PcompilerDebug=true` for all, or `-PcompilerDebugStages=parse,semantic,ir` for a list).
- IR is generated on every successful compilation; it is printed to stdout only when `ir` debug is enabled or when `--ir-out=-` is used. Use `--ir-out=PATH` to save it to disk.
