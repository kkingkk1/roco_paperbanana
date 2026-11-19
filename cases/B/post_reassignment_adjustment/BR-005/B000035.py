"""Try releasing either lower- or higher-reward endpoints of broken edges."""

from satellite_scheduler.runtime.contracts import LocalPlan


def search(problem, api, view, context, evaluator, budget, rng, max_evaluations):
    if max_evaluations < 1:
        raise ValueError("max_evaluations must be positive")
    present = {item.task.id for item in view.observations}
    pending = sorted(view.assigned_task_ids - present, key=lambda task: (-problem.task(task).priority, task))
    if not context.broken_edges:
        return None
    best = None
    for policy in range(min(2, max_evaluations)):
        released = {}
        for edge in context.broken_edges:
            budget.check()
            a, b = edge.previous_task_id, edge.next_task_id
            first, second = problem.task(a).priority, problem.task(b).priority
            removed = (a if first <= second else b) if policy == 0 else (b if first <= second else a)
            released[removed] = None
        order = [task for task in pending if task in context.incoming] + list(released)
        budget.check()
        trial = evaluator.evaluate(LocalPlan(view.hash, frozenset(released), tuple(order)))
        if trial.total_profit is not None and (best is None or trial.total_profit > best.total_profit):
            best = trial
    return best
