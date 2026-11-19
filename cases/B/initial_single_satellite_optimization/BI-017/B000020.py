"""Initial ordering by best-improvement subsequence reversal."""


def search(problem, api, view, evaluator, budget, rng, max_evaluations):
    if max_evaluations < 1:
        raise ValueError("max_evaluations must be positive")
    order = sorted(view.assigned_task_ids)
    budget.check()
    best = evaluator.evaluate(order)
    evaluations = 1
    while evaluations < max_evaluations:
        pass_best, pass_order = best, order
        for first in range(len(order)):
            for last in range(first + 1, len(order)):
                if evaluations >= max_evaluations:
                    break
                budget.check()
                candidate = order.copy()
                candidate[first:last + 1] = reversed(candidate[first:last + 1])
                trial = evaluator.evaluate(candidate)
                evaluations += 1
                if trial.total_profit > pass_best.total_profit:
                    pass_best, pass_order = trial, candidate
        if pass_best is best:
            break
        best, order = pass_best, pass_order
    return best
