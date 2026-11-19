"""读取来源场景格式；加载过程中不缓存或分配任务。"""

from __future__ import annotations

from pathlib import Path

from .model import AttitudeRecord, ProblemInstance, Satellite, Task, TimeWindow


def _seconds(hour: str, minute: str, second: str) -> float:
    return int(hour) * 3600 + int(minute) * 60 + float(second)


def _tasks(path: Path) -> list[Task]:
    tasks: list[Task] = []
    with path.open(encoding="utf-8") as file:
        for line_number, line in enumerate(file, 1):
            if line_number % 2 == 0:
                continue
            fields = line.split()
            if len(fields) < 5:
                raise ValueError(f"Invalid task format at line {line_number} in {path}")
            try:
                task = Task(int(fields[0]), float(fields[1]), float(fields[2]),
                            int(fields[3]), int(fields[4]))
            except ValueError as exc:
                raise ValueError(f"Invalid task number at line {line_number} in {path}") from exc
            if task.duration <= 0 or task.priority < 0:
                raise ValueError(f"Invalid task at line {line_number} in {path}")
            tasks.append(task)
    return tasks


def _windows(path: Path) -> list[tuple[int, float, float]]:
    windows: list[tuple[int, float, float]] = []
    current_task_id = 0
    with path.open(encoding="utf-8") as file:
        for line_number, line in enumerate(file, 1):
            fields = line.split()
            if not fields or fields[0].startswith("s"):
                continue
            try:
                if len(fields) == 1:
                    current_task_id = int(fields[0])
                elif len(fields) >= 12:
                    for index in (0, 1, 2, 6, 7, 8):
                        int(fields[index])
                    start = _seconds(fields[3], fields[4], fields[5])
                    end = _seconds(fields[9], fields[10], fields[11])
                    windows.append((current_task_id, start, end))
                else:
                    raise ValueError("expected 12 columns")
            except ValueError as exc:
                raise ValueError(f"Invalid time window at line {line_number} in {path}") from exc
    return windows


def _attitudes(path: Path) -> list[tuple[AttitudeRecord, ...]]:
    groups: list[tuple[AttitudeRecord, ...]] = []
    current: list[AttitudeRecord] = []
    with path.open(encoding="utf-8") as file:
        for line_number, line in enumerate(file, 1):
            fields = line.split()
            if len(fields) <= 1:
                if current:
                    groups.append(tuple(current))
                    current.clear()
                continue
            if len(fields) < 8:
                raise ValueError(f"Invalid attitude format at line {line_number} in {path}")
            try:
                for index in (0, 1, 2):
                    int(fields[index])
                current.append(AttitudeRecord(_seconds(fields[3], fields[4], fields[5]),
                                              float(fields[6]), float(fields[7])))
            except ValueError as exc:
                raise ValueError(f"Invalid attitude record at line {line_number} in {path}") from exc
    if current:
        groups.append(tuple(current))
    return groups


def load_scene(directory: str | Path, num_tasks: int, num_satellites: int) -> ProblemInstance:
    """读取完整场景并校验问题实例；每次调用都从原始文件读取。"""
    if num_tasks < 0 or num_satellites <= 0:
        raise ValueError("Invalid scene dimensions")
    directory = Path(directory)
    tasks = _tasks(directory / "tasklist.txt")
    if len(tasks) != num_tasks:
        raise ValueError(f"Expected {num_tasks} tasks, but loaded {len(tasks)}")
    satellites: list[Satellite] = []
    for satellite_id in range(num_satellites):
        raw_windows = _windows(directory / f"outputtimewindow_{satellite_id + 1}.txt")
        groups = _attitudes(directory / f"outputattitude_{satellite_id + 1}.txt")
        if len(raw_windows) != len(groups):
            raise ValueError(f"Time windows and attitude records count mismatch for satellite "
                             f"{satellite_id}: {len(raw_windows)} windows and {len(groups)} groups")
        windows = tuple(TimeWindow(satellite_id, task_id, start, end, records)
                        for (task_id, start, end), records in zip(raw_windows, groups, strict=True))
        satellites.append(Satellite(satellite_id, windows))
    return ProblemInstance(tuple(tasks), tuple(satellites))
