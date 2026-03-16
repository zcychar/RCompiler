#!/bin/bash
# Run multiple IR-1 testcases through the ASM codegen pipeline via test_asm.bash
# Usage: test_asm_batch.bash [--exit-on-fail] [--range A B] [--quiet] [--stats] [--stats-only] [--stats-dir DIR] <case1> [case2 ...]
#   --range A B     : add comprehensiveA…comprehensiveB to the case list
#   --exit-on-fail  : stop after first failure
#   --stats         : print per-case REIMU stats and aggregate summary
#   --stats-only    : show stats-focused output with minimal extra logs
#   --stats-dir DIR : keep per-case extracted stats files in DIR

set -u

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Locate repo root (works whether this script lives at repo root or scripts/)
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

ROOT="$(find_root)" || { echo "Cannot locate repo root (expected src/main/resources/RCompiler-Testcases/IR-1)" >&2; exit 1; }
TESTER="$ROOT/test_asm.bash"
export MAKE="make -s --no-print-directory -C $ROOT"
EXIT_ON_FAIL=0
RANGE_START=""
RANGE_END=""
QUIET=0
SHOW_STATS=0
STATS_ONLY=0
STATS_DIR=""
TEMP_STATS_DIR=0
TOTAL_CYCLES=0
TOTAL_PARSED=0
declare -A STAT_SUMS
STAT_KEYS=(simple arith mul div mem load store branch jump jalr libcMem libcIO libcOp)
cases=()

usage() {
  cat >&2 <<EOF
Usage: $0 [--exit-on-fail] [--range A B] [-q|--quiet] [--stats] [--stats-only] [--stats-dir DIR] <case1> [case2 ...]
      --range A B      Add comprehensiveA..comprehensiveB
      --exit-on-fail   Stop after first failure
      --stats          Print per-case REIMU stats and aggregate summary
      --stats-only     Show stats-focused output with minimal extra logs
      --stats-dir DIR  Keep per-case extracted stats files in DIR
  -q, --quiet          Suppress tester output; print PASS/FAIL only
EOF
  exit 1
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --range)
      [[ $# -ge 3 ]] || usage
      RANGE_START="$2"; RANGE_END="$3"; shift 3 ;;
    --exit-on-fail)
      EXIT_ON_FAIL=1; shift ;;
    -q|--quiet)
      QUIET=1; shift ;;
    --stats)
      SHOW_STATS=1; shift ;;
    --stats-only)
      SHOW_STATS=1
      STATS_ONLY=1
      QUIET=1
      shift ;;
    --stats-dir)
      [[ $# -ge 2 ]] || usage
      SHOW_STATS=1
      STATS_DIR="$2"
      shift 2 ;;
    -h|--help)
      usage ;;
    *)
      cases+=("$1"); shift ;;
  esac
done

[[ -f "$TESTER" ]] || { echo "Tester not found: $TESTER" >&2; exit 1; }

init_stats_dir() {
  if (( ! SHOW_STATS )); then
    return 0
  fi
  if [[ -n "$STATS_DIR" ]]; then
    mkdir -p "$STATS_DIR" || { echo "Failed to create stats directory: $STATS_DIR" >&2; exit 1; }
  else
    STATS_DIR="$(mktemp -d -t asmbatch.stats.XXXXXX)"
    TEMP_STATS_DIR=1
  fi
}

cleanup_stats_dir() {
  if (( TEMP_STATS_DIR == 1 && FAIL == 0 )); then
    rm -rf "$STATS_DIR"
  fi
}

read_stat_value() {
  local stats_file="$1"
  local key="$2"
  awk -F': ' -v key="$key" '$1 == key { print $2; exit }' "$stats_file"
}

read_instruction_count() {
  local stats_file="$1"
  local key="$2"
  awk -v key="$key" '
    $1 == "#" && $2 == key && $3 == "=" { print $4; exit }
  ' "$stats_file"
}

