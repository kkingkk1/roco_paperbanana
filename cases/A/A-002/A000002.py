"""Standalone task allocation case A-002."""

from __future__ import annotations

from collections.abc import Iterator
from satellite_scheduler.runtime.contracts import Assignment, Move


class _Allocation:

    def __init__(self):
        self._cursor_hash: str | None = None
        self._moves: Iterator[Move] = iter(())

    def initial_assign(self, problem: ProblemInstance, api: ProblemAccess, budget: Budget, rng) -> Assignment:
        self._cursor_hash = None
        ids = sorted((task.id for task in problem.tasks))
        owners: dict[int, int | None] = {task_id: None for task_id in ids}
        load: dict[int, float] = {}
        eligible: dict[int, list[int]] = {}
        for task_id in ids:
            budget.check()
            eligible[task_id] = [sat for sat in api.satellite_ids if api.has_candidate(task_id, sat, budget)]
        for task_id in sorted(ids, key=lambda item: (len(eligible[item]), item)):
            budget.check()
            task = problem.task(task_id)
            best = min(eligible[task_id], key=lambda sat: ((load.get(sat, 0.0) + task.duration) / max(1e-12, api.capacity(sat, budget)), sat), default=None)
            owners[task_id] = best
            if best is not None:
                load[best] = load.get(best, 0.0) + task.duration
        return Assignment(owners)

    def prepare(self, problem: ProblemInstance, api: ProblemAccess, assignment: Assignment, budget: Budget, seed: int) -> None:
        pass

    def _proposals(self, problem: ProblemInstance, api: ProblemAccess, state: State, budget: Budget) -> Iterator[Move]:
        scheduled = {observation.task.id for timeline in state.timelines.values() for observation in timeline}
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
            occupied = sum((item.end - item.start for item in state.timelines.get(sat, ())))
            normalized[sat] = occupied / max(1e-12, api.capacity(sat, budget))
        satellites = sorted(api.satellite_ids, key=lambda sat: (normalized[sat], sat))
        for task in pending:
            for target in satellites:
                budget.check()
                if state.assignment.owners[task] != target and api.has_candidate(task, target, budget):
                    yield Move(state.hash, {task: target})
        for index, first in enumerate(tasks):
            for second in tasks[index + 1:]:
                budget.check()
                owner1, owner2 = (state.assignment.owners[first], state.assignment.owners[second])
                if owner1 is not None and owner2 is not None and (owner1 != owner2) and api.has_candidate(first, owner2, budget) and api.has_candidate(second, owner1, budget):
                    yield Move(state.hash, {first: owner2, second: owner1})

    def search_reassignment(self, problem: ProblemInstance, api: ProblemAccess, state: State, feedback: Feedback, evaluator, budget: Budget, rng):
        if state.hash != self._cursor_hash:
            self._cursor_hash = state.hash
            self._moves = self._proposals(problem, api, state, budget)
        move = next(self._moves, None)
        return None if move is None else evaluator.evaluate(state, move)


def create():
    return _Allocation()
