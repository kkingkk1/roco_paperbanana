"""Density-based initial ordering and local edits; initialization only."""

from satellite_scheduler.runtime.contracts import LocalPlan


class DensitySearch:
    def initial_priority(self, problem, api, view, budget, rng):
        density = {}
        for task_id in view.assigned_task_ids:
            budget.check()
            task = problem.task(task_id)
            density[task_id] = task.priority / task.duration
        return tuple(sorted(view.assigned_task_ids, key=lambda task: (-density[task], task)))

    def propose_initial_local(self, problem, api, view, context, feedback, budget, rng):
        present = {item.task.id for item in view.observations}
        missing = sorted(view.assigned_task_ids - present,
                         key=lambda task: (-problem.task(task).priority / problem.task(task).duration, task))
        existing = sorted(present, key=lambda task: (problem.task(task).priority, task))
        ordinal = 0
        for task in missing:
            budget.check()
            if ordinal == feedback.attempt:
                return LocalPlan(view.hash, frozenset(), (task,))
            ordinal += 1
            for removed in existing:
                budget.check()
                if problem.task(removed).priority >= problem.task(task).priority:
                    continue
                if ordinal == feedback.attempt:
                    return LocalPlan(view.hash, frozenset((removed,)), (task, removed))
                ordinal += 1
        for task in existing:
            budget.check()
            if ordinal == feedback.attempt:
                return LocalPlan(view.hash, frozenset((task,)), (task,))
            ordinal += 1
        for index, first in enumerate(existing):
            for second in existing[index + 1:]:
                budget.check()
                if ordinal == feedback.attempt:
                    return LocalPlan(view.hash, frozenset((first, second)), (second, first))
                ordinal += 1
        return None

    def search_initial(self, problem, api, view, evaluator, budget, rng):
        return evaluator.evaluate(self.initial_priority(problem, api, view, budget, rng))

    def search_initial_local(self, problem, api, view, context, feedback, evaluator, budget, rng):
        plan = self.propose_initial_local(problem, api, view, context, feedback, budget, rng)
        return None if plan is None else evaluator.evaluate(plan)


def create():
    return DensitySearch()
