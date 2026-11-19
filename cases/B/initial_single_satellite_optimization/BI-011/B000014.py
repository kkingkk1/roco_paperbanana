"""Initial ordering by late-acceptance pairwise swaps."""


def search(problem, api, view, evaluator, budget, rng, max_evaluations):
    if max_evaluations < 1:
        raise ValueError("max_evaluations must be positive")
    order = sorted(view.assigned_task_ids)
    budget.check()
    best = current = evaluator.evaluate(order)
    history = [current.total_profit] * 5
    evaluations = 1
    while evaluations < max_evaluations and len(order) >= 2:
        budget.check()
        first = rng.randrange(len(order))
        second = rng.randrange(len(order) - 1)
        if second >= first:
            second += 1
        candidate = order.copy()
        candidate[first], candidate[second] = candidate[second], candidate[first]
        trial = evaluator.evaluate(candidate)
        slot = (evaluations - 1) % len(history)
        evaluations += 1
        if trial.total_profit >= current.total_profit or trial.total_profit >= history[slot]:
            current, order = trial, candidate
        history[slot] = current.total_profit
        if trial.total_profit > best.total_profit:
            best = trial
    return best
