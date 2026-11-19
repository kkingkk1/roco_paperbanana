"""场景只读检查入口。"""

import argparse

from .loader import load_scene
from .problem import evaluate


def main() -> None:
    parser = argparse.ArgumentParser(description="读取场景并报告问题规模")
    parser.add_argument("scene")
    parser.add_argument("tasks", type=int)
    parser.add_argument("satellites", type=int)
    args = parser.parse_args()
    instance = load_scene(args.scene, args.tasks, args.satellites)
    windows = sum(len(satellite.windows) for satellite in instance.satellites)
    records = sum(len(window.attitudes) for satellite in instance.satellites
                  for window in satellite.windows)
    result = evaluate(instance, ())
    print(f"tasks={len(instance.tasks)} satellites={len(instance.satellites)} "
          f"windows={windows} attitudeRecords={records}")
    print(f"emptyPlan.feasible={str(result.feasible).lower()} emptyPlan.totalProfit={result.total_profit}")


if __name__ == "__main__":
    main()
