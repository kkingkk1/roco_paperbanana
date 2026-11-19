"""P1 的两组手工 A/B/C 策略。"""

from __future__ import annotations

import math
import re
from collections.abc import Iterator
from dataclasses import dataclass

from ..model import ProblemInstance, ScheduledTask
from ..problem import evaluate, transformation_time, transition_feasible
from .access import ProblemAccess
from .contracts import (
    Assignment, Budget, Feedback, LocalContext, LocalPlan, LocalResult, Move,
    SatelliteView, State,
)
from .rkhma_allocation import RkhmaAllocation
from .rkhma_decoder import RkhmaGreedyDecoder
from .rkhma_search import RkhmaHybridSearch


class _Allocation:
    def __init__(self, scarcity: bool):
        self.scarcity = scarcity
        self._cursor_hash: str | None = None
        self._moves: Iterator[Move] = iter(())

    def initial_assign(self, problem: ProblemInstance, api: ProblemAccess, budget: Budget, rng) -> Assignment:
        self._cursor_hash = None
        ids = sorted(task.id for task in problem.tasks)
        owners: dict[int, int | None] = {task_id: None for task_id in ids}
        load: dict[int, float] = {}
        if self.scarcity:
            eligible: dict[int, list[int]] = {}
            for task_id in ids:
                budget.check()
                eligible[task_id] = [sat for sat in api.satellite_ids
                                     if api.has_candidate(task_id, sat, budget)]
            for task_id in sorted(ids, key=lambda item: (len(eligible[item]), item)):
                budget.check()
                task = problem.task(task_id)
                best = min(eligible[task_id], key=lambda sat: (
                    (load.get(sat, 0.0) + task.duration) / max(1e-12, api.capacity(sat, budget)), sat),
                    default=None)
                owners[task_id] = best
                if best is not None:
                    load[best] = load.get(best, 0.0) + task.duration
        else:
            for task_id in ids:
                budget.check()
                choices = [sat for sat in api.satellite_ids if api.has_candidate(task_id, sat, budget)]
                best = min(choices, key=lambda sat: (-api.candidate_count(task_id, sat, budget),
                                                     load.get(sat, 0.0), sat), default=None)
                owners[task_id] = best
                if best is not None:
                    load[best] = load.get(best, 0.0) + problem.task(task_id).duration
        return Assignment(owners)

    def prepare(self, problem: ProblemInstance, api: ProblemAccess, assignment: Assignment,
                budget: Budget, seed: int) -> None:
        pass

    def _proposals(self, problem: ProblemInstance, api: ProblemAccess, state: State,
                   budget: Budget) -> Iterator[Move]:
        scheduled = {observation.task.id for timeline in state.timelines.values() for observation in timeline}
        if self.scarcity:
            tasks = sorted(state.assignment.owners, key=lambda item: (-problem.task(item).priority, item))
            pending = [task for task in tasks if task not in scheduled]
            alternatives = {}
            for task in pending:
                count = 0
                for sat in api.satellite_ids:
                    budget.check()
                    if state.assignment.owners[task] != sat and api.has_candidate(task, sat, budget):
                        count += 1
                alternatives[task] = count
            pending.sort(key=lambda item: (alternatives[item], -problem.task(item).priority, item))
            normalized = {}
            for sat in api.satellite_ids:
                budget.check()
                occupied = sum(item.end - item.start for item in state.timelines.get(sat, ()))
                normalized[sat] = occupied / max(1e-12, api.capacity(sat, budget))
            satellites = sorted(api.satellite_ids, key=lambda sat: (normalized[sat], sat))
        else:
            tasks = sorted(state.assignment.owners)
            pending = sorted((task for task in tasks if task not in scheduled),
                             key=lambda item: (-problem.task(item).priority, item))
            satellites = api.satellite_ids
        for task in pending:
            for target in satellites:
                budget.check()
                if state.assignment.owners[task] != target and api.has_candidate(task, target, budget):
                    yield Move(state.hash, {task: target})
        for index, first in enumerate(tasks):
            for second in tasks[index + 1:]:
                budget.check()
                owner1, owner2 = state.assignment.owners[first], state.assignment.owners[second]
                if (owner1 is not None and owner2 is not None and owner1 != owner2
                        and api.has_candidate(first, owner2, budget)
                        and api.has_candidate(second, owner1, budget)):
                    yield Move(state.hash, {first: owner2, second: owner1})

    def search_reassignment(self, problem: ProblemInstance, api: ProblemAccess, state: State,
                            feedback: Feedback, evaluator, budget: Budget, rng):
        if state.hash != self._cursor_hash:
            self._cursor_hash = state.hash
            self._moves = self._proposals(problem, api, state, budget)
        move = next(self._moves, None)
        return None if move is None else evaluator.evaluate(state, move)


