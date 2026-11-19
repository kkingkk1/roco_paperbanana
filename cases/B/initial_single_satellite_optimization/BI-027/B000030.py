"""Initial ordering by reward divided by candidate-envelope conflicts."""


def search(problem, api, view, evaluator, budget, rng, max_evaluations):
    if max_evaluations < 1:
        raise ValueError("max_evaluations must be positive")
    envelopes = {}
    for task in view.assigned_task_ids:
        budget.check()
        first, last = float("inf"), float("-inf")
        for candidate in api.candidates(task, view.satellite_id, budget):
            budget.check()
            first, last = min(first, candidate.start), max(last, candidate.end)
        envelopes[task] = first, last
    scores = {}
    for task in view.assigned_task_ids:
        budget.check()
        first, last = envelopes[task]
        conflicts = 0
        for other in view.assigned_task_ids:
            budget.check()
            other_first, other_last = envelopes[other]
            if other != task and first < other_last and other_first < last:
                conflicts += 1
        scores[task] = problem.task(task).priority / (1 + conflicts)
    order = sorted(view.assigned_task_ids, key=lambda task: (-scores[task], task))
    budget.check()
    return evaluator.evaluate(order)
