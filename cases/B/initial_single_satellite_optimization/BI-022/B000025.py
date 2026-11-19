"""Build an order using regret-ranked insertion positions and full-order trials."""


def search(problem, api, view, evaluator, budget, rng, max_evaluations):
    if max_evaluations < 1:
        raise ValueError("max_evaluations must be positive")
    order = sorted(view.assigned_task_ids)
    budget.check()
    best = evaluator.evaluate(order)
    evaluations, prefix, remaining = 1, [], order.copy()
    while remaining and evaluations < max_evaluations:
        chosen, chosen_task = None, None
        best_regret = best_score = float("-inf")
        for task in remaining:
            first = second = None
            task_prefix = None
            for position in range(len(prefix) + 1):
                if evaluations >= max_evaluations:
                    break
                budget.check()
                candidate_prefix = prefix.copy()
                candidate_prefix.insert(position, task)
                full = candidate_prefix + [other for other in remaining if other != task]
                trial = evaluator.evaluate(full)
                evaluations += 1
                if trial.total_profit > best.total_profit:
                    best = trial
                score = trial.total_profit
                if first is None or score > first:
                    second, first, task_prefix = first, score, candidate_prefix
                elif second is None or score > second:
                    second = score
            if task_prefix is None:
                break
            regret = 0 if second is None else first - second
            if (regret > best_regret or (regret == best_regret and first > best_score)
                    or (regret == best_regret and first == best_score and task < chosen_task)):
                best_regret, best_score, chosen_task, chosen = regret, first, task, task_prefix
        if chosen is None:
            break
        prefix = chosen
        remaining.remove(chosen_task)
    return best
