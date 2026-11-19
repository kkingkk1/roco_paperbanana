"""两阶段调度运行时；受控试评后整体接受或撤销修改。"""

from __future__ import annotations

import hashlib
import random
import struct
import threading
import time
from dataclasses import dataclass, replace
from types import MappingProxyType
from typing import Callable, Mapping

from ..model import ProblemInstance, ScheduledTask
from ..problem import evaluate, transition_feasible
from .access import ProblemAccess
from .contracts import (
    Assignment, BrokenEdge, Budget, Feedback, HardTimeout, InitialTrial, Limits, LocalContext,
    LocalPlan, LocalResult, LocalStatus, LocalTrial, Move, MoveTrial, Phase, SatelliteView,
    SearchExpired, State,
)


class InvalidOutput(Exception):
    """策略返回的结果违反接口或编辑权限。"""


@dataclass(slots=True)
class Statistics:
    initial_assignments: int = 0
    initial_priorities: int = 0
    initial_builds: int = 0
    initial_local_calls: int = 0
    reassign_moves: int = 0
    reassign_local_calls: int = 0
    accepted_moves: int = 0
    rejected_moves: int = 0
    no_feasible_changes: int = 0


@dataclass(frozen=True, slots=True)
class SolveResult:
    status: str
    observations: tuple[ScheduledTask, ...] | None
    total_profit: int | None
    owners: Mapping[int, int | None]
    statistics: Statistics
    detail: str
    elapsed_ns: int


class _TrialGuard:
    """同一调用只接收本次试评签发的对象；关闭后不能继续试评。"""

    def __init__(self, budget: Budget):
        self.budget = budget
        self._lock = threading.RLock()
        self._open = True
        self._failure: Exception | None = None
        self._issued: dict[int, object] = {}

    def evaluate(self, action: Callable[[], object]):
        with self._lock:
            self._check_open()
            if self._failure is not None:
                raise self._failure
            try:
                self.budget.check()
                trial = action()
                if trial is None:
                    raise InvalidOutput("null candidate trial")
                self.budget.check()
                self._issued[id(trial)] = trial
                return trial
            except Exception as exc:
                self._failure = exc
                raise

    def select(self, trial, required: bool = False):
        with self._lock:
            self._check_open()
            self.budget.check()
            if self._failure is not None:
                raise self._failure
            if (trial is None and required) or (trial is not None and self._issued.get(id(trial)) is not trial):
                raise InvalidOutput("candidate not issued by the current evaluator")
            return trial

    def close(self) -> None:
        with self._lock:
            self._open = False

    def _check_open(self) -> None:
        if not self._open:
            raise InvalidOutput("evaluator is closed")


def _order(observation: ScheduledTask) -> tuple[float, float, int]:
    return observation.start, observation.end, observation.task.id


def _bits(value: float) -> int:
    return struct.unpack(">q", struct.pack(">d", value))[0]


def _append_observation(parts: list[str], observation: ScheduledTask, api: ProblemAccess) -> None:
    parts.append(f"O{observation.task.id}:{api.window_index(observation.window)}:"
                 f"{_bits(observation.start)}:{_bits(observation.end)};")


def _state(assignment: Assignment, timelines: Mapping[int, tuple[ScheduledTask, ...]],
           api: ProblemAccess | None) -> State:
    parts = [f"T{task}:{'null' if assignment.owners[task] is None else assignment.owners[task]};"
             for task in sorted(assignment.owners)]
    for sat in sorted(timelines):
        parts.append(f"S{sat}:")
        for observation in sorted(timelines[sat], key=_order):
            if api is None:
                raise InvalidOutput("cannot hash observation without problem access")
            _append_observation(parts, observation, api)
    return State(assignment, timelines, hashlib.sha256("".join(parts).encode("utf-8")).hexdigest())


def _view(state: State, satellite: int, api: ProblemAccess) -> SatelliteView:
    assigned = frozenset(task for task, owner in state.assignment.owners.items() if owner == satellite)
    observations = state.timelines[satellite]
    parts = [f"S{satellite}:"] + [f"T{task};" for task in sorted(assigned)]
    for observation in sorted(observations, key=_order):
        _append_observation(parts, observation, api)
    return SatelliteView(satellite, assigned, observations,
                         hashlib.sha256("".join(parts).encode("utf-8")).hexdigest())


