"""Iterated first-improvement swaps with random reversal perturbation."""


def search(problem, api, view, evaluator, budget, rng, max_evaluations):
    if max_evaluations < 1:
        raise ValueError("max_evaluations must be positive")
    order = sorted(view.assigned_task_ids)
    budget.check()
    best = evaluator.evaluate(order)
    evaluations = 1
    if len(order) < 2:
        return best
    current = best
    while evaluations < max_evaluations:
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
                if trial.total_profit > current.total_profit:
                    current, order, improved = trial, candidate, True
                    if trial.total_profit > best.total_profit:
                        best = trial
                    break
            if improved:
                break
        if not improved and evaluations < max_evaluations:
            budget.check()
            left = rng.randrange(len(order))
            right = rng.randrange(len(order) - 1)
            if right >= left:
                right += 1
            if left > right:
                left, right = right, left
            candidate = order.copy()
            candidate[left:right + 1] = reversed(candidate[left:right + 1])
            current = evaluator.evaluate(candidate)
            evaluations += 1
            order = candidate
            if current.total_profit > best.total_profit:
                best = current
    return best
