"""只读的问题输入与显式观测，不包含调度策略。"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from types import MappingProxyType
from typing import Mapping


@dataclass(frozen=True, slots=True, eq=False)
class AttitudeRecord:
    time: float
    roll: float
    pitch: float


@dataclass(frozen=True, slots=True, eq=False)
class Task:
    id: int
    longitude: float
    latitude: float
    priority: int
    duration: int


@dataclass(frozen=True, slots=True, eq=False)
class TimeWindow:
    satellite_id: int
    task_id: int
    start: float
    end: float
    attitudes: tuple[AttitudeRecord, ...] = ()

    def __post_init__(self) -> None:
        object.__setattr__(self, "attitudes", tuple(self.attitudes))


@dataclass(frozen=True, slots=True, eq=False)
class Satellite:
    id: int
    windows: tuple[TimeWindow, ...] = ()

    def __post_init__(self) -> None:
        object.__setattr__(self, "windows", tuple(self.windows))


@dataclass(frozen=True, slots=True, eq=False)
class ScheduledTask:
    task: Task
    window: TimeWindow
    start_record: AttitudeRecord
    end_record: AttitudeRecord

    @property
    def start(self) -> float:
        return self.start_record.time

    @property
    def end(self) -> float:
        return self.end_record.time


@dataclass(frozen=True, slots=True, eq=False)
class ProblemInstance:
    tasks: tuple[Task, ...]
    satellites: tuple[Satellite, ...]
    _tasks_by_id: Mapping[int, Task] = field(init=False, repr=False)
    _satellites_by_id: Mapping[int, Satellite] = field(init=False, repr=False)
    _windows_by_pair: Mapping[tuple[int, int], tuple[TimeWindow, ...]] = field(init=False, repr=False)
    _window_ids: frozenset[int] = field(init=False, repr=False)

    def __post_init__(self) -> None:
        tasks = tuple(self.tasks)
        satellites = tuple(self.satellites)
        tasks_by_id: dict[int, Task] = {}
        satellites_by_id: dict[int, Satellite] = {}
        windows_by_pair: dict[tuple[int, int], list[TimeWindow]] = {}
        for task in tasks:
            if (task.id < 0 or task.duration <= 0 or task.priority < 0
                    or not math.isfinite(task.longitude) or not math.isfinite(task.latitude)
                    or task.id in tasks_by_id):
                raise ValueError(f"Invalid or duplicate task: {task.id}")
            tasks_by_id[task.id] = task
        window_ids: set[int] = set()
        for satellite in satellites:
            if satellite.id < 0 or satellite.id in satellites_by_id:
                raise ValueError(f"Invalid or duplicate satellite: {satellite.id}")
            satellites_by_id[satellite.id] = satellite
            for window in satellite.windows:
                if (window.satellite_id != satellite.id or window.task_id not in tasks_by_id
                        or not math.isfinite(window.start) or not math.isfinite(window.end)
                        or window.start < 0 or window.end < 0):
                    raise ValueError(f"Invalid window: {window}")
                _validate_attitudes(window)
                windows_by_pair.setdefault((window.task_id, satellite.id), []).append(window)
                window_ids.add(id(window))
        object.__setattr__(self, "tasks", tasks)
        object.__setattr__(self, "satellites", satellites)
        object.__setattr__(self, "_tasks_by_id", MappingProxyType(tasks_by_id))
        object.__setattr__(self, "_satellites_by_id", MappingProxyType(satellites_by_id))
        object.__setattr__(self, "_windows_by_pair", MappingProxyType(
            {key: tuple(value) for key, value in windows_by_pair.items()}))
        object.__setattr__(self, "_window_ids", frozenset(window_ids))

    def task(self, task_id: int) -> Task | None:
        return self._tasks_by_id.get(task_id)

    def satellite(self, satellite_id: int) -> Satellite | None:
        return self._satellites_by_id.get(satellite_id)

    def windows_for(self, task_id: int, satellite_id: int) -> tuple[TimeWindow, ...]:
        return self._windows_by_pair.get((task_id, satellite_id), ())

    def contains_window(self, window: TimeWindow) -> bool:
        return id(window) in self._window_ids


def _validate_attitudes(window: TimeWindow) -> None:
    previous = -math.inf
    first_internal = second_internal = internal_step = 0.0
    records = window.attitudes
    for i, record in enumerate(records):
        if (not math.isfinite(record.time) or not math.isfinite(record.roll)
                or not math.isfinite(record.pitch) or record.time <= previous):
            raise ValueError(f"Invalid attitude records: {window.task_id}/{window.satellite_id}")
        if i == 1:
            first_internal = record.time
        elif i == 2 and i < len(records) - 1:
            second_internal = record.time
            internal_step = second_internal - first_internal
            if not math.isfinite(internal_step):
                raise ValueError("Invalid internal interval")
        elif i > 2 and i < len(records) - 1:
            scale = max(abs(first_internal), abs(second_internal), abs(previous), abs(record.time))
            if abs((record.time - previous) - internal_step) > 8 * math.ulp(scale):
                raise ValueError(f"Unequal internal interval at raw index {i}")
        previous = record.time
