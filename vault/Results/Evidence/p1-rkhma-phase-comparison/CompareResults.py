"""核对来源与A3B3C3的实际输出，并生成一次性证据摘要。"""

import csv
import sys
from collections import Counter
from pathlib import Path


RUNS = [
    ("initial", "original", 300, 42, "fixed", 441, "outputs/p1-rkhma-phase-orig-300-42-init-v2.txt"),
    ("initial", "assembled", 300, 42, "fixed", 913, "outputs/p1-rkhma-phase-assembled-300-42-init-v2.txt"),
    ("full", "original", 300, 42, "fixed", 1103, "outputs/p1-rkhma-phase-orig-300-42-full-v2.txt"),
    ("full", "assembled", 300, 42, "fixed", 1552, "outputs/p1-rkhma-phase-assembled-300-42-full-v2.txt"),
    ("initial", "original", 300, 1234, "fixed", 432, "outputs/p1-rkhma-phase-orig-300-1234-init.txt"),
    ("initial", "assembled", 300, 1234, "fixed", 1083, "outputs/p1-rkhma-phase-assembled-300-1234-init.txt"),
    ("full", "original", 300, 1234, "fixed", 793, "outputs/p1-rkhma-phase-orig-300-1234-full.txt"),
    ("full", "assembled", 300, 1234, "fixed", 1133, "outputs/p1-rkhma-phase-assembled-300-1234-full.txt"),
    ("full", "original", 500, 42, "auto", 4565, "outputs/p1-rkhma-phase-orig-500-42-auto.txt"),
    ("full", "assembled", 500, 42, "auto", 4652, "outputs/p1-rkhma-phase-assembled-500-42-auto.txt"),
    ("baseline", "original", 500, 42, "baseline", 174, "outputs/p1-rkhma-phase-orig-500-42-baseline.txt"),
]


def read(path):
    lines = Path(path).read_text(encoding="utf-8").splitlines()
    values = {}
    traces = []
    for line in lines:
        if "=" not in line:
            raise ValueError(f"unexpected output line: {path}: {line}")
        key, value = line.split("=", 1)
        if key == "trace":
            traces.append(value)
        elif key in values:
            raise ValueError(f"duplicate key: {path}: {key}")
        else:
            values[key] = value
    return lines, values, traces


def check():
    loaded = {run[-1]: read(run[-1]) for run in RUNS}
    for i in range(0, 8, 2):
        original, assembled = RUNS[i], RUNS[i + 1]
        if original[0] != assembled[0] or original[2:5] != assembled[2:5]:
            raise ValueError("invalid comparison pair")
        if loaded[original[-1]][0] != loaded[assembled[-1]][0]:
            raise ValueError(f"completed result differs: {original[-1]}")
        if loaded[original[-1]][1]["status"] != "COMPLETED":
            raise ValueError(f"incomplete controlled run: {original[-1]}")
    source_500 = loaded[RUNS[8][-1]][1]
    assembled_500 = loaded[RUNS[9][-1]][1]
    original_baseline = loaded[RUNS[10][-1]][1]
    for key in ("input_sha256", "dimensions", "source_compatible", "population", "initial"):
        if source_500[key] != assembled_500[key]:
            raise ValueError(f"500-task input or config mismatch: {key}")
    if (source_500["status"], source_500["initial_profit"], source_500["profit"]) != (
        "SEARCH_EXPIRED", "unavailable", "0"
    ):
        raise ValueError("unexpected source deadline result")
    if (assembled_500["status"], assembled_500["initial_profit"], assembled_500["profit"]) != (
        "SEARCH_EXPIRED", "unavailable", "2400"
    ):
        raise ValueError("unexpected assembled deadline result")
    for key in ("input_sha256", "dimensions", "initial", "profit", "observations"):
        if original_baseline[key] != assembled_500[key]:
            raise ValueError(f"500-task assembled checkpoint differs from source baseline: {key}")
    if original_baseline["status"] != "COMPLETED":
        raise ValueError("source baseline was not fully evaluated")

    timed = read("outputs/p1-rkhma-phase-orig-300-42-full-timed-v2.txt")
    untimed = read("outputs/p1-rkhma-phase-orig-300-42-full-v2.txt")
    differing = {key for key in timed[1] if timed[1][key] != untimed[1].get(key)}
    if differing != {"owners", "observations"} or timed[2] != untimed[2]:
        raise ValueError(f"unexpected original checkpoint comparison: {differing}")

    before = read("outputs/p1-rkhma-phase-assembled-300-42-full-defaultpool.txt")
    after = read("outputs/p1-rkhma-phase-assembled-300-42-full-fixed.txt")
    original = read("outputs/p1-rkhma-phase-orig-300-42-full-defaultpool.txt")
    if before[1] != original[1] or Counter(before[2]) != Counter(original[2]) or before[2] == original[2]:
        raise ValueError("default-order difference was not reproduced")
    if after[0] != original[0]:
        raise ValueError("default-order correction does not match source")
    return loaded


def write_csv(path, loaded):
    columns = ["phase", "implementation", "tasks", "seed", "budget", "population", "baseline_profit",
               "initial_profit", "profit", "observations", "trace_count", "status", "elapsed_ms", "raw_output"]
    with Path(path).open("x", encoding="utf-8", newline="") as target:
        writer = csv.DictWriter(target, fieldnames=columns, lineterminator="\n")
        writer.writeheader()
        for phase, implementation, tasks, seed, budget, elapsed, raw in RUNS:
            _, values, traces = loaded[raw]
            encoded = values["observations"]
            if not encoded.startswith("[") or not encoded.endswith("]"):
                raise ValueError(f"invalid observations: {raw}")
            count = 0 if encoded == "[]" else len(encoded[1:-1].split(", "))
            writer.writerow({"phase": phase, "implementation": implementation, "tasks": tasks, "seed": seed,
                             "budget": budget, "population": values["population"],
                             "baseline_profit": values.get("baseline_profit", "not_measured"),
                             "initial_profit": values["initial_profit"], "profit": values["profit"],
                             "observations": count, "trace_count": len(traces), "status": values["status"],
                             "elapsed_ms": elapsed, "raw_output": raw})


def main():
    if len(sys.argv) > 2:
        raise SystemExit("usage: CompareResults.py [new-data.csv]")
    loaded = check()
    if len(sys.argv) == 2:
        write_csv(sys.argv[1], loaded)
    print("four_completed_pairs=identical")
    print("original_timed_checkpoint=equal_profit_different_schedule")
    print("default_order=before_diff_after_identical")
    print("500_task_default_budget=both_expired_source_0_assembled_checkpoint_2400")
    print("500_task_source_baseline=assembled_checkpoint")


if __name__ == "__main__":
    main()
