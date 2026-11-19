"""Single-satellite decoder: C-077."""

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
    removable = [item for item in prior if any(observation.task.id == item for observation in schedule)]
    best, best_loss, best_slew = None, 2**63 - 1, float("inf")

    def visit(index, remaining, candidate, loss):
        nonlocal best, best_loss, best_slew
        budget.check()
        if loss >= problem.task(task).priority or loss > best_loss:
            return
        if index == len(removable):
            trial = list(remaining)
            trial.insert(_position(trial, candidate), candidate)
            if not evaluate(problem, trial).feasible:
                return
            slew = _total_slew(trial, budget)
            if best is None or loss < best_loss or (loss == best_loss and slew < best_slew):
                best, best_loss, best_slew = tuple(trial), loss, slew
            return
        visit(index + 1, remaining, candidate, loss)
        item = removable[index]
        for position, observation in enumerate(remaining):
            if observation.task.id == item:
                removed = remaining.pop(position)
                visit(index + 1, remaining, candidate, loss + removed.task.priority)
                remaining.insert(position, removed)
                break

    for candidate in api.candidates(task, satellite, budget):
        budget.check()
        visit(0, list(schedule), candidate, 0)
    return best


def _total_slew(schedule, budget):
    total = 0.0
    for index in range(1, len(schedule)):
        budget.check()
        total += transformation_time(schedule[index - 1].end_record, schedule[index].start_record)
    return total


def create():
    return Decoder()
