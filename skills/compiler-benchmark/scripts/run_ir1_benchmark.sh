#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: run_ir1_benchmark.sh [options]

Options:
  --repo DIR                Repository root. Defaults to current directory.
  --range START END         Benchmark range. Defaults to 1 50.
  --results-dir DIR         Parent directory for saved runs. Defaults to <repo>/benchmark-results.
  --baseline-dir DIR        Directory for named baselines. Defaults to <results-dir>/baselines.
  --label NAME              Result directory name. Defaults to UTC timestamp.
  --save-baseline NAME      Save this run as a named baseline symlink under <baseline-dir>.
  --compare-baseline NAME   Compare this run against a named baseline under <baseline-dir>.
  --gradle-user-home DIR    Set GRADLE_USER_HOME for the build.
  --compare PATH            Compare the new run against a previous result dir or stats dir.
  -h, --help                Show this help.
EOF
}

repo="$(pwd)"
range_start=1
range_end=50
results_dir=""
baseline_dir=""
label=""
save_baseline=""
compare_baseline=""
gradle_user_home=""
compare_path=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo)
      repo="$2"
      shift 2
      ;;
    --range)
      range_start="$2"
      range_end="$3"
      shift 3
      ;;
    --results-dir)
      results_dir="$2"
      shift 2
      ;;
    --baseline-dir)
      baseline_dir="$2"
      shift 2
      ;;
    --label)
      label="$2"
      shift 2
      ;;
    --save-baseline)
      save_baseline="$2"
      shift 2
      ;;
    --compare-baseline)
      compare_baseline="$2"
      shift 2
      ;;
    --gradle-user-home)
      gradle_user_home="$2"
      shift 2
      ;;
    --compare)
      compare_path="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

repo="$(cd "$repo" && pwd)"
results_dir="${results_dir:-$repo/benchmark-results}"
baseline_dir="${baseline_dir:-$results_dir/baselines}"
label="${label:-$(date -u +%Y%m%dT%H%M%SZ)}"
run_dir="$results_dir/$label"
stats_dir="$run_dir/stats"
build_log="$run_dir/build.log"
run_log="$run_dir/run.log"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
jar_name="RCompiler-1.0-SNAPSHOT-all.jar"
jar_src="$repo/build/libs/$jar_name"
jar_dst="$repo/$jar_name"

if [[ ! -x "$repo/gradlew" ]]; then
  echo "Missing gradlew under repo root: $repo" >&2
  exit 1
fi

if [[ ! -f "$repo/test_asm_batch.bash" ]]; then
  echo "Missing benchmark script under repo root: $repo/test_asm_batch.bash" >&2
  exit 1
fi

if [[ ! -d "$repo/src/main/resources/RCompiler-Testcases/IR-1" ]]; then
  echo "Missing IR-1 testcase tree under repo root" >&2
  exit 1
fi

if ! command -v reimu >/dev/null 2>&1; then
  echo "reimu not found in PATH" >&2
  exit 1
fi

mkdir -p "$stats_dir"
mkdir -p "$baseline_dir"

echo "==> Building fat jar"
if [[ -n "$gradle_user_home" ]]; then
  GRADLE_USER_HOME="$gradle_user_home" "$repo/gradlew" shadowJar 2>&1 | tee "$build_log"
  build_status=${PIPESTATUS[0]}
else
  "$repo/gradlew" shadowJar 2>&1 | tee "$build_log"
  build_status=${PIPESTATUS[0]}
fi
if [[ $build_status -ne 0 ]]; then
  echo "Build failed. See $build_log" >&2
  exit "$build_status"
fi

if [[ ! -f "$jar_src" ]]; then
  echo "Expected fat jar not found: $jar_src" >&2
  exit 1
fi

cp "$jar_src" "$jar_dst"

echo "==> Running benchmark range $range_start..$range_end"
bash "$repo/test_asm_batch.bash" --stats-only --stats-dir "$stats_dir" --range "$range_start" "$range_end" 2>&1 | tee "$run_log"
run_status=${PIPESTATUS[0]}
if [[ $run_status -ne 0 ]]; then
  echo "Benchmark failed. See $run_log" >&2
  exit "$run_status"
fi

echo "==> Saved run to $run_dir"
echo "    build log: $build_log"
echo "    benchmark log: $run_log"
echo "    stats dir: $stats_dir"

if [[ -n "$save_baseline" ]]; then
  baseline_link="$baseline_dir/$save_baseline"
  ln -sfn "$run_dir" "$baseline_link"
  echo "==> Saved baseline '$save_baseline' -> $baseline_link"
fi

if [[ -n "$compare_baseline" ]]; then
  compare_path="$baseline_dir/$compare_baseline"
  if [[ ! -e "$compare_path" ]]; then
    echo "Named baseline not found: $compare_path" >&2
    exit 1
  fi
fi

if [[ -n "$compare_path" ]]; then
  echo "==> Comparing against $compare_path"
  python3 "$script_dir/compare_stats.py" "$compare_path" "$run_dir"
fi
