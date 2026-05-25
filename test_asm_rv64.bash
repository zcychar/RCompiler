#!/bin/bash
# Run one IR-1 ASM testcase as a real RV64 Linux-user ELF under qemu-riscv64.

set -u

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

find_root() {
  for cand in "$SCRIPT_DIR" "$SCRIPT_DIR/.." ; do
    if [ -d "$cand/src/main/resources/RCompiler-Testcases/IR-1" ]; then
      echo "$cand"
      return 0
    fi
  done
  if command -v git >/dev/null 2>&1; then
    local git_root
    git_root=$(cd "$SCRIPT_DIR" && git rev-parse --show-toplevel 2>/dev/null) || true
    if [ -n "${git_root:-}" ] && [ -d "$git_root/src/main/resources/RCompiler-Testcases/IR-1" ]; then
      echo "$git_root"
      return 0
    fi
  fi
  return 1
}

ROOT="$(find_root)" || { echo "Cannot locate repo root" >&2; exit 1; }
IR_DIR="$ROOT/src/main/resources/RCompiler-Testcases/IR-1"
RUNTIME="$ROOT/runtime/rv64_linux_runtime.s"

print_red()   { echo -e "\033[31m$1\033[0m" >&2; }
print_green() { echo -e "\033[32m$1\033[0m" >&2; }

usage() {
  cat >&2 <<EOF
Usage: $0 <testcase> [tempdir]

Environment:
  COMPILER_JAR      Compiler fat jar (default: RCompiler-1.0-SNAPSHOT-all.jar)
  COMPILER_FLAGS    Extra compiler flags
  RV64_QEMU         qemu-riscv64 path
  RV64_TIMEOUT      Timeout for execution (default: 30s)
EOF
  exit 1
}

find_qemu() {
  if [ -n "${RV64_QEMU:-}" ]; then
    command -v "$RV64_QEMU" >/dev/null 2>&1 || [ -x "$RV64_QEMU" ] || return 1
    echo "$RV64_QEMU"
    return 0
  fi
  for cand in qemu-riscv64 /home/zcychar/qemu-7.0.0/build/qemu-riscv64; do
    if command -v "$cand" >/dev/null 2>&1; then
      command -v "$cand"
      return 0
    fi
    if [ -x "$cand" ]; then
      echo "$cand"
      return 0
    fi
  done
  return 1
}

find_compiler_jar() {
  if [ -n "${COMPILER_JAR:-}" ]; then
    [ -f "$COMPILER_JAR" ] && { echo "$COMPILER_JAR"; return 0; }
    return 1
  fi
  for cand in \
    "$ROOT/build/libs/RCompiler-1.0-SNAPSHOT-all.jar" \
    "$ROOT/RCompiler-1.0-SNAPSHOT-all.jar"
  do
    [ -f "$cand" ] && { echo "$cand"; return 0; }
  done
  return 1
}

