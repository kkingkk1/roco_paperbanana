"""Initial ordering by latest feasible start time."""


def search(problem, api, view, evaluator, budget, rng, max_evaluations):
    if max_evaluations < 1:
        raise ValueError("max_evaluations must be positive")
    latest = {}
    for task in view.assigned_task_ids:
        budget.check()
        end = float("-inf")
        for candidate in api.candidates(task, view.satellite_id, budget):
            budget.check()
            end = max(end, candidate.start)
        latest[task] = float("inf") if end == float("-inf") else end
    order = sorted(view.assigned_task_ids, key=lambda task: (latest[task], -problem.task(task).priority, task))
    budget.check()
    return evaluator.evaluate(order)
