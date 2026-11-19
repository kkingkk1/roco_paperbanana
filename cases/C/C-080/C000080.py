"""Single-satellite decoder: C-080."""

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
    pass
    for task in order:
        in_left = any(item.task.id == task for item in left)
        in_right = any(item.task.id == task for item in right)
        if in_left != in_right:
            return in_left
    def movement(schedule):
        total = 0.0
        for item in schedule:
            budget.check()
            original = originals.get(item.task.id)
            if original is not None:
                total += abs(item.start - original.start)
        return total
    def changed(schedule):
        total = 0
        for item in schedule:
            budget.check()
            original = originals.get(item.task.id)
            if original is not None and (original.window is not item.window
                                         or original.start_record is not item.start_record
                                         or original.end_record is not item.end_record):
                total += 1
        return total
    left_changes, right_changes = changed(left), changed(right)
    if left_changes != right_changes:
        return left_changes < right_changes
    left_move, right_move = movement(left), movement(right)
    if left_move != right_move:
        return left_move < right_move
    for first, second in zip(left, right):
        if first.start != second.start:
            return first.start < second.start
        if first.start == 0.0 and math.copysign(1.0, first.start) != math.copysign(1.0, second.start):
            return math.copysign(1.0, first.start) < math.copysign(1.0, second.start)
    return False


def _search(problem, api, satellite, base, order, budget, originals):
    schedule = sorted(base, key=lambda item: item.start)
    best = None

    def visit(index):
        nonlocal best
        budget.check()
        if index == len(order):
            result = evaluate(problem, schedule)
            budget.check()
            if result.feasible and _better(schedule, best, order, originals, budget):
                best = tuple(schedule)
            return
        task = order[index]
        if not any(item.task.id == task for item in schedule):
            for candidate in api.candidates(task, satellite, budget):
                budget.check()
                position = _position(schedule, candidate)
                before = schedule[position - 1] if position else None
                after = schedule[position] if position < len(schedule) else None
                if not _fits(before, candidate, after):
                    continue
                schedule.insert(position, candidate)
                visit(index + 1)
                schedule.pop(position)
        visit(index + 1)

    visit(0)
    return best


def create():
    return Decoder()
