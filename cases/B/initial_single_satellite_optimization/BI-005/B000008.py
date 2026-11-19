"""Initial ordering by first-improvement task insertion."""


def search(problem, api, view, evaluator, budget, rng, max_evaluations):
    if max_evaluations < 1:
        raise ValueError("max_evaluations must be positive")
    order = sorted(view.assigned_task_ids)
    budget.check()
    best = evaluator.evaluate(order)
    evaluations, improved = 1, True
    while improved and evaluations < max_evaluations:
        improved = False
        for source in range(len(order)):
            for target in range(len(order)):
                if evaluations >= max_evaluations:
                    break
                if source == target:
                    continue
                budget.check()
                candidate = order.copy()
                candidate.insert(target, candidate.pop(source))
                trial = evaluator.evaluate(candidate)
                evaluations += 1
                if trial.total_profit > best.total_profit:
                    best, order, improved = trial, candidate, True
                    break
            if improved:
                break
    return best
