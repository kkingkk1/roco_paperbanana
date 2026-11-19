"""Single-parent random-key evolution strategy for initial ordering."""


def search(problem, api, view, evaluator, budget, rng, max_evaluations):
    if max_evaluations < 1:
        raise ValueError("max_evaluations must be positive")
    tasks = sorted(view.assigned_task_ids)
    budget.check()
    best = current = evaluator.evaluate(tasks)
    if len(tasks) < 2:
        return best
    keys = [float(index) for index in range(len(tasks))]
    evaluations = 1
    while evaluations < max_evaluations:
        budget.check()
        candidate = [value + rng.gauss(0, 1) for value in keys]
        indices = sorted(range(len(candidate)), key=lambda index: (candidate[index], tasks[index]))
        trial = evaluator.evaluate([tasks[index] for index in indices])
        evaluations += 1
        if trial.total_profit >= current.total_profit:
            current, keys = trial, candidate
        if trial.total_profit > best.total_profit:
            best = trial
    return best
