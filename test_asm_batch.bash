#!/bin/bash
# Run multiple IR-1 testcases through the ASM codegen pipeline via test_asm.bash
# Usage: test_asm_batch.bash [--exit-on-fail] [--range A B] [--quiet] <case1> [case2 ...]
#   --range A B : add comprehensiveA…comprehensiveB to the case list
#   --exit-on-fail: stop after first failure

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
cases=()

usage() {
  cat >&2 <<EOF
Usage: $0 [--exit-on-fail] [--range A B] [-q|--quiet] <case1> [case2 ...]
      --range A B      Add comprehensiveA..comprehensiveB
      --exit-on-fail   Stop after first failure
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
    -h|--help)
      usage ;;
    *)
      cases+=("$1"); shift ;;
  esac
done

[[ -f "$TESTER" ]] || { echo "Tester not found: $TESTER" >&2; exit 1; }

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
  if (( QUIET )); then
    local log
    log="$(mktemp -t asmbatch.${tc}.XXXXXX)"
    if bash "$TESTER" "$tc" >"$log" 2>&1; then
      echo "PASS $tc"
      rm -f "$log"
      return 0
    else
      echo "FAIL $tc (log: $log)" >&2
      return 1
    fi
  else
    echo "===> $tc" >&2
    bash "$TESTER" "$tc"
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

echo "Summary: ${PASS} passed, ${FAIL} failed" >&2
if [[ $FAIL -gt 0 ]]; then
  printf 'Failed cases: %s\n' "${failed_cases[*]}" >&2
  exit 1
fi
exit 0
