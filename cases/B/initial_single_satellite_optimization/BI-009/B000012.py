"""Random restarts followed by first-improvement pairwise swaps."""


def search(problem, api, view, evaluator, budget, rng, max_evaluations):
    if max_evaluations < 1:
        raise ValueError("max_evaluations must be positive")
    order = sorted(view.assigned_task_ids)
    budget.check()
    best = evaluator.evaluate(order)
    evaluations = 1
    if len(order) < 2:
        return best
    while evaluations < max_evaluations:
        restart = order.copy()
        for index in range(len(restart) - 1, 0, -1):
            target = rng.randrange(index + 1)
            restart[index], restart[target] = restart[target], restart[index]
        budget.check()
        current = evaluator.evaluate(restart)
        evaluations += 1
        if current.total_profit > best.total_profit:
            best = current
        improved = True
        while improved and evaluations < max_evaluations:
            improved = False
            for i in range(len(restart)):
                for j in range(i + 1, len(restart)):
                    if evaluations >= max_evaluations:
                        break
                    budget.check()
                    candidate = restart.copy()
                    candidate[i], candidate[j] = candidate[j], candidate[i]
                    trial = evaluator.evaluate(candidate)
                    evaluations += 1
                    if trial.total_profit > current.total_profit:
                        current, restart, improved = trial, candidate, True
                        if trial.total_profit > best.total_profit:
                            best = trial
                        break
                if improved:
                    break
    return best
