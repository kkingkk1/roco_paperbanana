"""Scarcity-adjusted initial ordering and local edits; initialization only."""

import math

from satellite_scheduler.runtime.contracts import LocalPlan


class ScarcitySearch:
    def initial_priority(self, problem, api, view, budget, rng):
        scores = {}
        for task_id in view.assigned_task_ids:
            budget.check()
            task = problem.task(task_id)
            scores[task_id] = (task.priority / task.duration) / (
                1 + math.log1p(api.candidate_count(task_id, view.satellite_id, budget)))
        return tuple(sorted(view.assigned_task_ids, key=lambda task: (-scores[task], task)))

    def propose_initial_local(self, problem, api, view, context, feedback, budget, rng):
        present = {item.task.id for item in view.observations}
        missing = view.assigned_task_ids - present
        choices = {task: api.candidate_count(task, view.satellite_id, budget) for task in missing}
        ordered_missing = sorted(missing, key=lambda task: (choices[task], -problem.task(task).priority, task))
        existing = sorted(present, key=lambda task: (problem.task(task).priority, task))
        ordinal = 0
        for task in ordered_missing:
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
        for index, first in enumerate(existing):
            for second in existing[index + 1:]:
                budget.check()
                if ordinal == feedback.attempt:
                    return LocalPlan(view.hash, frozenset((first, second)), (second, first))
                ordinal += 1
        for task in existing:
            budget.check()
            if ordinal == feedback.attempt:
                return LocalPlan(view.hash, frozenset((task,)), (task,))
            ordinal += 1
        return None

    def search_initial(self, problem, api, view, evaluator, budget, rng):
        return evaluator.evaluate(self.initial_priority(problem, api, view, budget, rng))

    def search_initial_local(self, problem, api, view, context, feedback, evaluator, budget, rng):
        plan = self.propose_initial_local(problem, api, view, context, feedback, budget, rng)
        return None if plan is None else evaluator.evaluate(plan)


def create():
    return ScarcitySearch()
