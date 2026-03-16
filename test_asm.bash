#!/bin/bash
# test_asm.bash — Run a single ASM codegen testcase through REIMU.
#
# Usage: test_asm.bash <testcase> [tempdir]
#
# The compiler is invoked via `make run-asm` at the repo root, which reads
# source from stdin and emits RISC-V assembly to stdout.  REIMU then runs
# the assembly with the provided input and the output is diffed against the
# expected answer.
#
# Unlike test_llvm.bash, this does NOT need clang or builtin.c — the compiler
# emits RISC-V assembly directly and REIMU handles builtins at the simulator
# level.
#
# Environment variables:
#   ASM_SHOW_STATS=1   Print REIMU stats for this testcase to stderr on success
#   ASM_STATS_FILE     Write extracted REIMU stats to this file

set -u

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# ── Locate repo root ────────────────────────────────────────────────────

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
export MAKE="make -s --no-print-directory -C $ROOT"

# ── Helpers ─────────────────────────────────────────────────────────────

print_red()   { echo -e "\033[31m$1\033[0m" >&2; }
print_green() { echo -e "\033[32m$1\033[0m" >&2; }

extract_reimu_stats() {
  awk '
    /^Exit code:/ { capture=1 }
    capture { print }
  ' "$1"
}

write_reimu_stats() {
  local source_file="$1"
  local stats_file="$2"
  {
    echo "testcase=$TESTCASE"
    extract_reimu_stats "$source_file"
  } > "$stats_file"
}

show_reimu_stats() {
  local source_file="$1"
  echo "REIMU stats for $TESTCASE:" >&2
  extract_reimu_stats "$source_file" >&2
}

# ── Usage ───────────────────────────────────────────────────────────────

if [ $# -lt 1 ] || [ $# -gt 2 ]; then
  cat >&2 <<EOF
Usage: $0 <testcase> [tempdir]
  Your compiler is run via 'make run-asm' at the repo root.
EOF
  exit 1
fi

TESTCASE=$1

# Validate testcase exists
if [ ! -d "$IR_DIR/src/$TESTCASE" ] \
   || [ ! -f "$IR_DIR/src/$TESTCASE/${TESTCASE}.rx" ] \
   || [ ! -f "$IR_DIR/src/$TESTCASE/${TESTCASE}.in" ] \
   || [ ! -f "$IR_DIR/src/$TESTCASE/${TESTCASE}.out" ]; then
  echo "Error: testcase '$TESTCASE' does not exist or files are missing." >&2
  exit 1
fi

# Check that reimu is installed
if ! command -v reimu >/dev/null 2>&1; then
  echo "Error: reimu not found in PATH" >&2
  exit 1
fi

# ── Temp directory ──────────────────────────────────────────────────────

if [ $# -eq 2 ]; then
  USER_DEFINED_TEMPDIR=1
  TEMPDIR=$2
else
  USER_DEFINED_TEMPDIR=0
  TEMPDIR="$(mktemp -d -p /tmp rcc-asm.XXXXXXXXXX)"
  if [ $? -ne 0 ]; then
    echo "Error: failed to create temp directory." >&2
    exit 1
  fi
fi

if [ ! -d "$TEMPDIR" ]; then
  echo "Error: temp directory does not exist." >&2
  exit 1
fi

clean() {
  if [ $USER_DEFINED_TEMPDIR -eq 0 ]; then
    rm -rf "$TEMPDIR"
  fi
}

print_temp_dir() {
  cat >&2 <<EOF
Temp files at: $TEMPDIR
  test.s        compiler output
  test.in       input
  test.out      actual output
  test.ans      expected output
  reimu_out.txt reimu stdout
  reimu_err.txt reimu stderr
Clean up:  rm -rf '$TEMPDIR'
EOF
}

# ── 1. Compile ──────────────────────────────────────────────────────────

echo "Compiling '$TESTCASE' → RISC-V assembly..." >&2
${MAKE:-make} run-asm < "$IR_DIR/src/$TESTCASE/${TESTCASE}.rx" > "$TEMPDIR/test.s" 2>"$TEMPDIR/compile_err.txt"
if [ $? -ne 0 ]; then
  echo "Error: compilation failed for '$TESTCASE'." >&2
  cat "$TEMPDIR/compile_err.txt" >&2
  print_temp_dir
  clean
  exit 1
fi

# ── 2. Copy input / expected output ────────────────────────────────────

cp "$IR_DIR/src/$TESTCASE/${TESTCASE}.in"  "$TEMPDIR/test.in"
cp "$IR_DIR/src/$TESTCASE/${TESTCASE}.out" "$TEMPDIR/test.ans"

# ── 3. Run on REIMU ────────────────────────────────────────────────────

echo "Running on REIMU..." >&2
(cd "$TEMPDIR" && reimu -f=test.s -i="$TEMPDIR/test.in" --stack=64M -o="$TEMPDIR/test.out") \
  > "$TEMPDIR/reimu_out.txt" 2> "$TEMPDIR/reimu_err.txt"
REIMU_EXIT=$?

if [ $REIMU_EXIT -ne 0 ]; then
  echo "Error: REIMU exited with code $REIMU_EXIT." >&2
  cat "$TEMPDIR/reimu_err.txt" >&2
  if [ -n "${ASM_STATS_FILE:-}" ]; then
    write_reimu_stats "$TEMPDIR/reimu_err.txt" "$ASM_STATS_FILE"
  fi
  print_temp_dir
  exit 1
fi

if [ -n "${ASM_STATS_FILE:-}" ]; then
  write_reimu_stats "$TEMPDIR/reimu_err.txt" "$ASM_STATS_FILE"
fi

# ── 4. Compare output ──────────────────────────────────────────────────

HAS_PROBLEM=0
diff -ZB "$TEMPDIR/test.out" "$TEMPDIR/test.ans" >&2
if [ $? -ne 0 ]; then
  echo "Error: output mismatch." >&2
  print_temp_dir
  HAS_PROBLEM=1
fi

if [ $HAS_PROBLEM -eq 0 ]; then
  print_green "PASSED  $TESTCASE"
  if [ "${ASM_SHOW_STATS:-0}" = "1" ]; then
    show_reimu_stats "$TEMPDIR/reimu_err.txt"
  fi
  clean
  exit 0
else
  print_red "FAILED  $TESTCASE"
  print_temp_dir
  exit 1
fi
