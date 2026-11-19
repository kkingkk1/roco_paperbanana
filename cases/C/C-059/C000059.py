"""Single-satellite decoder: C-059."""

import math

from satellite_scheduler.problem import evaluate, transition_feasible
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
        schedule = sorted(schedule, key=lambda item: item.start)
        previously_inserted = []
        for task in priorities:
            budget.check()
            if any(item.task.id == task for item in schedule):
                continue
            selected = self._choose(api, satellite, task, schedule, budget, rng)
            if selected is not None:
                schedule.insert(_position(schedule, selected), selected)
                previously_inserted.append(task)
                continue
            repaired = _repair(problem, api, satellite, task, schedule, previously_inserted, budget)
            if repaired is not None:
                schedule = list(repaired)
                previously_inserted.append(task)
        return tuple(schedule)

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
            score = candidate.end
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



def _repair(problem, api, satellite, task, schedule, prior, budget):
    removable = set(prior)
    best, best_key = None, None
    for candidate in api.candidates(task, satellite, budget):
        budget.check()
        trial = list(schedule)
        trial.insert(_position(trial, candidate), candidate)
        loss, possible = 0, True
        while True:
            budget.check()
            violation = None
            for index in range(1, len(trial)):
                budget.check()
                before, after = trial[index - 1], trial[index]
                if after.start < before.end or not transition_feasible(before.end_record, after.start_record):
                    violation = index
                    break
            if violation is None:
                break
            before, after = trial[violation - 1], trial[violation]
            can_before = before.task.id in removable
            can_after = after.task.id in removable
            if not can_before and not can_after:
                possible = False
                break
            removed = (violation - 1 if can_before and
                       (not can_after or before.task.priority <= after.task.priority) else violation)
            loss += trial.pop(removed).task.priority
        gain = problem.task(task).priority - loss
        if not possible or gain <= 0 or not evaluate(problem, trial).feasible:
            continue
        budget.check()
        return tuple(trial)
    return best


def create():
    return Decoder()