def _replace_timeline(state: State, satellite: int, observations, api: ProblemAccess) -> State:
    timelines = dict(state.timelines)
    timelines[satellite] = tuple(sorted(observations, key=_order))
    return _state(state.assignment, timelines, api)


def _profit(observations) -> int:
    return sum(item.task.priority for item in observations)


def _state_profit(state: State) -> int:
    return sum(_profit(timeline) for timeline in state.timelines.values())


def _validate_timeline(problem: ProblemInstance, assignment: Assignment, satellite: int, observations) -> None:
    if observations is None:
        raise InvalidOutput("null timeline")
    for item in observations:
        if (not isinstance(item, ScheduledTask) or item.window.satellite_id != satellite
                or assignment.owners.get(item.task.id) != satellite):
            raise InvalidOutput("timeline violates owner or satellite")
    result = evaluate(problem, observations)
    if not result.feasible:
        raise InvalidOutput(f"infeasible timeline: {result.violations}")


def _validate_priorities(view: SatelliteView, priorities) -> tuple[int, ...]:
    if priorities is None:
        raise InvalidOutput("invalid initial priorities")
    priorities = tuple(priorities)
    if (any(type(item) is not int for item in priorities) or len(set(priorities)) != len(priorities)
            or not view.assigned_task_ids.issuperset(priorities)):
        raise InvalidOutput("invalid initial priorities")
    return priorities


def _validate_local_plan(view: SatelliteView, plan: LocalPlan) -> None:
    if not isinstance(plan, LocalPlan) or plan.base_view_hash != view.hash:
        raise InvalidOutput("stale local plan")
    existing = {item.task.id for item in view.observations}
    if (not existing.issuperset(plan.release) or not view.assigned_task_ids.issuperset(plan.reconsider)
            or len(set(plan.reconsider)) != len(plan.reconsider)):
        raise InvalidOutput("invalid release or reconsider set")
    if any(task in existing and task not in plan.release for task in plan.reconsider):
        raise InvalidOutput("scheduled task reconsidered without release")


def _same_observation(first: ScheduledTask, second: ScheduledTask) -> bool:
    return (first.task is second.task and first.window is second.window
            and first.start_record is second.start_record and first.end_record is second.end_record)


def _inspect_local_result(problem: ProblemInstance, assignment: Assignment, view: SatelliteView,
                          plan: LocalPlan, result: LocalResult) -> None:
    if not isinstance(result, LocalResult):
        raise InvalidOutput("invalid local result")
    if result.status == LocalStatus.NO_FEASIBLE_CHANGE:
        if result.observations:
            raise InvalidOutput("no-change result contains observations")
        return
    if result.status != LocalStatus.APPLIED:
        raise InvalidOutput("invalid local result status")
    _validate_timeline(problem, assignment, view.satellite_id, result.observations)
    before = {item.task.id: item for item in view.observations}
    after = {item.task.id: item for item in result.observations}
    for task, original in before.items():
        changed = after.get(task)
        if changed is None and task not in plan.release:
            raise InvalidOutput("C removed an unauthorized observation")
        if (changed is not None and not _same_observation(original, changed)
                and (task not in plan.release or task not in plan.reconsider)):
            raise InvalidOutput("C changed an unauthorized observation")
    if any(task not in before and task not in plan.reconsider for task in after):
        raise InvalidOutput("C added an unauthorized observation")


def _broken_edges(observations: tuple[ScheduledTask, ...]) -> tuple[BrokenEdge, ...]:
    ordered = sorted(observations, key=_order)
    return tuple(BrokenEdge(before.task.id, after.task.id)
                 for before, after in zip(ordered, ordered[1:])
                 if after.start < before.end or not transition_feasible(before.end_record, after.start_record))


