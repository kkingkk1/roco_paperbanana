"""在求解进程之外验证手工组合的输出；操作系统限制由启动脚本施加。"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys

from .loader import load_scene
from .model import ProblemInstance, ScheduledTask
from .problem import evaluate


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"重复字段: {key}")
        result[key] = value
    return result


def _invalid_constant(value):
    raise ValueError(f"非法数字: {value}")


def validate_output(instance: ProblemInstance, raw: bytes):
    """仅从可信场景还原观测，重新检查合法性及报告收益。"""
    data = json.loads(raw, object_pairs_hook=_unique_object, parse_constant=_invalid_constant)
    if not isinstance(data, dict) or set(data) != {"status", "profit", "observations", "elapsed_ns", "detail"}:
        raise ValueError("结果字段不完整或包含额外字段")
    if data["status"] not in ("SUCCESS", "FAILURE", "HARD_TIMEOUT"):
        raise ValueError("未知求解状态")
    if type(data["elapsed_ns"]) is not int or data["elapsed_ns"] < 0 or not isinstance(data["detail"], str):
        raise ValueError("非法耗时或状态说明")
    rows = data["observations"]
    if not isinstance(rows, list) or len(rows) > len(instance.tasks):
        raise ValueError("非法观测清单")
    if data["status"] != "SUCCESS":
        if data["profit"] is not None or rows:
            raise ValueError("失败结果包含收益或观测")
        return data, ()
    if type(data["profit"]) is not int:
        raise ValueError("非法收益")
    observations = []
    for row in rows:
        if (not isinstance(row, dict) or set(row) != {"task", "satellite", "window", "start", "end"}
                or any(type(value) is not int or value < 0 for value in row.values())):
            raise ValueError("非法观测字段")
        task = instance.task(row["task"])
        satellite = instance.satellite(row["satellite"])
        if task is None or satellite is None or row["window"] >= len(satellite.windows):
            raise ValueError("观测引用不存在的任务、卫星或窗口")
        window = satellite.windows[row["window"]]
        if row["start"] >= len(window.attitudes) or row["end"] >= len(window.attitudes):
            raise ValueError("观测引用不存在的姿态记录")
        observations.append(ScheduledTask(task, window, window.attitudes[row["start"]],
                                          window.attitudes[row["end"]]))
    checked = evaluate(instance, observations)
    if not checked.feasible or checked.total_profit != data["profit"]:
        raise ValueError(f"独立验证失败: {checked.violations or ('收益不一致',)}")
    return data, tuple(observations)


def main() -> int:
    parser = argparse.ArgumentParser(description="独立验证一个 Python 手工组合")
    parser.add_argument("scene")
    parser.add_argument("tasks", type=int)
    parser.add_argument("satellites", type=int)
    parser.add_argument("combination")
    parser.add_argument("seed", type=int)
    args = parser.parse_args()
    if not os.environ.get("P1_RESOURCE_JOB_NAME"):
        raise RuntimeError("请使用 scripts/run-python-manual.ps1 启动，以执行 CPU 和内存限制")
    instance = load_scene(args.scene, args.tasks, args.satellites)
    command = [sys.executable, "-B", "-m", "satellite_scheduler.manual_worker", args.scene,
               str(args.tasks), str(args.satellites), args.combination, str(args.seed)]
    child = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    try:
        diagnostics = []
        while True:
            line = child.stderr.readline()
            if not line:
                raise RuntimeError("求解进程未发送就绪信号: " + b"".join(diagnostics).decode("utf-8", "replace"))
            if line.rstrip(b"\r\n") == b"P1_INSTANCE_READY":
                break
            diagnostics.append(line)
        print(f"P1_INSTANCE_READY:{child.pid}", file=sys.stderr, flush=True)
        if sys.stdin.buffer.read(1) != b"\x01":
            raise RuntimeError("invalid resource supervisor start signal")
        raw, errors = child.communicate(input=b"\x01")
        if diagnostics or errors:
            sys.stderr.buffer.write(b"".join(diagnostics) + errors)
            sys.stderr.buffer.flush()
        data, observations = validate_output(instance, raw)
        if (child.returncode == 0) != (data["status"] == "SUCCESS"):
            raise ValueError("求解状态与进程退出码不一致")
        detail = " ".join(data["detail"].split())[:500]
        profit = "null" if data["profit"] is None else str(data["profit"])
        print(f"status={data['status']} profit={profit} observations={len(observations)} "
              f"elapsedMs={data['elapsed_ns'] / 1_000_000:.3f} detail={detail}")
        for item in observations:
            satellite = instance.satellite(item.window.satellite_id)
            index = next(i for i, window in enumerate(satellite.windows) if window is item.window)
            print(f"task={item.task.id} satellite={satellite.id} window={index} "
                  f"start={item.start} end={item.end}")
        return 0 if data["status"] == "SUCCESS" else 1
    except (ValueError, UnicodeError, json.JSONDecodeError) as exc:
        print("status=FAILURE profit=null observations=0 elapsedMs=0 detail=independent_validation_failed")
        print(str(exc), file=sys.stderr)
        return 1
    finally:
        if child.poll() is None:
            child.kill()
            child.wait()


if __name__ == "__main__":
    raise SystemExit(main())
