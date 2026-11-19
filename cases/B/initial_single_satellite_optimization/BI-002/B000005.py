"""Initial ordering by the earliest feasible observation."""


def search(problem, api, view, evaluator, budget, rng, max_evaluations):
    if max_evaluations < 1:
        raise ValueError("max_evaluations must be positive")
    earliest = {}
    for task in view.assigned_task_ids:
        budget.check()
        start = float("inf")
        for candidate in api.candidates(task, view.satellite_id, budget):
            budget.check()
            start = min(start, candidate.start)
        earliest[task] = start
    order = sorted(view.assigned_task_ids, key=lambda task: (earliest[task], -problem.task(task).priority, task))
    budget.check()
    return evaluator.evaluate(order)
