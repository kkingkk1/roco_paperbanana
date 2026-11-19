"""用重分配试评反馈调整任务—卫星价格。"""

from satellite_scheduler.runtime.contracts import Assignment, Move


class Allocation:
    def __init__(self):
        self._penalties = {}

    def initial_assign(self, problem, api, budget, rng):
        self._penalties.clear()
        owners, load = {}, {}
        for task in sorted(problem.tasks, key=lambda item: (-item.priority, item.id)):
            budget.check()
            selected = None
            for satellite in api.satellite_ids:
                budget.check()
                if not api.has_candidate(task.id, satellite, budget):
                    continue
                if selected is None or (load.get(satellite, 0), satellite) < (load.get(selected, 0), selected):
                    selected = satellite
            owners[task.id] = selected
            if selected is not None:
                load[selected] = load.get(selected, 0) + task.duration
        return Assignment(owners)

    def prepare(self, problem, api, assignment, budget, seed):
        pass

    def search_reassignment(self, problem, api, state, feedback, evaluator, budget, rng):
        scheduled, initial_profit = set(), 0
        for timeline in state.timelines.values():
            for observation in timeline:
                budget.check()
                scheduled.add(observation.task.id)
                initial_profit += problem.task(observation.task.id).priority
        options = []
        for task in problem.tasks:
            budget.check()
            if task.id in scheduled:
                continue
            current = state.assignment.owners[task.id]
            for satellite in api.satellite_ids:
                budget.check()
                if current != satellite and api.has_candidate(task.id, satellite, budget):
                    options.append((task.id, satellite))
        options.sort(key=lambda pair: (-(problem.task(pair[0]).priority - self._penalties.get(pair, 0)),
                                       pair[0], pair[1]))
        best, best_profit = None, initial_profit
        for task, satellite in options:
            budget.check()
            trial = evaluator.evaluate(state, Move(state.hash, {task: satellite}))
            task_scheduled = (trial.state is not None and
                              any(item.task.id == task for item in trial.state.timelines.get(satellite, ())))
            if not task_scheduled:
                pair = task, satellite
                self._penalties[pair] = min(2**31 - 1, self._penalties.get(pair, 0) + 1)
            if trial.state is not None and trial.total_profit > best_profit:
                best, best_profit = trial, trial.total_profit
                evaluator.checkpoint(trial)
        return best


def create():
    return Allocation()
