"""Try pending-task insertions and return the best issued local trial."""

from satellite_scheduler.runtime.contracts import LocalPlan


def search(problem, api, view, context, evaluator, budget, rng, max_evaluations):
    if max_evaluations < 1:
        raise ValueError("max_evaluations must be positive")
    present = {item.task.id for item in view.observations}
    pending = sorted(view.assigned_task_ids - present, key=lambda task: (-problem.task(task).priority, task))
    best = None
    for task in pending[:max_evaluations]:
        budget.check()
        trial = evaluator.evaluate(LocalPlan(view.hash, frozenset(), (task,)))
        if trial.total_profit is not None and (best is None or trial.total_profit > best.total_profit):
            best = trial
    return best
