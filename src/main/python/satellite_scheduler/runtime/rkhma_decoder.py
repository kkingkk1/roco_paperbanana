"""RKHMA思想的首次可行贪心插入与来源窗口偏好。"""

import struct

from satellite_scheduler.problem import transition_feasible
from satellite_scheduler.runtime.contracts import LocalResult


class RkhmaGreedyDecoder:
    def build(self, problem, api, view, priorities, budget, rng):
        budget.check()
        return _decode(problem, api, view.satellite_id, (), priorities, frozenset(), budget)

    def rebuild(self, problem, api, view, context, priorities, budget, rng):
        budget.check()
        preferred = context.preferred_window_tasks
        if preferred and not _source_compatible(problem, view.satellite_id, budget):
            preferred = frozenset()
        return _decode(problem, api, view.satellite_id, (), priorities, preferred, budget)

    def apply_local(self, problem, api, view, context, plan, budget, rng):
        budget.check()
        kept = []
        for observation in view.observations:
            budget.check()
            if observation.task.id not in plan.release:
                kept.append(observation)
        return LocalResult.applied(
            _decode(problem, api, view.satellite_id, kept, plan.reconsider, frozenset(), budget))


def _decode(problem, api, satellite, existing, order, preferred, budget):
    schedule = sorted(existing, key=lambda item: item.start)
    for task in order:
        budget.check()
        if any(item.task.id == task for item in schedule):
            continue
        ranks = _ranked_windows(problem, api, satellite, task, budget) if task in preferred else {}
        selected, selected_rank = None, 2**31 - 1
        for candidate in api.candidates(task, satellite, budget):
            budget.check()
            rank = ranks[candidate.window] if ranks else 0
            if rank >= selected_rank or not _fits(schedule, candidate):
                continue
            selected, selected_rank = candidate, rank
            if rank == 0:
                break
        if selected is not None:
            schedule.append(selected)
            schedule.sort(key=lambda item: item.start)
    return tuple(schedule)


def _fits(schedule, candidate):
    index = 0
    while index < len(schedule) and schedule[index].start < candidate.start:
        index += 1
    previous = schedule[index - 1] if index else None
    following = schedule[index] if index < len(schedule) else None
    return ((previous is None or (candidate.start >= previous.end and
             transition_feasible(previous.end_record, candidate.start_record))) and
            (following is None or (following.start >= candidate.end and
             transition_feasible(candidate.end_record, following.start_record))))


def _ranked_windows(problem, api, satellite, task, budget):
    task_index = 0
    while problem.tasks[task_index].id != task:
        budget.check()
        task_index += 1
    satellite_index = 0
    while problem.satellites[satellite_index].id != satellite:
        budget.check()
        satellite_index += 1
    windows = list(api.windows(task, satellite))
    windows.sort(key=lambda window: (-_window_score(window, task_index, satellite_index),
                                     window.start, window.end))
    ranks = {}
    for index, window in enumerate(windows):
        budget.check()
        ranks[window] = index
    return ranks


def _window_score(window, task_index, satellite_index):
    mask = (1 << 64) - 1
    value = 1469598103934665603
    for part in (task_index, satellite_index,
                 struct.unpack(">Q", struct.pack(">d", window.start))[0],
                 struct.unpack(">Q", struct.pack(">d", window.end))[0]):
        value = ((value ^ part) * 1099511628211) & mask
    noise = (value & ((1 << 63) - 1)) / float((1 << 63) - 1)
    return -window.start * 1.0e-3 + max(0.0, window.end - window.start) + noise * 1.0e-2


def _source_compatible(problem, satellite, budget):
    for window in problem.satellite(satellite).windows:
        budget.check()
        records = window.attitudes
        for index in range(1, len(records) - 1):
            budget.check()
            time = records[index].time
            if time < window.start or time > window.end:
                return False
            if index > 1 and time - records[index - 1].time != 1.0:
                return False
    return True


def create():
    return RkhmaGreedyDecoder()
