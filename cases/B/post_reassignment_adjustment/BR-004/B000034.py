"""Release low-reward observations and reconsider incoming and pending tasks."""

from satellite_scheduler.runtime.contracts import LocalPlan


def search(problem, api, view, context, evaluator, budget, rng, max_evaluations):
    if max_evaluations < 1:
        raise ValueError("max_evaluations must be positive")
    present = {item.task.id for item in view.observations}
    pending = sorted(view.assigned_task_ids - present, key=lambda task: (-problem.task(task).priority, task))
    removed = sorted(present, key=lambda task: (problem.task(task).priority, task))[:2]
    order = ([task for task in pending if task in context.incoming]
             + [task for task in pending if task not in context.incoming]
             + sorted(removed, key=lambda task: (-problem.task(task).priority, task)))
    if not removed and not order:
        return None
    budget.check()
    trial = evaluator.evaluate(LocalPlan(view.hash, frozenset(removed), tuple(order)))
    return None if trial.total_profit is None else trial