accumulate_case_stats() {
  local tc="$1"
  local stats_file="$2"
  local cycles parsed value
  cycles="$(read_stat_value "$stats_file" "Total cycles")"
  parsed="$(read_stat_value "$stats_file" "Instruction parsed")"
  [[ "$cycles" =~ ^[0-9]+$ ]] || cycles=0
  [[ "$parsed" =~ ^[0-9]+$ ]] || parsed=0
  TOTAL_CYCLES=$((TOTAL_CYCLES + cycles))
  TOTAL_PARSED=$((TOTAL_PARSED + parsed))

  for key in "${STAT_KEYS[@]}"; do
    value="$(read_instruction_count "$stats_file" "$key")"
    [[ "$value" =~ ^[0-9]+$ ]] || value=0
    STAT_SUMS[$key]=$(( ${STAT_SUMS[$key]:-0} + value ))
  done

  if (( STATS_ONLY )); then
    echo "CASE $tc"
    sed '/^testcase=/d' "$stats_file"
  elif (( QUIET )); then
    echo "STATS $tc cycles=$cycles parsed=$parsed"
  fi
}

print_stats_summary() {
  (( SHOW_STATS )) || return 0

  echo "Stats summary:" >&2
  echo "  total cycles: $TOTAL_CYCLES" >&2
  echo "  total instruction parsed: $TOTAL_PARSED" >&2
  if (( PASS > 0 )); then
    echo "  average cycles per passed case: $((TOTAL_CYCLES / PASS))" >&2
    echo "  average parsed instructions per passed case: $((TOTAL_PARSED / PASS))" >&2
  fi
  echo "  instruction counts:" >&2
  for key in "${STAT_KEYS[@]}"; do
    if (( ${STAT_SUMS[$key]:-0} > 0 )); then
      echo "    $key=${STAT_SUMS[$key]:-0}" >&2
    fi
  done
  if [[ -n "$STATS_DIR" ]]; then
    echo "  stats directory: $STATS_DIR" >&2
  fi
}

init_stats_dir

if [[ -n "$RANGE_START" || -n "$RANGE_END" ]]; then
  [[ -n "$RANGE_START" && -n "$RANGE_END" ]] || usage
  if ! [[ "$RANGE_START" =~ ^[0-9]+$ && "$RANGE_END" =~ ^[0-9]+$ ]]; then
    echo "--range arguments must be numbers" >&2; exit 1
  fi
  if (( RANGE_START > RANGE_END )); then
    echo "--range start must be <= end" >&2; exit 1
  fi
  for n in $(seq "$RANGE_START" "$RANGE_END"); do
    cases+=("comprehensive${n}")
  done
fi

[[ ${#cases[@]} -gt 0 ]] || usage

PASS=0; FAIL=0; failed_cases=()

run_case() {
  local tc="$1"
  local log stats_file
  stats_file=""
  if (( SHOW_STATS )); then
    stats_file="$STATS_DIR/${tc}.stats"
  fi

  if (( QUIET )); then
    log="$(mktemp -t asmbatch.${tc}.XXXXXX)"
    if ASM_STATS_FILE="$stats_file" bash "$TESTER" "$tc" >"$log" 2>&1; then
      if (( ! STATS_ONLY )); then
        echo "PASS $tc"
      fi
      if (( SHOW_STATS )) && [[ -f "$stats_file" ]]; then
        accumulate_case_stats "$tc" "$stats_file"
      fi
      rm -f "$log"
      return 0
    else
      echo "FAIL $tc (log: $log)" >&2
      return 1
    fi
  else
    echo "===> $tc" >&2
    if ASM_SHOW_STATS="$SHOW_STATS" ASM_STATS_FILE="$stats_file" bash "$TESTER" "$tc"; then
      if (( SHOW_STATS )) && [[ -f "$stats_file" ]]; then
        accumulate_case_stats "$tc" "$stats_file"
      fi
      return 0
    else
      return 1
    fi
  fi
}

for tc in "${cases[@]}"; do
  if run_case "$tc"; then
    PASS=$((PASS+1))
  else
    FAIL=$((FAIL+1))
    failed_cases+=("$tc")
    [[ $EXIT_ON_FAIL -eq 1 ]] && break
  fi
done

if (( STATS_ONLY )); then
  echo "RESULT pass=$PASS fail=$FAIL"
else
  echo "Summary: ${PASS} passed, ${FAIL} failed" >&2
fi
print_stats_summary
if [[ $FAIL -gt 0 ]]; then
  if (( STATS_ONLY )); then
    printf 'FAILED %s\n' "${failed_cases[*]}" >&2
  else
    printf 'Failed cases: %s\n' "${failed_cases[*]}" >&2
  fi
  cleanup_stats_dir
  exit 1
fi
cleanup_stats_dir
exit 0
