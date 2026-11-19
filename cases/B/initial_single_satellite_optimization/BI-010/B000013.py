"""Initial ordering with tabu pairwise exchanges."""


def search(problem, api, view, evaluator, budget, rng, max_evaluations):
    if max_evaluations < 1:
        raise ValueError("max_evaluations must be positive")
    order = sorted(view.assigned_task_ids)
    budget.check()
    best = evaluator.evaluate(order)
    evaluations, iteration, tabu_until = 1, 0, {}
    while evaluations < max_evaluations:
        chosen = chosen_order = chosen_pair = None
        for i in range(len(order)):
            for j in range(i + 1, len(order)):
                if evaluations >= max_evaluations:
                    break
                budget.check()
                first, second = order[i], order[j]
                pair = min(first, second), max(first, second)
                candidate = order.copy()
                candidate[i], candidate[j] = second, first
                trial = evaluator.evaluate(candidate)
                evaluations += 1
                forbidden = tabu_until.get(pair, -1) > iteration
                if forbidden and trial.total_profit <= best.total_profit:
                    continue
                if chosen is None or trial.total_profit > chosen.total_profit:
                    chosen, chosen_order, chosen_pair = trial, candidate, pair
        if chosen is None:
            break
        order = chosen_order
        tabu_until[chosen_pair] = iteration + 4
        if chosen.total_profit > best.total_profit:
            best = chosen
        iteration += 1
    return best
