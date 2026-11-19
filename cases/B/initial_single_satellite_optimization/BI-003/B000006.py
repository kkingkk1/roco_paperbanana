"""Initial ordering by first-improvement pairwise swaps."""


def search(problem, api, view, evaluator, budget, rng, max_evaluations):
    if max_evaluations < 1:
        raise ValueError("max_evaluations must be positive")
    order = sorted(view.assigned_task_ids)
    budget.check()
    best = evaluator.evaluate(order)
    evaluations = 1
    improved = True
    while improved and evaluations < max_evaluations:
        improved = False
        for i in range(len(order)):
            for j in range(i + 1, len(order)):
                if evaluations >= max_evaluations:
                    break
                budget.check()
                candidate = order.copy()
                candidate[i], candidate[j] = candidate[j], candidate[i]
                trial = evaluator.evaluate(candidate)
                evaluations += 1
                if trial.total_profit > best.total_profit:
                    best, order, improved = trial, candidate, True
                    break
            if improved:
                break
    return best