def _instance_key(problem: ProblemInstance, budget: Budget) -> int:
    mask = (1 << 64) - 1
    value = 0xcbf29ce484222325

    def mix(item: int) -> None:
        nonlocal value
        value = ((value ^ (item & mask)) * 0x100000001b3) & mask

    for task in sorted(problem.tasks, key=lambda item: item.id):
        budget.check()
        for item in (task.id, _bits(task.longitude), _bits(task.latitude), task.priority, task.duration):
            mix(item)
    for satellite in sorted(problem.satellites, key=lambda item: item.id):
        budget.check()
        mix(satellite.id)
        mix(len(satellite.windows))
        for window in satellite.windows:
            budget.check()
            for item in (window.task_id, _bits(window.start), _bits(window.end), len(window.attitudes)):
                mix(item)
            for record in window.attitudes:
                budget.check()
                for item in (_bits(record.time), _bits(record.roll), _bits(record.pitch)):
                    mix(item)
    return value


def _random(seed: int, phase: Phase, module: int, move: int, satellite: int, call: int) -> random.Random:
    mask = (1 << 64) - 1
    value = seed ^ ((0 if phase is Phase.INIT else 1) << 61) ^ (module << 56)
    value ^= 0x9e3779b97f4a7c15 * (move + 1)
    value ^= 0xbf58476d1ce4e5b9 * (satellite + 2)
    value ^= 0x94d049bb133111eb * (call + 1)
    return random.Random(value & mask)


def _trial_random(seed: int, phase: Phase, move: int, satellite: int, call: int, trial: int) -> random.Random:
    return _random(seed ^ (0xd1342543de82ef95 * trial), phase, 3, move, satellite, call)


