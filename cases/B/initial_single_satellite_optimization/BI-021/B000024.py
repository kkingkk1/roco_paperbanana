"""Build an order using greedy insertion positions and full-order trials."""


def search(problem, api, view, evaluator, budget, rng, max_evaluations):
    if max_evaluations < 1:
        raise ValueError("max_evaluations must be positive")
    order = sorted(view.assigned_task_ids)
    budget.check()
    best = evaluator.evaluate(order)
    evaluations, prefix = 1, []
    for task in order:
        chosen = chosen_trial = None
        for position in range(len(prefix) + 1):
            if evaluations >= max_evaluations:
                break
            budget.check()
            candidate_prefix = prefix.copy()
            candidate_prefix.insert(position, task)
            full = candidate_prefix + [other for other in order if other not in candidate_prefix]
            trial = evaluator.evaluate(full)
            evaluations += 1
            if trial.total_profit > best.total_profit:
                best = trial
            if chosen_trial is None or trial.total_profit > chosen_trial.total_profit:
                chosen, chosen_trial = candidate_prefix, trial
        if chosen is None:
            break
        prefix = chosen
    return best