class _Search:
    def __init__(self, scarcity: bool):
        self.scarcity = scarcity

    def prepare(self, problem: ProblemInstance, api: ProblemAccess, assignment: Assignment,
                budget: Budget, seed: int) -> None:
        pass

    def initial_priority(self, problem: ProblemInstance, api: ProblemAccess,
                         view: SatelliteView, budget: Budget) -> tuple[int, ...]:
        score = {}
        for task_id in view.assigned_task_ids:
            budget.check()
            task = problem.task(task_id)
            value = task.priority / task.duration
            if self.scarcity:
                value /= 1 + math.log1p(api.candidate_count(task_id, view.satellite_id, budget))
            score[task_id] = value
        return tuple(sorted(view.assigned_task_ids, key=lambda item: (-score[item], item)))

    def search_initial(self, problem: ProblemInstance, api: ProblemAccess, view: SatelliteView,
                       evaluator, budget: Budget, rng):
        return evaluator.evaluate(self.initial_priority(problem, api, view, budget))

    def _local_plans(self, problem: ProblemInstance, api: ProblemAccess, view: SatelliteView,
                     context: LocalContext, budget: Budget) -> Iterator[LocalPlan]:
        present = {item.task.id for item in view.observations}
        pending = list(view.assigned_task_ids - present)
        choices: dict[int, int] = {}
        if self.scarcity:
            choice_ids = view.assigned_task_ids if context.phase.value == "reassignment" else pending
            choices = {item: api.candidate_count(item, view.satellite_id, budget) for item in choice_ids}
        if context.phase.value == "initial":
            if self.scarcity:
                pending.sort(key=lambda item: (choices[item], -problem.task(item).priority, item))
            else:
                pending.sort(key=lambda item: (-problem.task(item).priority / problem.task(item).duration, item))
            existing = sorted(present, key=lambda item: (problem.task(item).priority, item))
        else:
            if self.scarcity:
                pending.sort(key=lambda item: (item not in context.incoming, choices[item],
                                               -problem.task(item).priority, item))
                existing = sorted(present, key=lambda item: (-choices[item], problem.task(item).priority, item))
            else:
                pending.sort(key=lambda item: (item not in context.incoming, -problem.task(item).priority, item))
                existing = sorted(present, key=lambda item: (problem.task(item).priority, item))
            if context.broken_edges:
                release: dict[int, None] = {}
                for edge in context.broken_edges:
                    a, b = edge.previous_task_id, edge.next_task_id
                    if self.scarcity and choices[a] != choices[b]:
                        removed = a if choices[a] > choices[b] else b
                    else:
                        removed = a if problem.task(a).priority <= problem.task(b).priority else b
                    release[removed] = None
                reconsider = [item for item in pending if item in context.incoming] + list(release)
                yield LocalPlan(view.hash, frozenset(release), tuple(reconsider))
        for task in pending:
            budget.check()
            yield LocalPlan(view.hash, frozenset(), (task,))
            for removed in existing:
                budget.check()
                if problem.task(removed).priority < problem.task(task).priority:
                    yield LocalPlan(view.hash, frozenset((removed,)), (task, removed))

        def singles() -> Iterator[LocalPlan]:
            for task in existing:
                budget.check()
                yield LocalPlan(view.hash, frozenset((task,)), (task,))

        def pairs() -> Iterator[LocalPlan]:
            for i, first in enumerate(existing):
                for second in existing[i + 1:]:
                    budget.check()
                    yield LocalPlan(view.hash, frozenset((first, second)), (second, first))

        if self.scarcity:
            yield from pairs()
            yield from singles()
        else:
            yield from singles()
            yield from pairs()

    def search_initial_local(self, problem: ProblemInstance, api: ProblemAccess, view: SatelliteView,
                             context: LocalContext, feedback: Feedback, evaluator, budget: Budget, rng):
        return self._search_local(problem, api, view, context, feedback, evaluator, budget)

    def search_reassignment(self, problem: ProblemInstance, api: ProblemAccess, view: SatelliteView,
                            context: LocalContext, feedback: Feedback, evaluator, budget: Budget, rng):
        return self._search_local(problem, api, view, context, feedback, evaluator, budget)

    def _search_local(self, problem: ProblemInstance, api: ProblemAccess, view: SatelliteView,
                      context: LocalContext, feedback: Feedback, evaluator, budget: Budget):
        for index, plan in enumerate(self._local_plans(problem, api, view, context, budget)):
            if index == feedback.attempt:
                return evaluator.evaluate(plan)
        return None


