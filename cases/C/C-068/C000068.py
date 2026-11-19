"""Single-satellite decoder: C-068."""

import math

from satellite_scheduler.problem import evaluate, transition_feasible
from satellite_scheduler.runtime.contracts import LocalPlan, LocalResult, LocalStatus


class Decoder:
    def build(self, problem, api, view, priorities, budget, rng):
        result = self._search(problem, api, view.satellite_id, (), priorities, budget, rng)
        return () if result is None else result

    def apply_local(self, problem, api, view, context, plan, budget, rng):
        base = [item for item in view.observations if item.task.id not in plan.release]
        result = self._search(problem, api, view.satellite_id, base, plan.reconsider, budget, rng)
        if result is None or _same(view.observations, result):
            return LocalResult.unchanged()
        return LocalResult.applied(result)

    def _search(self, problem, api, satellite, base, order, budget, rng):
        best, best_profit = None, None
        for _ in range(16):
            budget.check()
            trial = self._decode(problem, api, satellite, list(base), order, budget, rng)
            result = evaluate(problem, trial)
            budget.check()
            if not result.feasible:
                continue
            if best is None or result.total_profit > best_profit or (
                    result.total_profit == best_profit and _prefer(trial, best, order)):
                best, best_profit = trial, result.total_profit
        return best

    def rebuild(self, problem, api, view, context, priorities, budget, rng):
        release = frozenset(item.task.id for item in view.observations)
        result = self.apply_local(problem, api, view, context, LocalPlan(view.hash, release, priorities), budget, rng)
        return view.observations if result.status is LocalStatus.NO_FEASIBLE_CHANGE else result.observations

    def _decode(self, problem, api, satellite, schedule, priorities, budget, rng):
        for task in priorities:
            budget.check()
            if any(item.task.id == task for item in schedule):
                continue
            selected = self._choose(api, satellite, task, schedule, budget, rng)
            if selected is not None:
                schedule.append(selected)
        return tuple(sorted(schedule, key=lambda item: item.start))

    def _choose(self, api, satellite, task, schedule, budget, rng):
        ordered = sorted(schedule, key=lambda item: item.start)
        best = best_key = None
        for candidate in api.candidates(task, satellite, budget):
            budget.check()
            position = _position(ordered, candidate)
            before = ordered[position - 1] if position else None
            after = ordered[position] if position < len(ordered) else None
            if not _fits(before, candidate, after):
                continue
            score = rng.random()
            zero_sign = 0 if score == 0 and math.copysign(1.0, score) < 0 else 1
            key = (score, zero_sign, candidate.end, candidate.start)
            if best_key is None or key < best_key:
                best, best_key = candidate, key
        return best


def _position(schedule, candidate):
    index = 0
    while index < len(schedule) and schedule[index].start <= candidate.start:
        index += 1
    return index


def _fits(before, candidate, after):
    return ((before is None or (candidate.start >= before.end and
            transition_feasible(before.end_record, candidate.start_record))) and
            (after is None or (after.start >= candidate.end and
            transition_feasible(candidate.end_record, after.start_record))))


def _same(left, right):
    if len(left) != len(right):
        return False
    existing = {item.task.id: item for item in left}
    for item in right:
        old = existing.get(item.task.id)
        if (old is None or old.task is not item.task or old.window is not item.window or
                old.start_record is not item.start_record or old.end_record is not item.end_record):
            return False
    return True


def _prefer(left, right, order):
    for task in order:
        in_left = any(item.task.id == task for item in left)
        in_right = any(item.task.id == task for item in right)
        if in_left != in_right:
            return in_left
    for first, second in zip(left, right):
        if first.start != second.start:
            return first.start < second.start
        if first.start == 0.0 and math.copysign(1.0, first.start) != math.copysign(1.0, second.start):
            return math.copysign(1.0, first.start) < math.copysign(1.0, second.start)
    return False


def create():
    return Decoder()
