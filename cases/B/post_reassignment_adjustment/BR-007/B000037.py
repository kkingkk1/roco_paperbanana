"""Compare scarcity-first and density-first complete rebuilds."""


def search(problem, api, view, context, evaluator, budget, rng, max_evaluations):
    if max_evaluations < 1:
        raise ValueError("max_evaluations must be positive")
    counts = {}
    for task in view.assigned_task_ids:
        budget.check()
        counts[task] = api.candidate_count(task, view.satellite_id, budget)
    scarcity = sorted(view.assigned_task_ids, key=lambda task: (counts[task], -problem.task(task).priority, task))
    budget.check()
    first = evaluator.rebuild(scarcity)
    if max_evaluations == 1:
        return first
    density = sorted(view.assigned_task_ids,
                     key=lambda task: (-problem.task(task).priority / problem.task(task).duration, task))
    budget.check()
    second = evaluator.rebuild(density)
    if first.total_profit is None:
        return None if second.total_profit is None else second
    return second if second.total_profit is not None and second.total_profit > first.total_profit else first
