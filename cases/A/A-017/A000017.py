"""试评未调度任务迁移与跨星交换，选择实际收益最高的改善。"""

from satellite_scheduler.runtime.contracts import Assignment, Move


class Allocation:
    def initial_assign(self, problem, api, budget, rng):
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
        scheduled, baseline = set(), 0
        for timeline in state.timelines.values():
            for item in timeline:
                budget.check()
                scheduled.add(item.task.id)
                baseline += problem.task(item.task.id).priority
        ids = sorted(task.id for task in problem.tasks)
        best, best_profit = None, baseline
        for task in ids:
            if task in scheduled:
                continue
            old = state.assignment.owners[task]
            for satellite in api.satellite_ids:
                budget.check()
                if old == satellite or not api.has_candidate(task, satellite, budget):
                    continue
                trial = evaluator.evaluate(state, Move(state.hash, {task: satellite}))
                if trial.state is not None and trial.total_profit > best_profit:
                    best, best_profit = trial, trial.total_profit
                    evaluator.checkpoint(trial)
        for index, first in enumerate(ids):
            for second in ids[index + 1:]:
                budget.check()
                owner1 = state.assignment.owners[first]
                owner2 = state.assignment.owners[second]
                if (owner1 is None or owner2 is None or owner1 == owner2
                        or not api.has_candidate(first, owner2, budget)
                        or not api.has_candidate(second, owner1, budget)):
                    continue
                trial = evaluator.evaluate(state, Move(state.hash, {first: owner2, second: owner1}))
                if trial.state is not None and trial.total_profit > best_profit:
                    best, best_profit = trial, trial.total_profit
                    evaluator.checkpoint(trial)
        return best


def create():
    return Allocation()
