"""内部观测候选、姿态转换和完整方案验证。"""

from __future__ import annotations

import math
from dataclasses import dataclass
from typing import Iterable, Iterator

from .model import AttitudeRecord, ProblemInstance, ScheduledTask, TimeWindow


DURATION_EPSILON = 1e-6


def transformation_time(first: AttitudeRecord, second: AttitudeRecord) -> float:
    angle = abs(first.roll - second.roll) + abs(first.pitch - second.pitch)
    if angle <= 10:
        return 11.66
    if angle <= 30:
        return angle / 1.5 + 5
    if angle <= 60:
        return angle / 2 + 10
    if angle <= 90:
        return angle / 2.5 + 16
    return angle / 3 + 22


def transition_feasible(first: AttitudeRecord, second: AttitudeRecord) -> bool:
    return first.time < second.time and transformation_time(first, second) <= second.time - first.time


@dataclass(frozen=True, slots=True)
class ValidationResult:
    feasible: bool
    total_profit: int
    violations: tuple[str, ...]


def evaluate(instance: ProblemInstance, observations: Iterable[ScheduledTask]) -> ValidationResult:
    """独立检查显式观测，不增加、修补或重新排列调用方的方案。"""
    violations: list[str] = []
    seen_tasks: set[int] = set()
    per_satellite: dict[int, list[ScheduledTask]] = {}
    profit = 0
    for observation in observations:
        task_id = observation.task.id
        satellite_id = observation.window.satellite_id
        label = f"task={task_id}, satellite={satellite_id}: "
        task = instance.task(task_id)
        satellite = instance.satellite(satellite_id)
        window = observation.window
        if task_id in seen_tasks:
            violations.append(label + "DUPLICATE_TASK")
        seen_tasks.add(task_id)
        if task is not observation.task:
            violations.append(label + "TASK_NOT_IN_INSTANCE")
            continue
        if (satellite is None or window.task_id != task_id
                or not any(candidate is window for candidate in satellite.windows)):
            violations.append(label + "WINDOW_NOT_IN_INSTANCE")
            continue
        if (not any(record is observation.start_record for record in window.attitudes)
                or not any(record is observation.end_record for record in window.attitudes)):
            violations.append(label + "ATTITUDE_NOT_IN_WINDOW")
            continue
        if (not any(record is observation.start_record for record in window.attitudes[1:-1])
                or not any(record is observation.end_record for record in window.attitudes[1:-1])):
            violations.append(label + "ATTITUDE_OUTSIDE_INTERNAL_GRID")
            continue
        start, end = observation.start, observation.end
        if (not math.isfinite(start) or not math.isfinite(end) or end <= start
                or abs(end - start - task.duration) > DURATION_EPSILON):
            violations.append(label + "IMAGING_DURATION")
        if start < window.start or end > window.end:
            violations.append(label + "OUTSIDE_WINDOW")
        per_satellite.setdefault(satellite_id, []).append(observation)
        profit += task.priority
    for satellite_id, sequence in per_satellite.items():
        sequence.sort(key=lambda item: item.start)
        for previous, current in zip(sequence, sequence[1:]):
            if current.start < previous.end:
                violations.append(f"satellite={satellite_id}: OVERLAP")
            elif not transition_feasible(previous.end_record, current.start_record):
                violations.append(f"satellite={satellite_id}: ATTITUDE_TRANSITION")
    return ValidationResult(not violations, profit if not violations else 0, tuple(violations))


class ProblemAdapter:
    """只读候选视图；不在问题层选择任务或观测。"""

    def __init__(self, instance: ProblemInstance):
        self._instance = instance

    def grid(self, window: TimeWindow) -> tuple[AttitudeRecord, ...]:
        if not self._instance.contains_window(window):
            raise ValueError("Window is not in this instance")
        return window.attitudes[1:-1] if len(window.attitudes) >= 3 else ()

    def candidates(self, window: TimeWindow) -> Iterator[ScheduledTask]:
        records = self.grid(window)
        task = self._instance.task(window.task_id)
        if task is None:
            raise ValueError("Window task is not in this instance")
        lower_end = 1
        for start_index in range(len(records) - 1):
            first = records[start_index]
            lower_end = max(lower_end, start_index + 1)
            while (lower_end < len(records)
                   and records[lower_end].time - first.time - task.duration < -DURATION_EPSILON):
                lower_end += 1
            for end_index in range(lower_end, len(records)):
                last = records[end_index]
                difference = last.time - first.time - task.duration
                if difference > DURATION_EPSILON:
                    break
                if (abs(difference) <= DURATION_EPSILON and first.time >= window.start
                        and last.time <= window.end and last.time > first.time):
                    yield ScheduledTask(task, window, first, last)