class TwoStageSolver:
    def __init__(self, limits: Limits | None = None, clock: Callable[[], int] = time.monotonic_ns):
        self.limits = limits or Limits()
        self.clock = clock

    def solve(self, problem: ProblemInstance, a, b, c, seed: int) -> SolveResult:
        started = self.clock()
        budget = Budget(self.clock, started, self.limits.hard_ns, self.limits.search_ns)
        stats = Statistics()
        owners = {task.id: None for task in sorted(problem.tasks, key=lambda item: item.id)}
        timelines = {sat.id: () for sat in sorted(problem.satellites, key=lambda item: item.id)}
        accepted = _state(Assignment(owners), timelines, None)
        checkpoint: list[State | None] = [None]
        try:
            stream_seed = seed ^ _instance_key(problem, budget)
            api = ProblemAccess(problem, budget)
            budget.check()
            stats.initial_assignments += 1
            initial = a.initial_assign(problem, api, budget, _random(stream_seed, Phase.INIT, 1, 0, -1, 0))
            budget.check()
            self._validate_assignment(problem, api, initial, budget)
            accepted = _state(initial, timelines, api)
            if hasattr(a, "prepare"):
                a.prepare(problem, api, initial, budget, seed)
            if hasattr(b, "prepare"):
                b.prepare(problem, api, initial, budget, seed)
            budget.check()
            empty_state = accepted
            prime_initial = getattr(b, "prime_initial", None)
            if prime_initial is not None:
                for sat in api.satellite_ids:
                    view = _view(empty_state, sat, api)
                    if view.assigned_task_ids:
                        selected = self._initial(problem, api, initial, view, prime_initial, c,
                                                 budget, stream_seed, stats)
                        accepted = _replace_timeline(accepted, sat, selected.observations, api)
            for sat in api.satellite_ids:
                budget.check()
                # 初始构建始终接收空时间轴，已保存的种群基准只用于截止返回。
                view = _view(empty_state, sat, api)
                stats.initial_priorities += 1
                selected = self._initial(problem, api, initial, view, b.search_initial, c,
                                         budget, stream_seed, stats)
                accepted = _replace_timeline(accepted, sat, selected.observations, api)
            per_initial = self.limits.initial_local_ns // len(api.satellite_ids) if api.satellite_ids else 0
            for sat in api.satellite_ids:
                if not per_initial:
                    break
                budget.check()
                local = budget.within(per_initial)
                attempt, previous = 0, "START"
                try:
                    while True:
                        local.check()
                        view = _view(accepted, sat, api)
                        context = LocalContext(Phase.INIT)
                        stats.initial_local_calls += 1
                        selected = self._local(problem, api, initial, view, context, Feedback(attempt, previous),
                                               b, c, local, stream_seed, 0, attempt + 1, False)
                        if selected is None:
                            break
                        result = selected.result
                        if result.status is LocalStatus.NO_FEASIBLE_CHANGE:
                            stats.no_feasible_changes += 1
                            previous = "NO_FEASIBLE_CHANGE"
                        elif _profit(result.observations) > _profit(view.observations):
                            accepted = _replace_timeline(accepted, sat, result.observations, api)
                            previous = "ACCEPTED"
                        else:
                            previous = "NO_IMPROVEMENT"
                        attempt += 1
                except SearchExpired:
                    budget.check()
            attempt, previous = 0, "START"
            while True:
                budget.check()
                checkpoint[0] = accepted
                selected = self._reassignment(problem, api, accepted, Feedback(attempt, previous),
                                              a, b, c, budget, stream_seed, stats, checkpoint)
                budget.check()
                if selected is None:
                    break
                if selected.state is not None:
                    accepts_non_improving = getattr(a, "accepts_non_improving_result", lambda: False)()
                    if selected.total_profit > _state_profit(accepted) or accepts_non_improving:
                        accepted = selected.state
                        if hasattr(b, "on_accepted"):
                            b.on_accepted(problem, api, accepted, budget)
                        budget.check()
                        stats.accepted_moves += 1
                        previous, attempt = "ACCEPTED", 0
                    else:
                        stats.rejected_moves += 1
                        previous, attempt = "NO_IMPROVEMENT", attempt + 1
                else:
                    stats.rejected_moves += 1
                    previous, attempt = "NO_FEASIBLE_CHANGE", attempt + 1
            return self._finish(problem, accepted, stats, budget, "COMPLETED")
        except SearchExpired:
            try:
                return self._finish(problem, checkpoint[0] or accepted, stats, budget, "SEARCH_EXPIRED")
            except HardTimeout:
                return self._failed("HARD_TIMEOUT", accepted, stats, budget, "hard deadline exceeded")
        except HardTimeout:
            return self._failed("HARD_TIMEOUT", accepted, stats, budget, "hard deadline exceeded")
        except Exception as exc:
            try:
                budget.check_hard()
            except HardTimeout:
                return self._failed("HARD_TIMEOUT", accepted, stats, budget, "hard deadline exceeded")
            return self._failed("FAILURE", accepted, stats, budget, f"{type(exc).__name__}: {exc}")

    @staticmethod
    def _validate_assignment(problem: ProblemInstance, api: ProblemAccess,
                             assignment: Assignment, budget: Budget) -> None:
        if not isinstance(assignment, Assignment) or set(assignment.owners) != {task.id for task in problem.tasks}:
            raise InvalidOutput("assignment task set differs")
        for task, sat in assignment.owners.items():
            budget.check()
            if sat is not None and (problem.satellite(sat) is None or not api.has_candidate(task, sat, budget)):
                raise InvalidOutput("assigned satellite has no candidate")

    def _initial(self, problem, api, assignment, view, search, c, budget, seed, stats):
        guard = _TrialGuard(budget)
        calls = 0

        class Evaluator:
            def evaluate(self, priorities):
                def perform():
                    nonlocal calls
                    order = _validate_priorities(view, priorities)
                    stats.initial_builds += 1
                    rng = _trial_random(seed, Phase.INIT, 0, view.satellite_id, 0, calls)
                    calls += 1
                    built = tuple(c.build(problem, api, view, order, budget, rng))
                    _validate_timeline(problem, assignment, view.satellite_id, built)
                    if any(item.task.id not in order for item in built):
                        raise InvalidOutput("C.build added a task outside priorities")
                    return InitialTrial(order, built, _profit(built))
                return guard.evaluate(perform)

        try:
            trial = search(problem, api, view, Evaluator(), budget,
                           _random(seed, Phase.INIT, 2, 0, view.satellite_id, 0))
            return guard.select(trial, True)
        finally:
            guard.close()

    def _local(self, problem, api, assignment, view, context, feedback, b, c, budget,
               seed, move_number, call, calibration):
        guard = _TrialGuard(budget)
        calls = 0

        class Evaluator:
            def evaluate(self, plan):
                def perform():
                    nonlocal calls
                    _validate_local_plan(view, plan)
                    rng = _trial_random(seed, context.phase, move_number, view.satellite_id, call, calls)
                    calls += 1
                    result = c.apply_local(problem, api, view, context, plan, budget, rng)
                    _inspect_local_result(problem, assignment, view, plan, result)
                    profit = _profit(result.observations) if result.status is LocalStatus.APPLIED else None
                    return LocalTrial(plan, result, profit)
                return guard.evaluate(perform)

            def rebuild(self, priorities, preferred_window_tasks=frozenset()):
                def perform():
                    nonlocal calls
                    order = _validate_priorities(view, priorities)
                    preferred = frozenset(preferred_window_tasks)
                    if not view.assigned_task_ids.issuperset(preferred):
                        raise InvalidOutput("invalid window preference tasks")
                    plan = LocalPlan(view.hash, frozenset(item.task.id for item in view.observations), order)
                    _validate_local_plan(view, plan)
                    empty = SatelliteView(view.satellite_id, view.assigned_task_ids, (),
                                          hashlib.sha256((f"S{view.satellite_id}:" + "".join(
                                              f"T{task};" for task in sorted(view.assigned_task_ids)))
                                              .encode("utf-8")).hexdigest())
                    rebuild_context = LocalContext(context.phase, preferred_window_tasks=preferred)
                    rng = _trial_random(seed, context.phase, move_number, view.satellite_id, call, calls)
                    calls += 1
                    if context.phase is Phase.INIT:
                        built = tuple(c.build(problem, api, empty, order, budget, rng))
                    else:
                        built = tuple(c.rebuild(problem, api, empty, rebuild_context, order, budget, rng))
                    _validate_timeline(problem, assignment, view.satellite_id, built)
                    if any(item.task.id not in order for item in built):
                        raise InvalidOutput("C rebuild added a task outside priorities")
                    result = LocalResult.applied(built)
                    _inspect_local_result(problem, assignment, view, plan, result)
                    return LocalTrial(plan, result, _profit(built))
                return guard.evaluate(perform)

        try:
            rng = _random(seed, context.phase, 2, move_number, view.satellite_id, call)
            if context.phase is Phase.INIT:
                selected = b.search_initial_local(problem, api, view, context, feedback, Evaluator(), budget, rng)
            elif calibration and hasattr(b, "search_calibration"):
                selected = b.search_calibration(problem, api, view, context, feedback, Evaluator(), budget, rng)
            else:
                selected = b.search_reassignment(problem, api, view, context, feedback, Evaluator(), budget, rng)
            return guard.select(selected)
        finally:
            guard.close()

    def _reassignment(self, problem, api, root, feedback, a, b, c, budget, seed, stats, checkpoint):
        guard = _TrialGuard(budget)
        issued = {id(root): root}
        solver = self

        class Evaluator:
            def evaluate(self, base, move):
                def perform():
                    if issued.get(id(base)) is not base:
                        raise InvalidOutput("A used a state not issued by this evaluator")
                    if not isinstance(move, Move):
                        raise InvalidOutput("invalid move")
                    number = stats.reassign_moves
                    stats.reassign_moves += 1
                    if hasattr(b, "on_trial_base"):
                        b.on_trial_base(problem, api, base, budget)
                    candidate = solver._try_move(problem, api, base, move, b, c, budget, seed, number, stats)
                    if candidate is None:
                        return MoveTrial(move, None, None)
                    checked = evaluate(problem, (item for row in candidate.timelines.values() for item in row))
                    budget.check()
                    if not checked.feasible:
                        return MoveTrial(move, None, None)
                    issued[id(candidate)] = candidate
                    return MoveTrial(move, candidate, checked.total_profit)
                return guard.evaluate(perform)

            def checkpoint(self, trial):
                def perform():
                    guard.select(trial, True)
                    if trial.state is None:
                        raise InvalidOutput("cannot checkpoint an infeasible trial")
                    if trial.total_profit > _state_profit(checkpoint[0]):
                        checkpoint[0] = trial.state
                    return trial
                guard.evaluate(perform)

            def calibrate(self, base):
                return self.evaluate(base, Move(base.hash, {}, frozenset(base.timelines), True))

        try:
            trial = a.search_reassignment(problem, api, root, feedback, Evaluator(), budget,
                                          _random(seed, Phase.REASSIGN, 1, stats.reassign_moves, -1,
                                                  feedback.attempt))
            return guard.select(trial)
        finally:
            guard.close()

    def _try_move(self, problem, api, base, move, b, c, search, seed, move_number, stats):
        if base.hash != move.base_state_hash:
            raise InvalidOutput("stale move")
        if not move.edits and not move.repair_satellites:
            raise InvalidOutput("empty move")
        if move.full_recalibration and (move.edits or move.repair_satellites != set(base.timelines)):
            raise InvalidOutput("invalid full recalibration")
        if move.full_recalibration and hasattr(b, "begin_calibration"):
            b.begin_calibration(problem, api, base, search)
        owners = dict(base.assignment.owners)
        affected = set(move.repair_satellites)
        incoming: dict[int, set[int]] = {}
        outgoing: dict[int, set[int]] = {}
        for sat in affected:
            search.check()
            if problem.satellite(sat) is None:
                raise InvalidOutput("unknown repair satellite")
        for task, target in move.edits.items():
            search.check()
            if task not in owners:
                raise InvalidOutput("move has unknown task")
            source = owners[task]
            if source == target:
                raise InvalidOutput("move has unchanged owner")
            if target is not None and (problem.satellite(target) is None or not api.has_candidate(task, target, search)):
                raise InvalidOutput("move owner has no legal candidate")
            if source is not None:
                affected.add(source)
                outgoing.setdefault(source, set()).add(task)
            if target is not None:
                affected.add(target)
                incoming.setdefault(target, set()).add(task)
            owners[task] = target
        assignment = Assignment(owners)
        timelines = dict(base.timelines)
        for sat in affected:
            leaving = outgoing.get(sat, set())
            timelines[sat] = tuple(item for item in timelines[sat] if item.task.id not in leaving)
        trial = _state(assignment, timelines, api)
        for sat in sorted(affected):
            search.check()
            uses_instance = getattr(b, "uses_instance_budget_for_reassignment", lambda: False)()
            local = search if uses_instance else search.within(self.limits.per_satellite_ns)
            attempt, previous = 0, "START"
            try:
                while True:
                    local.check()
                    view = _view(trial, sat, api)
                    context = LocalContext(Phase.REASSIGN, frozenset(incoming.get(sat, ())),
                                           frozenset(outgoing.get(sat, ())), _broken_edges(view.observations))
                    stats.reassign_local_calls += 1
                    selected = self._local(problem, api, assignment, view, context, Feedback(attempt, previous),
                                           b, c, local, seed, move_number, attempt, move.full_recalibration)
                    if selected is None:
                        break
                    if selected.result.status is LocalStatus.NO_FEASIBLE_CHANGE:
                        stats.no_feasible_changes += 1
                        previous = "NO_FEASIBLE_CHANGE"
                    else:
                        previous = "APPLIED"
                        trial = _replace_timeline(trial, sat, selected.result.observations, api)
                    attempt += 1
            except SearchExpired:
                search.check()
                return None
        return trial

    def _finish(self, problem, state, stats, budget, detail) -> SolveResult:
        budget.check_hard()
        observations = tuple(item for row in state.timelines.values() for item in row)
        result = evaluate(problem, observations)
        budget.check_hard()
        if not result.feasible:
            return self._failed("FAILURE", state, stats, budget, "final validation failed")
        return SolveResult("SUCCESS", observations, result.total_profit, MappingProxyType(dict(state.assignment.owners)),
                           replace(stats), detail, budget.elapsed_ns())

    @staticmethod
    def _failed(status, state, stats, budget, detail) -> SolveResult:
        return SolveResult(status, None, None, MappingProxyType(dict(state.assignment.owners)),
                           replace(stats), detail, budget.elapsed_ns())
