"""Repair incoming tasks or broken edges through three-task rotations."""

from satellite_scheduler.runtime.contracts import LocalPlan, Phase


def search(problem, api, view, context, evaluator, budget, rng, max_evaluations):
    if max_evaluations < 1:
        raise ValueError("max_evaluations must be positive")
    if context.phase is not Phase.REASSIGN:
        raise ValueError("reassignment phase required")
    existing = [item.task.id for item in sorted(view.observations, key=lambda item: item.start)]
    present = set(existing)
    incoming = sorted((task for task in context.incoming if task in view.assigned_task_ids and task not in present),
                      key=lambda task: (-problem.task(task).priority, task))
    endpoints = {task for edge in context.broken_edges for task in (edge.previous_task_id, edge.next_task_id)}
    if not incoming and not endpoints:
        return None
    best, evaluations = None, 0
    for index in range(len(existing) - 2):
        if evaluations >= max_evaluations:
            break
        a, b, c = existing[index:index + 3]
        if endpoints and not endpoints.intersection((a, b, c)):
            continue
        for rotation in ((c, a, b), (b, c, a)):
            if evaluations >= max_evaluations:
                break
            budget.check()
            trial = evaluator.evaluate(LocalPlan(view.hash, frozenset((a, b, c)), tuple(incoming) + rotation))
            evaluations += 1
            if trial.total_profit is not None and (best is None or trial.total_profit > best.total_profit):
                best = trial
    return best
