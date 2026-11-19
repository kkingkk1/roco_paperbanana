"""方法层的只读候选访问；统计与预处理计入实例预算。"""

from __future__ import annotations

from collections.abc import Iterator

from ..model import ProblemInstance, ScheduledTask, TimeWindow
from ..problem import ProblemAdapter
from .contracts import Budget


class ProblemAccess:
    def __init__(self, instance: ProblemInstance, budget: Budget):
        budget.check()
        self._instance = instance
        self._adapter = ProblemAdapter(instance)
        self._counts: dict[tuple[int, int], int] = {}
        self._capacities: dict[int, float] = {}
        self._window_indices: dict[int, int] = {}
        self.satellite_ids = tuple(sorted(satellite.id for satellite in instance.satellites))
        index = 0
        for satellite in instance.satellites:
            budget.check()
            for window in satellite.windows:
                budget.check()
                self._window_indices.setdefault(id(window), index)
                index += 1

    def windows(self, task_id: int, satellite_id: int) -> tuple[TimeWindow, ...]:
        return self._instance.windows_for(task_id, satellite_id)

    def candidates(self, task_id: int, satellite_id: int, budget: Budget) -> Iterator[ScheduledTask]:
        for window in self.windows(task_id, satellite_id):
            budget.check()
            for candidate in self._adapter.candidates(window):
                budget.check()
                yield candidate
        budget.check()

    def candidate_count(self, task_id: int, satellite_id: int, budget: Budget) -> int:
        key = task_id, satellite_id
        if key not in self._counts:
            self._counts[key] = sum(1 for _ in self.candidates(task_id, satellite_id, budget))
        return self._counts[key]

    def has_candidate(self, task_id: int, satellite_id: int, budget: Budget) -> bool:
        key = task_id, satellite_id
        if key in self._counts:
            return self._counts[key] > 0
        return next(self.candidates(task_id, satellite_id, budget), None) is not None

    def capacity(self, satellite_id: int, budget: Budget) -> float:
        if satellite_id not in self._capacities:
            satellite = self._instance.satellite(satellite_id)
            if satellite is None:
                raise ValueError(f"Unknown satellite: {satellite_id}")
            total = 0.0
            for window in satellite.windows:
                budget.check()
                if next(self._adapter.candidates(window), None) is not None:
                    total += max(0.0, window.end - window.start)
            self._capacities[satellite_id] = total
        return self._capacities[satellite_id]

    def window_index(self, window: TimeWindow) -> int:
        try:
            return self._window_indices[id(window)]
        except KeyError as exc:
            raise ValueError("window not in instance") from exc
