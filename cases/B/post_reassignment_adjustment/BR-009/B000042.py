"""Scarcity-based local adjustment after reassignment; reassignment only."""

from satellite_scheduler.runtime.contracts import LocalPlan


class ScarcitySearch:
    def propose_reassignment_local(self, problem, api, view, context, feedback, budget, rng):
        present = {item.task.id for item in view.observations}
        choices = {task: api.candidate_count(task, view.satellite_id, budget) for task in view.assigned_task_ids}
        pending = sorted(view.assigned_task_ids - present,
                         key=lambda task: (task not in context.incoming, choices[task],
                                           -problem.task(task).priority, task))
        existing = sorted(present, key=lambda task: (-choices[task], problem.task(task).priority, task))
        ordinal = 0
        if context.broken_edges:
            release = {}
            for edge in context.broken_edges:
                a, b = edge.previous_task_id, edge.next_task_id
                if choices[a] == choices[b]:
                    removed = a if problem.task(a).priority <= problem.task(b).priority else b
                else:
                    removed = a if choices[a] > choices[b] else b
                release[removed] = None
            reconsider = [task for task in pending if task in context.incoming] + list(release)
            if ordinal == feedback.attempt:
                return LocalPlan(view.hash, frozenset(release), tuple(reconsider))
            ordinal += 1
        for task in pending:
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

    def search_reassignment(self, problem, api, view, context, feedback, evaluator, budget, rng):
        plan = self.propose_reassignment_local(problem, api, view, context, feedback, budget, rng)
        return None if plan is None else evaluator.evaluate(plan)


def create():
    return ScarcitySearch()
