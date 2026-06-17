#!/usr/bin/env python3
from __future__ import annotations

import argparse
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Iterable

STAT_KEYS = [
    "simple",
    "arith",
    "mul",
    "div",
    "mem",
    "load",
    "store",
    "branch",
    "jump",
    "jalr",
    "libcMem",
    "libcIO",
    "libcOp",
]


@dataclass
class CaseStats:
    name: str
    total_cycles: int = 0
    instruction_parsed: int = 0
    counts: Dict[str, int] | None = None

    def __post_init__(self) -> None:
        if self.counts is None:
            self.counts = {key: 0 for key in STAT_KEYS}


def resolve_stats_dir(path: Path) -> Path:
    if path.is_dir() and (path / "stats").is_dir():
        return path / "stats"
    if path.is_dir():
        return path
    raise FileNotFoundError(f"stats directory not found: {path}")


def parse_stats_file(path: Path) -> CaseStats:
    name = path.stem
    stats = CaseStats(name=name)
    for line in path.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if stripped.startswith("testcase="):
            stats.name = stripped.split("=", 1)[1].strip() or stats.name
        elif stripped.startswith("Total cycles:"):
            stats.total_cycles = parse_int(stripped.split(":", 1)[1])
        elif stripped.startswith("Instruction parsed:"):
            stats.instruction_parsed = parse_int(stripped.split(":", 1)[1])
        elif stripped.startswith("#") and "=" in stripped:
            left, right = stripped.split("=", 1)
            key = left.replace("#", "").strip()
            if key in stats.counts:
                stats.counts[key] = parse_int(right)
    return stats


def parse_int(value: str) -> int:
    value = value.strip()
    digits = "".join(ch for ch in value if ch.isdigit() or ch == "-")
    return int(digits) if digits else 0


def load_stats(dir_path: Path) -> Dict[str, CaseStats]:
    stats_dir = resolve_stats_dir(dir_path)
    result: Dict[str, CaseStats] = {}
    for path in sorted(stats_dir.glob("*.stats")):
        case = parse_stats_file(path)
        result[case.name] = case
    if not result:
        raise FileNotFoundError(f"no .stats files found in {stats_dir}")
    return result


def sum_cycles(items: Iterable[CaseStats]) -> int:
    return sum(item.total_cycles for item in items)


def sum_parsed(items: Iterable[CaseStats]) -> int:
    return sum(item.instruction_parsed for item in items)


def sum_key(items: Iterable[CaseStats], key: str) -> int:
    return sum(item.counts.get(key, 0) for item in items)


def format_delta(old: int, new: int) -> str:
    delta = new - old
    if old == 0:
        pct = "n/a"
    else:
        pct = f"{(delta / old) * 100:+.2f}%"
    return f"{delta:+d} ({pct})"


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Compare two compiler benchmark runs by per-case REIMU stats."
    )
    parser.add_argument("old", help="Older result dir or raw stats dir")
    parser.add_argument("new", help="Newer result dir or raw stats dir")
    parser.add_argument(
        "--top",
        type=int,
        default=10,
        help="Number of top regressions/improvements to print",
    )
    args = parser.parse_args()

    old_stats = load_stats(Path(args.old))
    new_stats = load_stats(Path(args.new))

    old_cases = set(old_stats)
    new_cases = set(new_stats)
    common = sorted(old_cases & new_cases)
    only_old = sorted(old_cases - new_cases)
    only_new = sorted(new_cases - old_cases)

    if not common:
        raise SystemExit("no common cases to compare")

    old_items = [old_stats[name] for name in common]
    new_items = [new_stats[name] for name in common]

    old_total = sum_cycles(old_items)
    new_total = sum_cycles(new_items)
    old_parsed = sum_parsed(old_items)
    new_parsed = sum_parsed(new_items)

    print(f"Compared cases: {len(common)}")
    print(f"Total cycles: {old_total} -> {new_total}  delta {format_delta(old_total, new_total)}")
    print(f"Instruction parsed: {old_parsed} -> {new_parsed}  delta {format_delta(old_parsed, new_parsed)}")
    print()
    print("Instruction-class totals:")
    for key in STAT_KEYS:
        old_value = sum_key(old_items, key)
        new_value = sum_key(new_items, key)
        if old_value == 0 and new_value == 0:
            continue
        print(f"  {key}: {old_value} -> {new_value}  delta {format_delta(old_value, new_value)}")

    ranked = []
    for name in common:
        old_case = old_stats[name]
        new_case = new_stats[name]
        ranked.append((new_case.total_cycles - old_case.total_cycles, name, old_case.total_cycles, new_case.total_cycles))
    ranked.sort(reverse=True)

    top = max(args.top, 0)
    if top:
        regressions = [item for item in ranked if item[0] > 0][:top]
        improvements = [item for item in reversed(ranked) if item[0] < 0][:top]
        if regressions:
            print()
            print("Top regressions:")
            for delta, name, old_value, new_value in regressions:
                print(f"  {name}: {old_value} -> {new_value}  delta {format_delta(old_value, new_value)}")
        if improvements:
            print()
            print("Top improvements:")
            for delta, name, old_value, new_value in improvements:
                print(f"  {name}: {old_value} -> {new_value}  delta {format_delta(old_value, new_value)}")

    if only_old:
        print()
        print("Only in old:", ", ".join(only_old))
    if only_new:
        print()
        print("Only in new:", ", ".join(only_new))

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
