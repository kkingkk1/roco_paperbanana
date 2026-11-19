"""Rebuild the affected satellite with incoming tasks first."""


def search(problem, api, view, context, evaluator, budget, rng, max_evaluations):
    if max_evaluations < 1:
        raise ValueError("max_evaluations must be positive")
    order = sorted(view.assigned_task_ids,
                   key=lambda task: (task not in context.incoming, -problem.task(task).priority, task))
    budget.check()
    return evaluator.rebuild(order)
