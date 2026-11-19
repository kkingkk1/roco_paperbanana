"""RKHMA-inspired affected-satellite insertion and local refinement."""


def search(problem, api, view, context, evaluator, budget, rng, max_evaluations):
    if max_evaluations < 1:
        raise ValueError("max_evaluations must be positive")
    assigned = view.assigned_task_ids
    base, included = [], set()
    for item in sorted(view.observations, key=lambda observation: observation.start):
        budget.check()
        if item.task.id in assigned and item.task.id not in included:
            included.add(item.task.id)
            base.append(item.task.id)
    pending = sorted(assigned - included,
                     key=lambda task: (task not in context.incoming, -problem.task(task).priority, task))
    has_existing_order = bool(base)
    base.extend(pending)
    budget.check()
    best = evaluator.rebuild(base)
    if best.total_profit is None:
        best = None
    evaluations = 1
    if len(base) < 2 or evaluations >= max_evaluations:
        return best
    if not has_existing_order:
        population = [base.copy()]
        while evaluations < max_evaluations:
            budget.check()
            candidate = population[rng.randrange(len(population))].copy()
            first = rng.randrange(len(candidate))
            second = rng.randrange(len(candidate) - 1)
            if second >= first:
                second += 1
            candidate[first], candidate[second] = candidate[second], candidate[first]
            trial = evaluator.rebuild(candidate)
            evaluations += 1
            if trial.total_profit is not None and (best is None or trial.total_profit > best.total_profit):
                best = trial
                population.append(candidate.copy())
                if len(population) > 6:
                    population.pop(0)
        return best
    working = base.copy()
    for task in pending:
        for position in range(len(working)):
            if evaluations >= max_evaluations:
                break
            budget.check()
            candidate = working.copy()
            candidate.remove(task)
            candidate.insert(position, task)
            trial = evaluator.rebuild(candidate)
            evaluations += 1
            if trial.total_profit is not None and (best is None or trial.total_profit > best.total_profit):
                best, working = trial, candidate
        if evaluations >= max_evaluations:
            break
    for index in range(len(working) - 1):
        if evaluations >= max_evaluations:
            break
        budget.check()
        candidate = working.copy()
        candidate[index], candidate[index + 1] = candidate[index + 1], candidate[index]
        trial = evaluator.rebuild(candidate)
        evaluations += 1
        if trial.total_profit is not None and (best is None or trial.total_profit > best.total_profit):
            best, working = trial, candidate
    return best
