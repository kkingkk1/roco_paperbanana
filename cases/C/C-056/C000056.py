"""Single-satellite decoder: C-056."""

import math

from satellite_scheduler.problem import evaluate, transition_feasible
from satellite_scheduler.runtime.contracts import LocalPlan, LocalResult, LocalStatus


class Decoder:
    def build(self, problem, api, view, priorities, budget, rng):
        result = _search(problem, api, view.satellite_id, (), priorities, budget, {})
        return () if result is None else result

    def apply_local(self, problem, api, view, context, plan, budget, rng):
        base = [item for item in view.observations if item.task.id not in plan.release]
        originals = {item.task.id: item for item in view.observations if item.task.id in plan.release}
        result = _search(problem, api, view.satellite_id, base, plan.reconsider, budget, originals)
        if result is None or _same(view.observations, result):
            return LocalResult.unchanged()
        return LocalResult.applied(result)

    def rebuild(self, problem, api, view, context, priorities, budget, rng):
        release = frozenset(item.task.id for item in view.observations)
        result = self.apply_local(problem, api, view, context, LocalPlan(view.hash, release, priorities), budget, rng)
        return view.observations if result.status is LocalStatus.NO_FEASIBLE_CHANGE else result.observations


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


def _better(left, right, order, originals, budget):
    if right is None:
        return True
    if len(left) != len(right):
        return len(left) > len(right)
    for task in order:
        in_left = any(item.task.id == task for item in left)
        in_right = any(item.task.id == task for item in right)
        if in_left != in_right:
            return in_left
    pass
    for first, second in zip(left, right):
        if first.start != second.start:
            return first.start < second.start
        if first.start == 0.0 and math.copysign(1.0, first.start) != math.copysign(1.0, second.start):
            return math.copysign(1.0, first.start) < math.copysign(1.0, second.start)
    return False


def _search(problem, api, satellite, base, order, budget, originals):
    initial = tuple(sorted(base, key=lambda item: item.start))
    states = [(initial, None)]
    for task in order:
        budget.check()
        if any(item.task.id == task for item in initial):
            continue
        additions = []
        for candidate in api.candidates(task, satellite, budget):
            budget.check()
            best = None
            for schedule, previous in states:
                budget.check()
                if previous is not None and candidate.start < previous.start:
                    continue
                at = _position(schedule, candidate)
                before = schedule[at - 1] if at else None
                after = schedule[at] if at < len(schedule) else None
                if not _fits(before, candidate, after):
                    continue
                trial = list(schedule)
                trial.insert(at, candidate)
                if not _prefix_feasible(trial, candidate.start):
                    continue
                if best is None or _better(trial, best[0], order, originals, budget):
                    best = tuple(trial), candidate
            if best is not None:
                additions.append(best)
        states.extend(additions)
    best = None
    for schedule, _ in states:
        budget.check()
        if evaluate(problem, schedule).feasible and _better(schedule, best, order, originals, budget):
            best = schedule
    return best


def _prefix_feasible(schedule, until):
    for index in range(1, len(schedule)):
        before, after = schedule[index - 1], schedule[index]
        if after.start > until:
            break
        if after.start < before.end or not transition_feasible(before.end_record, after.start_record):
            return False
    return True


def create():
    return Decoder()
