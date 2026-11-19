"""Initial ordering by the span of feasible start times."""


def search(problem, api, view, evaluator, budget, rng, max_evaluations):
    if max_evaluations < 1:
        raise ValueError("max_evaluations must be positive")
    spans = {}
    for task in view.assigned_task_ids:
        budget.check()
        first, last = float("inf"), float("-inf")
        for candidate in api.candidates(task, view.satellite_id, budget):
            budget.check()
            first, last = min(first, candidate.start), max(last, candidate.start)
        spans[task] = float("inf") if first == float("inf") else last - first
    order = sorted(view.assigned_task_ids, key=lambda task: (spans[task], -problem.task(task).priority, task))
    budget.check()
    return evaluator.evaluate(order)
