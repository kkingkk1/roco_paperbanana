"""独立复核三个固定手工夹具；只使用标准库，不导入项目代码。"""

import csv
import itertools
import sys
from pathlib import Path


# 各候选为 (satellite, start, end, roll)；窗口均为固定姿态，pitch 为 0。
FIXTURES = {
    "contest": {
        0: (9, [(0, 10, 15, 0), (1, 10, 15, 0)]),
        1: (5, [(0, 10, 15, 0), (1, 10, 15, 0)]),
        2: (3, [(0, 10, 15, 0), (1, 10, 15, 0)]),
    },
    "half_second": {
        0: (10, [(0, 10, 12, 0), (0, 10.5, 12.5, 0), (0, 11, 13, 0), (1, 40, 42, 0)]),
        1: (6, [(0, 24, 26, 0), (0, 24.5, 26.5, 0), (0, 25, 27, 0)]),
        2: (8, [(0, 10, 12, 30), (1, 40, 42, 0)]),
        3: (7, []),
    },
    "slew_boundary": {
        0: (5, [(0, 10, 12, 0)]),
        1: (9, [(0, 36, 38, 30)]),
        2: (7, [(0, 37, 39, 30)]),
        3: (3, [(0, 64, 66, 0)]),
    },
}
EXPECTED = {
    "contest": (6, 27, 13, 14),
    "half_second": (9, 60, 32, 24),
    "slew_boundary": (4, 16, 10, 15),
}
COMBINATIONS = {f"A{a}B{b}C{c}" for a, b, c in itertools.product((1, 2), repeat=3)}


def feasible(observations):
    """每个夹具只出现 0° 或 30° 差角，对应 11.66 秒或 25 秒。"""
    for satellite in {item[0] for item in observations}:
        ordered = sorted((item for item in observations if item[0] == satellite), key=lambda item: item[1])
        for previous, current in zip(ordered, ordered[1:]):
            angle = abs(previous[3] - current[3])
            if angle not in (0, 30):
                raise ValueError(f"unexpected fixture angle: {angle}")
            required = 11.66 if angle == 0 else 25
            if current[1] - previous[2] < required:
                return False
    return True


def enumerate_fixture(tasks):
    total = valid = 0
    optimum = -1
    for selected in itertools.product(*[[None, *choices] for _, choices in tasks.values()]):
        total += 1
        observations = [choice for choice in selected if choice is not None]
        if feasible(observations):
            valid += 1
            score = sum(profit for (profit, _), choice in zip(tasks.values(), selected) if choice is not None)
            optimum = max(optimum, score)
    return sum(len(choices) for _, choices in tasks.values()), total, valid, optimum


def check_output(path, summaries):
    with Path(path).open(encoding="utf-8", newline="") as source:
        rows = list(csv.DictReader(source))
    if len(rows) != 24:
        raise ValueError(f"expected 24 rows, found {len(rows)}")
    seen = {name: set() for name in FIXTURES}
    for row in rows:
        name, combination = row["fixture"], row["combination"]
        if name not in FIXTURES or combination not in COMBINATIONS or combination in seen[name]:
            raise ValueError(f"unknown or duplicate fixture/combination: {name}/{combination}")
        seen[name].add(combination)
        summary = tuple(int(row[key]) for key in ("candidates", "possible", "feasible", "optimum"))
        if summary != summaries[name] or row["status"] != "SUCCESS":
            raise ValueError(f"incorrect summary or status: {name}/{combination}")
        observations, chosen_tasks, profit = [], set(), 0
        for decision in filter(None, row["decisions"].split("|")):
            task_satellite, start, end = decision.split(":")
            task, satellite = (int(value) for value in task_satellite.split("@"))
            if task in chosen_tasks or task not in FIXTURES[name]:
                raise ValueError(f"duplicate or unknown task: {name}/{combination}/{task}")
            chosen_tasks.add(task)
            reward, choices = FIXTURES[name][task]
            matching = [entry for entry in choices if entry[:3] == (satellite, float(start), float(end))]
            if len(matching) != 1:
                raise ValueError(f"decision outside manually listed candidates: {name}/{combination}/{decision}")
            observations.append(matching[0])
            profit += reward
        if (len(observations) != int(row["observations"]) or profit != int(row["profit"])
                or not feasible(observations) or profit > summaries[name][3]):
            raise ValueError(f"invalid output or profit: {name}/{combination}")
    if any(names != COMBINATIONS for names in seen.values()):
        raise ValueError("missing combinations")
    print("independent_output_check=PASS rows=24")


def main():
    if len(sys.argv) > 2:
        raise SystemExit("usage: independent_oracle.py [data.csv]")
    summaries = {name: enumerate_fixture(tasks) for name, tasks in FIXTURES.items()}
    if summaries != EXPECTED:
        raise ValueError(f"enumeration differs from manually calculated counts: {summaries}")
    for name, (candidates, possible, valid, optimum) in summaries.items():
        print(f"{name}: candidates={candidates} possible={possible} feasible={valid} optimum={optimum}")
    if len(sys.argv) == 2:
        check_output(sys.argv[1], summaries)


if __name__ == "__main__":
    main()
