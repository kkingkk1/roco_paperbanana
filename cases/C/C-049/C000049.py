"""Single-satellite decoder: C-049."""

import math
from functools import cmp_to_key

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
    left_score = sum(item.task.priority for item in left)
    right_score = sum(item.task.priority for item in right)
    if left_score != right_score:
        return left_score > right_score
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
    return len(left) < len(right)


def _search(problem, api, satellite, base, order, budget, originals):
    beam = [tuple(sorted(base, key=lambda item: item.start))]

    def compare(left, right):
        budget.check()
        if _better(left, right, order, originals, budget):
            return -1
        if _better(right, left, order, originals, budget):
            return 1
        return 0

    def offer(values, value):
        for existing in values:
            budget.check()
            if _same(existing, value):
                return
        values.append(tuple(value))
        values.sort(key=cmp_to_key(compare))
        if len(values) > 4:
            values.pop()

    for task in order:
        budget.check()
        following = []
        for timeline in beam:
            offer(following, timeline)
            if any(item.task.id == task for item in timeline):
                continue
            for candidate in api.candidates(task, satellite, budget):
                budget.check()
                at = _position(timeline, candidate)
                before = timeline[at - 1] if at else None
                after = timeline[at] if at < len(timeline) else None
                if not _fits(before, candidate, after):
                    continue
                changed = list(timeline)
                changed.insert(at, candidate)
                offer(following, changed)
        beam = following
    for timeline in beam:
        budget.check()
        if evaluate(problem, timeline).feasible:
            return tuple(timeline)
    return None


def create():
    return Decoder()
