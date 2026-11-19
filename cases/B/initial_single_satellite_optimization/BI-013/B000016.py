"""Enumerate task permutations under a trial-evaluation budget."""


def search(problem, api, view, evaluator, budget, rng, max_evaluations):
    if max_evaluations < 1:
        raise ValueError("max_evaluations must be positive")
    tasks = sorted(view.assigned_task_ids)
    budget.check()
    best = evaluator.evaluate(tasks)
    evaluations = 1

    def visit(prefix, remaining):
        nonlocal best, evaluations
        budget.check()
        if evaluations >= max_evaluations:
            return
        if not remaining:
            if prefix != tasks:
                trial = evaluator.evaluate(prefix.copy())
                evaluations += 1
                if trial.total_profit > best.total_profit:
                    best = trial
            return
        for index in range(len(remaining)):
            if evaluations >= max_evaluations:
                break
            task = remaining.pop(index)
            prefix.append(task)
            visit(prefix, remaining)
            prefix.pop()
            remaining.insert(index, task)

    visit([], tasks.copy())
    return best
