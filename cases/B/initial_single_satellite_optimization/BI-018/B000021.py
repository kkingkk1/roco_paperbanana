"""Initial ordering by first-improvement adjacent swaps."""


def search(problem, api, view, evaluator, budget, rng, max_evaluations):
    if max_evaluations < 1:
        raise ValueError("max_evaluations must be positive")
    order = sorted(view.assigned_task_ids)
    budget.check()
    best = evaluator.evaluate(order)
    evaluations, improved = 1, True
    while improved and evaluations < max_evaluations:
        improved = False
        for index in range(len(order) - 1):
            if evaluations >= max_evaluations:
                break
            budget.check()
            candidate = order.copy()
            candidate[index], candidate[index + 1] = candidate[index + 1], candidate[index]
            trial = evaluator.evaluate(candidate)
            evaluations += 1
            if trial.total_profit > best.total_profit:
                best, order, improved = trial, candidate, True
                break
    return best
