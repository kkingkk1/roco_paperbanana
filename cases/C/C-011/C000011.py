"""Single-satellite decoder: C-011."""

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
        best = best_key = None
        for candidate in api.candidates(task, satellite, budget):
            budget.check()
            position = _position(ordered, candidate)
            before = ordered[position - 1] if position else None
            after = ordered[position] if position < len(ordered) else None
            if not _fits(before, candidate, after):
                continue
            placed = list(ordered)
            placed.insert(position, candidate)
            score = -_future_options(problem, api, satellite, future, placed, budget, 0)
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



def _options(api, satellite, task, schedule, budget):
    options = []
    for option in api.candidates(task, satellite, budget):
        budget.check()
        index = _position(schedule, option)
        before = schedule[index - 1] if index else None
        after = schedule[index] if index < len(schedule) else None
        if _fits(before, option, after):
            options.append(option)
    return options


def _future_options(problem, api, satellite, future, placed, budget, mode):
    total, minimum, considered, seen = 0.0, float("inf"), 0, set()
    for task in future:
        budget.check()
        if task in seen or any(item.task.id == task for item in placed):
            continue
        seen.add(task)
        considered += 1
        options = _options(api, satellite, task, placed, budget)
        count = len(options)
        windows = len({id(option.window) for option in options})
        minimum = min(minimum, count)
        if mode == 0:
            return float(count)
        if mode == 1:
            total += count
        if mode == 2 and count > 0:
            total += 1
        if mode == 4 and count > 0:
            total += problem.task(task).priority
        if mode == 5:
            total += windows
        if mode == 6:
            total += count * float(problem.task(task).priority)
        if mode == 7 and windows >= 2:
            total += 1
        if mode == 8 and count > 0:
            total = count if total == 0 else min(total, count)
    return (0 if not considered else minimum) if mode == 3 else total

def create():
    return Decoder()
