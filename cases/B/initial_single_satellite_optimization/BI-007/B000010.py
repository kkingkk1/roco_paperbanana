"""Initial ordering by swap, insertion and reversal descent."""


def search(problem, api, view, evaluator, budget, rng, max_evaluations):
    if max_evaluations < 1:
        raise ValueError("max_evaluations must be positive")
    order = sorted(view.assigned_task_ids)
    budget.check()
    best = evaluator.evaluate(order)
    evaluations, neighborhood = 1, 0
    while neighborhood < 3 and evaluations < max_evaluations:
        improved = False
        for i in range(len(order)):
            for j in range(len(order)):
                if evaluations >= max_evaluations:
                    break
                if i == j or (neighborhood != 1 and j < i):
                    continue
                budget.check()
                candidate = order.copy()
                if neighborhood == 0:
                    candidate[i], candidate[j] = candidate[j], candidate[i]
                elif neighborhood == 1:
                    candidate.insert(j, candidate.pop(i))
                else:
                    candidate[i:j + 1] = reversed(candidate[i:j + 1])
                trial = evaluator.evaluate(candidate)
                evaluations += 1
                if trial.total_profit > best.total_profit:
                    best, order, improved = trial, candidate, True
                    break
            if improved:
                break
        neighborhood = 0 if improved else neighborhood + 1
    return best
