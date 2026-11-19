"""Single-satellite decoder: C-061."""

import math

from satellite_scheduler.problem import evaluate, transformation_time, transition_feasible
from satellite_scheduler.runtime.contracts import LocalPlan, LocalResult, LocalStatus


class Decoder:
    def build(self, problem, api, view, priorities, budget, rng):
        return self._decode(problem, api, view.satellite_id, [], priorities, budget, rng)

    def apply_local(self, problem, api, view, context, plan, budget, rng):
        schedule = [item for item in view.observations if item.task.id not in plan.release]
        schedule = self._decode(problem, api, view.satellite_id, schedule, plan.reconsider, budget, rng)
        if _same(view.observations, schedule):
            return LocalResult.unchanged()
        budget.check()
        if not evaluate(problem, schedule).feasible:
            return LocalResult.unchanged()
        budget.check()
        return LocalResult.applied(schedule)

    def rebuild(self, problem, api, view, context, priorities, budget, rng):
        release = frozenset(item.task.id for item in view.observations)
        result = self.apply_local(problem, api, view, context, LocalPlan(view.hash, release, priorities), budget, rng)
        return view.observations if result.status is LocalStatus.NO_FEASIBLE_CHANGE else result.observations

    def _decode(self, problem, api, satellite, schedule, priorities, budget, rng):
        for index, task in enumerate(priorities):
            budget.check()
            if any(item.task.id == task for item in schedule):
                continue
            selected = self._choose(problem, api, satellite, task, schedule, priorities[index + 1:], budget, rng)
            if selected is not None:
                schedule.append(selected)
        return tuple(sorted(schedule, key=lambda item: item.start))

    def _choose(self, problem, api, satellite, task, schedule, future, budget, rng):
        ordered = sorted(schedule, key=lambda item: item.start)
        feasible, costs = [], []
        for candidate in api.candidates(task, satellite, budget):
            budget.check()
            at = _position(ordered, candidate)
            before = ordered[at - 1] if at else None
            after = ordered[at] if at < len(ordered) else None
            if not _fits(before, candidate, after):
                continue
            feasible.append(candidate)
            costs.append(_added_transition(before, candidate, after))
        if not feasible:
            return None
        low, high = min(costs), max(costs)
        chosen, eligible = None, 0
        threshold = low + (high - low) / 2
        for candidate, cost in zip(feasible, costs):
            budget.check()
            if cost <= threshold:
                eligible += 1
                if rng.randrange(eligible) == 0:
                    chosen = candidate
        return chosen


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


def _added_transition(before, candidate, after):
    value = 0.0
    if before is not None:
        value += transformation_time(before.end_record, candidate.start_record)
    if after is not None:
        value += transformation_time(candidate.end_record, after.start_record)
    if before is not None and after is not None:
        value -= transformation_time(before.end_record, after.start_record)
    return value


def create():
    return Decoder()