if [ $# -lt 1 ] || [ $# -gt 2 ]; then
  usage
fi

TESTCASE="$1"
SRC="$IR_DIR/src/$TESTCASE/${TESTCASE}.rx"
IN="$IR_DIR/src/$TESTCASE/${TESTCASE}.in"
ANS="$IR_DIR/src/$TESTCASE/${TESTCASE}.out"

if [ ! -f "$SRC" ] || [ ! -f "$IN" ] || [ ! -f "$ANS" ]; then
  echo "Error: testcase '$TESTCASE' does not exist or files are missing." >&2
  exit 1
fi

[ -f "$RUNTIME" ] || { echo "Runtime not found: $RUNTIME" >&2; exit 1; }
command -v riscv64-unknown-elf-as >/dev/null 2>&1 || { echo "Missing riscv64-unknown-elf-as" >&2; exit 1; }
command -v riscv64-unknown-elf-ld >/dev/null 2>&1 || { echo "Missing riscv64-unknown-elf-ld" >&2; exit 1; }
QEMU="$(find_qemu)" || { echo "Missing qemu-riscv64" >&2; exit 1; }
JAR="$(find_compiler_jar)" || {
  echo "Missing compiler fat jar. Run './gradlew shadowJar' first." >&2
  exit 1
}

if [ $# -eq 2 ]; then
  USER_DEFINED_TEMPDIR=1
  TEMPDIR="$2"
  mkdir -p "$TEMPDIR" || exit 1
else
  USER_DEFINED_TEMPDIR=0
  TEMPDIR="$(mktemp -d -p /tmp rcc-rv64.XXXXXXXXXX)" || exit 1
fi

clean() {
  if [ "$USER_DEFINED_TEMPDIR" -eq 0 ]; then
    rm -rf "$TEMPDIR"
  fi
}

print_temp_dir() {
  cat >&2 <<EOF
Temp files at: $TEMPDIR
  test.s        compiler output
  runtime.o     runtime object
  test.o        testcase object
  test.elf      linked ELF
  test.out      actual output
  test.ans      expected output
  compile.err   compiler stderr
  qemu.err      qemu stderr
Clean up:  rm -rf '$TEMPDIR'
EOF
}

cp "$ANS" "$TEMPDIR/test.ans"

echo "Compiling '$TESTCASE' -> RV64 assembly..." >&2
# shellcheck disable=SC2086
java -jar "$JAR" "$SRC" --emit=asm ${COMPILER_FLAGS:-} -o "$TEMPDIR/test.s" \
  2>"$TEMPDIR/compile.err"
if [ $? -ne 0 ]; then
  echo "Error: compilation failed for '$TESTCASE'." >&2
  cat "$TEMPDIR/compile.err" >&2
  print_temp_dir
  exit 1
fi

echo "Assembling and linking RV64 ELF..." >&2
riscv64-unknown-elf-as -march=rv64im -mabi=lp64 "$RUNTIME" -o "$TEMPDIR/runtime.o" \
  2>"$TEMPDIR/as_runtime.err" || {
    cat "$TEMPDIR/as_runtime.err" >&2
    print_temp_dir
    exit 1
  }

riscv64-unknown-elf-as -march=rv64im -mabi=lp64 "$TEMPDIR/test.s" -o "$TEMPDIR/test.o" \
  2>"$TEMPDIR/as_test.err" || {
    cat "$TEMPDIR/as_test.err" >&2
    print_temp_dir
    exit 1
  }

riscv64-unknown-elf-ld -static -o "$TEMPDIR/test.elf" "$TEMPDIR/runtime.o" "$TEMPDIR/test.o" \
  2>"$TEMPDIR/ld.err" || {
    cat "$TEMPDIR/ld.err" >&2
    print_temp_dir
    exit 1
  }

echo "Running on qemu-riscv64..." >&2
RV64_TIMEOUT="${RV64_TIMEOUT:-30s}"
if command -v timeout >/dev/null 2>&1; then
  timeout "$RV64_TIMEOUT" "$QEMU" "$TEMPDIR/test.elf" < "$IN" > "$TEMPDIR/test.out" 2>"$TEMPDIR/qemu.err"
else
  "$QEMU" "$TEMPDIR/test.elf" < "$IN" > "$TEMPDIR/test.out" 2>"$TEMPDIR/qemu.err"
fi
RUN_EXIT=$?

if [ $RUN_EXIT -ne 0 ]; then
  echo "Error: qemu-riscv64 exited with code $RUN_EXIT." >&2
  cat "$TEMPDIR/qemu.err" >&2
  print_temp_dir
  exit 1
fi

if diff -ZB "$TEMPDIR/test.out" "$TEMPDIR/test.ans" >&2; then
  print_green "PASSED  $TESTCASE"
  clean
  exit 0
else
  print_red "FAILED  $TESTCASE"
  print_temp_dir
  exit 1
fi
