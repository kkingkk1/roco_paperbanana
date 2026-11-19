"""A/B/C 两阶段交互中传递的只读数据。"""

from __future__ import annotations

import time
from dataclasses import dataclass, field
from enum import Enum
from types import MappingProxyType
from typing import Callable, Mapping

from ..model import ScheduledTask


class Phase(Enum):
    INIT = "initial"
    REASSIGN = "reassignment"


class LocalStatus(Enum):
    APPLIED = "applied"
    NO_FEASIBLE_CHANGE = "no_feasible_change"


@dataclass(frozen=True, slots=True, eq=False)
class Assignment:
    owners: Mapping[int, int | None]

    def __post_init__(self) -> None:
        object.__setattr__(self, "owners", MappingProxyType(dict(self.owners)))


@dataclass(frozen=True, slots=True, eq=False)
class Move:
    base_state_hash: str
    edits: Mapping[int, int | None]
    repair_satellites: frozenset[int] = frozenset()
    full_recalibration: bool = False

    def __post_init__(self) -> None:
        object.__setattr__(self, "edits", MappingProxyType(dict(self.edits)))
        object.__setattr__(self, "repair_satellites", frozenset(self.repair_satellites))


@dataclass(frozen=True, slots=True, eq=False)
class State:
    assignment: Assignment
    timelines: Mapping[int, tuple[ScheduledTask, ...]]
    hash: str

    def __post_init__(self) -> None:
        object.__setattr__(self, "timelines", MappingProxyType(
            {key: tuple(value) for key, value in self.timelines.items()}))


@dataclass(frozen=True, slots=True, eq=False)
class SatelliteView:
    satellite_id: int
    assigned_task_ids: frozenset[int]
    observations: tuple[ScheduledTask, ...]
    hash: str

    def __post_init__(self) -> None:
        object.__setattr__(self, "assigned_task_ids", frozenset(self.assigned_task_ids))
        object.__setattr__(self, "observations", tuple(self.observations))


@dataclass(frozen=True, slots=True)
class BrokenEdge:
    previous_task_id: int
    next_task_id: int


@dataclass(frozen=True, slots=True, eq=False)
class LocalContext:
    phase: Phase
    incoming: frozenset[int] = frozenset()
    outgoing: frozenset[int] = frozenset()
    broken_edges: tuple[BrokenEdge, ...] = ()
    preferred_window_tasks: frozenset[int] = frozenset()

    def __post_init__(self) -> None:
        object.__setattr__(self, "incoming", frozenset(self.incoming))
        object.__setattr__(self, "outgoing", frozenset(self.outgoing))
        object.__setattr__(self, "broken_edges", tuple(self.broken_edges))
        object.__setattr__(self, "preferred_window_tasks", frozenset(self.preferred_window_tasks))


@dataclass(frozen=True, slots=True)
class Feedback:
    attempt: int
    previous_outcome: str

    def __post_init__(self) -> None:
        if self.attempt < 0:
            raise ValueError("negative attempt")


@dataclass(frozen=True, slots=True, eq=False)
class LocalPlan:
    base_view_hash: str
    release: frozenset[int]
    reconsider: tuple[int, ...]

    def __post_init__(self) -> None:
        object.__setattr__(self, "release", frozenset(self.release))
        object.__setattr__(self, "reconsider", tuple(self.reconsider))


@dataclass(frozen=True, slots=True, eq=False)
class LocalResult:
    status: LocalStatus
    observations: tuple[ScheduledTask, ...] = ()

    def __post_init__(self) -> None:
        object.__setattr__(self, "observations", tuple(self.observations))

    @staticmethod
    def unchanged() -> LocalResult:
        return LocalResult(LocalStatus.NO_FEASIBLE_CHANGE)

    @staticmethod
    def applied(observations: tuple[ScheduledTask, ...]) -> LocalResult:
        return LocalResult(LocalStatus.APPLIED, observations)


@dataclass(frozen=True, slots=True, eq=False)
class InitialTrial:
    priorities: tuple[int, ...]
    observations: tuple[ScheduledTask, ...]
    total_profit: int


@dataclass(frozen=True, slots=True, eq=False)
class LocalTrial:
    plan: LocalPlan
    result: LocalResult
    total_profit: int | None


@dataclass(frozen=True, slots=True, eq=False)
class MoveTrial:
    move: Move
    state: State | None
    total_profit: int | None

    def __post_init__(self) -> None:
        if (self.state is None) != (self.total_profit is None):
            raise ValueError("inconsistent move trial")


class SearchExpired(Exception):
    """搜索截止，但仍可检查并返回已接受方案。"""


class HardTimeout(Exception):
    """整个实例的硬截止。"""


@dataclass(frozen=True, slots=True)
class Limits:
    search_ns: int = 4_500_000_000
    hard_ns: int = 5_000_000_000
    initial_local_ns: int = 1_800_000_000
    per_satellite_ns: int = 20_000_000

    def __post_init__(self) -> None:
        if (self.search_ns <= 0 or self.hard_ns < self.search_ns or self.initial_local_ns < 0
                or self.initial_local_ns > self.search_ns or self.per_satellite_ns <= 0):
            raise ValueError("invalid solver limits")


@dataclass(frozen=True, slots=True)
class Budget:
    clock: Callable[[], int] = field(default=time.monotonic_ns, repr=False)
    start_ns: int = 0
    hard_ns: int = 5_000_000_000
    cutoff_ns: int = 4_500_000_000

    def elapsed_ns(self) -> int:
        return self.clock() - self.start_ns

    def check(self) -> None:
        elapsed = self.elapsed_ns()
        if elapsed >= self.hard_ns:
            raise HardTimeout()
        if elapsed >= self.cutoff_ns:
            raise SearchExpired()

    def check_hard(self) -> None:
        if self.elapsed_ns() >= self.hard_ns:
            raise HardTimeout()

    def within(self, duration_ns: int) -> Budget:
        return Budget(self.clock, self.start_ns, self.hard_ns,
                      min(self.cutoff_ns, self.elapsed_ns() + duration_ns))
