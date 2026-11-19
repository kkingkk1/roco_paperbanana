"""单个 Python 手工组合的受限进程入口。"""

from __future__ import annotations

import argparse
import json
import os
import sys

from .loader import load_scene
from .runtime.manual import combination
from .runtime.solver import TwoStageSolver


def main() -> int:
    parser = argparse.ArgumentParser(description="执行一个 Python 手工组合")
    parser.add_argument("scene")
    parser.add_argument("tasks", type=int)
    parser.add_argument("satellites", type=int)
    parser.add_argument("combination")
    parser.add_argument("seed", type=int)
    args = parser.parse_args()
    if not os.environ.get("P1_RESOURCE_JOB_NAME"):
        raise RuntimeError("请使用 scripts/run-python-manual.ps1 启动，以执行 CPU 和内存限制")
    instance = load_scene(args.scene, args.tasks, args.satellites)
    print("P1_INSTANCE_READY", file=sys.stderr, flush=True)
    if sys.stdin.buffer.read(1) != b"\x01":
        raise RuntimeError("invalid resource supervisor start signal")
    strategies = combination(args.combination)
    result = TwoStageSolver().solve(instance, strategies.a, strategies.b, strategies.c, args.seed)
    observations = []
    for item in result.observations or ():
        satellite = instance.satellite(item.window.satellite_id)
        index = next(index for index, window in enumerate(satellite.windows) if window is item.window)
        observations.append({
            "task": item.task.id, "satellite": satellite.id, "window": index,
            "start": next(i for i, record in enumerate(item.window.attitudes) if record is item.start_record),
            "end": next(i for i, record in enumerate(item.window.attitudes) if record is item.end_record),
        })
    print(json.dumps({"status": result.status, "profit": result.total_profit,
                      "observations": observations, "elapsed_ns": result.elapsed_ns,
                      "detail": result.detail}, ensure_ascii=True), flush=True)
    return 0 if result.status == "SUCCESS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
