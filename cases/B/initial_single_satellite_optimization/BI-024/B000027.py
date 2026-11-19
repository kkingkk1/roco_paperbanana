"""Initial ordering by feasible candidate count."""


def search(problem, api, view, evaluator, budget, rng, max_evaluations):
    if max_evaluations < 1:
        raise ValueError("max_evaluations must be positive")
    counts = {}
    for task in view.assigned_task_ids:
        budget.check()
        counts[task] = api.candidate_count(task, view.satellite_id, budget)
    order = sorted(view.assigned_task_ids, key=lambda task: (counts[task], -problem.task(task).priority, task))
    budget.check()
    return evaluator.evaluate(order)