class _Decoder:
    def __init__(self, minimize_transition: bool):
        self.minimize_transition = minimize_transition

    def build(self, problem: ProblemInstance, api: ProblemAccess, view: SatelliteView,
              priorities: tuple[int, ...], budget: Budget, rng) -> tuple[ScheduledTask, ...]:
        schedule: list[ScheduledTask] = []
        for task in priorities:
            budget.check()
            choice = self._choose(api, task, view.satellite_id, schedule, budget)
            if choice is not None:
                schedule.append(choice)
        return tuple(sorted(schedule, key=lambda item: item.start))

    def apply_local(self, problem: ProblemInstance, api: ProblemAccess, view: SatelliteView,
                    context: LocalContext, plan: LocalPlan, budget: Budget, rng) -> LocalResult:
        schedule = [item for item in view.observations if item.task.id not in plan.release]
        for task in plan.reconsider:
            budget.check()
            if any(item.task.id == task for item in schedule):
                continue
            choice = self._choose(api, task, view.satellite_id, schedule, budget)
            if choice is not None:
                schedule.append(choice)
        schedule.sort(key=lambda item: item.start)
        if _same_observations(view.observations, schedule):
            return LocalResult.unchanged()
        budget.check()
        if not evaluate(problem, schedule).feasible:
            return LocalResult.unchanged()
        budget.check()
        return LocalResult.applied(tuple(schedule))

    def rebuild(self, problem: ProblemInstance, api: ProblemAccess, view: SatelliteView,
                context: LocalContext, priorities: tuple[int, ...], budget: Budget, rng) -> tuple[ScheduledTask, ...]:
        release = frozenset(item.task.id for item in view.observations)
        result = self.apply_local(problem, api, view, context,
                                  LocalPlan(view.hash, release, priorities), budget, rng)
        return view.observations if result.status.value == "no_feasible_change" else result.observations

    def _choose(self, api: ProblemAccess, task: int, satellite: int, schedule: list[ScheduledTask],
                budget: Budget) -> ScheduledTask | None:
        ordered = sorted(schedule, key=lambda item: item.start)
        best = None
        best_key = None
        for candidate in api.candidates(task, satellite, budget):
            budget.check()
            position = 0
            while position < len(ordered) and ordered[position].start <= candidate.start:
                position += 1
            before = ordered[position - 1] if position else None
            after = ordered[position] if position < len(ordered) else None
            if before is not None and (candidate.start < before.end
                                       or not transition_feasible(before.end_record, candidate.start_record)):
                continue
            if after is not None and (after.start < candidate.end
                                      or not transition_feasible(candidate.end_record, after.start_record)):
                continue
            cost = candidate.end
            if self.minimize_transition:
                cost = 0.0
                if before is not None:
                    cost += transformation_time(before.end_record, candidate.start_record)
                if after is not None:
                    cost += transformation_time(candidate.end_record, after.start_record)
                if before is not None and after is not None:
                    cost -= transformation_time(before.end_record, after.start_record)
            key = (cost, candidate.end, candidate.start)
            if best_key is None or key < best_key:
                best, best_key = candidate, key
        return best


def _same_observations(left, right) -> bool:
    if len(left) != len(right):
        return False
    existing = {item.task.id: item for item in left}
    for item in right:
        original = existing.get(item.task.id)
        if (original is None or original.task is not item.task or original.window is not item.window
                or original.start_record is not item.start_record or original.end_record is not item.end_record):
            return False
    return True


@dataclass(frozen=True, slots=True)
class Combination:
    name: str
    a: _Allocation | RkhmaAllocation
    b: _Search | RkhmaHybridSearch
    c: _Decoder | RkhmaGreedyDecoder


def combination(name: str) -> Combination:
    match = re.fullmatch(r"A([123])B([123])C([123])", name)
    if match is None:
        raise ValueError(f"Unknown manual combination: {name}")
    decoder = RkhmaGreedyDecoder() if match[3] == "3" else _Decoder(match[3] == "2")
    allocation = RkhmaAllocation() if match[1] == "3" else _Allocation(match[1] == "2")
    shared = allocation.keys if isinstance(allocation, RkhmaAllocation) else None
    search = RkhmaHybridSearch(shared) if match[2] == "3" else _Search(match[2] == "2")
    return Combination(name, allocation, search, decoder)
