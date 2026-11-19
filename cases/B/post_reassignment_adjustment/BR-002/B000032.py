"""Try releasing two existing observations while inserting a pending task."""

from satellite_scheduler.runtime.contracts import LocalPlan


def search(problem, api, view, context, evaluator, budget, rng, max_evaluations):
    if max_evaluations < 1:
        raise ValueError("max_evaluations must be positive")
    present = {item.task.id for item in view.observations}
    pending = sorted(view.assigned_task_ids - present, key=lambda task: (-problem.task(task).priority, task))
    existing = sorted(present)
    best, evaluations = None, 0
    for task in pending:
        for index, first in enumerate(existing):
            for second in existing[index + 1:]:
                if evaluations >= max_evaluations:
                    break
                budget.check()
                trial = evaluator.evaluate(LocalPlan(view.hash, frozenset((first, second)), (task, first, second)))
                evaluations += 1
                if trial.total_profit is not None and (best is None or trial.total_profit > best.total_profit):
                    best = trial
        if evaluations >= max_evaluations:
            break
    return best
