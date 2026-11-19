"""RKHMA 思路的复合分配及重分配案例；并非原版完整协调器。"""

from itertools import combinations
import random

from satellite_scheduler.problem import ProblemAdapter
from satellite_scheduler.runtime.contracts import Assignment, Move


class Allocation:
    def __init__(self):
        self.task_keys = {}
        self.pair_keys = {}

    def initial_assign(self, problem, api, budget, rng):
        adapter = ProblemAdapter(problem)
        owners = {task.id: None for task in problem.tasks}
        assigned = {sat.id: [] for sat in problem.satellites}
        for task in sorted(problem.tasks, key=lambda item: -item.priority):
            candidates = []
            for satellite in problem.satellites:
                valid = []
                for window in api.windows(task.id, satellite.id):
                    budget.check()
                    if next(adapter.candidates(window), None) is not None:
                        valid.append(window)
                length = sum(len(window.attitudes) - 2 for window in valid)
                if length > 0:
                    candidates.append((satellite.id, length, len(valid)))
            if not candidates:
                continue
            max_length = max(row[1] for row in candidates)
            max_count = max(row[2] for row in candidates)
            max_load = max(1, *(len(assigned[row[0]]) for row in candidates))
            scarcity = 1 / len(candidates)
            best_satellite, best_score = None, float('-inf')
            for satellite, length, count in candidates:
                budget.check()
                windows = api.windows(task.id, satellite)
                previous = []
                for old in assigned[satellite]:
                    budget.check()
                    previous.extend(api.windows(old, satellite))
                overlap = 0
                for a in windows:
                    for b in previous:
                        budget.check()
                        overlap += a.start < b.end and a.end > b.start
                conflict = overlap / (len(windows) * len(previous)) if windows and previous else 0
                score = (0.37 * (1 + 0.5 * scarcity) * length / max_length
                         + 0.20 * count / max_count
                         + 0.25 * (1 - 0.5 * scarcity) * (1 - len(assigned[satellite]) / max_load)
                         - 0.25 * conflict)
                if score > best_score:
                    best_satellite, best_score = satellite, score
            owners[task.id] = best_satellite
            assigned[best_satellite].append(task.id)
        return Assignment(owners)

    def prepare(self, problem, api, assignment, budget, seed):
        generator = random.Random(seed)
        self.task_keys, self.pair_keys = {}, {}
        for task in sorted(problem.tasks, key=lambda item: item.id):
            budget.check()
            self.task_keys[task.id] = generator.random()
            for satellite in api.satellite_ids:
                budget.check()
                if api.windows(task.id, satellite):
                    self.pair_keys[task.id, satellite] = generator.random()

    def _targets(self, task, problem, api, state, budget):
        options = []
        for satellite in api.satellite_ids:
            budget.check()
            if state.assignment.owners[task] == satellite or not api.has_candidate(task, satellite, budget):
                continue
            windows = api.windows(task, satellite)
            options.append((satellite, len(windows),
                            sum(max(0, item.end - item.start) for item in windows),
                            min(item.start for item in windows)))
        if not options:
            return []
        max_count = max(item[1] for item in options)
        max_length = max(1, *(item[2] for item in options))
        first_start = min(item[3] for item in options)
        return [item[0] for item in sorted(options, key=lambda item: (
            -(0.346 * item[1] / max_count + 0.274 * item[2] / max_length
              + 0.18 / (1 + item[3] - first_start)
              + 0.20 * self.pair_keys.get((task, item[0]), 0)), item[0]))]

    def search_reassignment(self, problem, api, state, feedback, evaluator, budget, rng):
        scheduled = {item.task.id for timeline in state.timelines.values() for item in timeline}
        pending = [task for task in problem.tasks if task.id not in scheduled]
        if not pending:
            return None
        windows = {}
        for task in pending:
            windows[task.id] = 0
            for satellite in api.satellite_ids:
                budget.check()
                windows[task.id] += len(api.windows(task.id, satellite))
        targets = {task.id: self._targets(task.id, problem, api, state, budget) for task in pending}
        max_priority = max(1, *(task.priority for task in pending))
        max_targets = max(1, *(len(targets[task.id]) for task in pending))
        max_windows = max(1, *windows.values())
        pending.sort(key=lambda task: (-(5 * task.priority / max_priority
                                        + 2 * len(targets[task.id]) / max_targets
                                        + windows[task.id] / max_windows
                                        + 3 * self.task_keys.get(task.id, 0)), task.id))
        baseline = sum(item.task.priority for timeline in state.timelines.values() for item in timeline)
        best, best_profit = None, baseline

        def attempt(edits):
            nonlocal best, best_profit
            budget.check()
            trial = evaluator.evaluate(state, Move(state.hash, edits))
            if trial.state is not None and trial.total_profit > best_profit:
                best, best_profit = trial, trial.total_profit
                evaluator.checkpoint(trial)
                return True
            return False

        failed = []
        for task in pending:
            before = best_profit
            for satellite in targets[task.id]:
                attempt({task.id: satellite})
            if best_profit > before:
                continue
            for satellite in targets[task.id]:
                task_windows = api.windows(task.id, satellite)
                nearby = [item for item in state.timelines.get(satellite, ())
                          if any(item.start <= window.end + 60 and item.end >= window.start - 60
                                 for window in task_windows)]
                nearby.sort(key=lambda item: (item.task.priority, item.task.id))
                for count in range(1, min(3, len(nearby)) + 1):
                    for removed in combinations(nearby[:3], count):
                        edits = {task.id: satellite}
                        edits.update({item.task.id: None for item in removed})
                        attempt(edits)
                for item in nearby[:3]:
                    for destination in api.satellite_ids:
                        budget.check()
                        if destination != satellite and api.has_candidate(item.task.id, destination, budget):
                            attempt({task.id: satellite, item.task.id: destination})
            if best_profit == before:
                failed.append(task.id)
        for first, second in combinations(failed, 2):
            for satellite in sorted(set(targets[first]).intersection(targets[second])):
                budget.check()
                first_start = min(item.start for item in api.windows(first, satellite))
                second_start = min(item.start for item in api.windows(second, satellite))
                if abs(first_start - second_start) <= 120:
                    attempt({first: satellite, second: satellite})
        return best


def create():
    return Allocation()
