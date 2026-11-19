"""Elitist permutation GA with tournament selection and order crossover."""


def search(problem, api, view, evaluator, budget, rng, max_evaluations):
    if max_evaluations < 1:
        raise ValueError("max_evaluations must be positive")
    tasks = sorted(view.assigned_task_ids)
    budget.check()
    best = evaluator.evaluate(tasks)
    if len(tasks) < 2:
        return best
    population, evaluations = [best], 1
    while len(population) < 8 and evaluations < max_evaluations:
        order = tasks.copy()
        for index in range(len(order) - 1, 0, -1):
            target = rng.randrange(index + 1)
            order[index], order[target] = order[target], order[index]
        budget.check()
        trial = evaluator.evaluate(order)
        evaluations += 1
        population.append(trial)
        if trial.total_profit > best.total_profit:
            best = trial
    while evaluations < max_evaluations:
        population.sort(key=lambda item: -item.total_profit)
        next_population = [population[0]]
        while len(next_population) < len(population) and evaluations < max_evaluations:
            parent1 = _tournament(population, rng)
            parent2 = _tournament(population, rng)
            left, right = rng.randrange(len(tasks)), rng.randrange(len(tasks))
            if left > right:
                left, right = right, left
            child = [None] * len(tasks)
            for index in range(left, right + 1):
                child[index] = parent1.priorities[index]
            slot = 0
            for task in parent2.priorities:
                if task in child:
                    continue
                while child[slot] is not None:
                    slot += 1
                child[slot] = task
            if rng.random() < .25:
                first, second = rng.randrange(len(child)), rng.randrange(len(child))
                child[first], child[second] = child[second], child[first]
            budget.check()
            trial = evaluator.evaluate(child)
            evaluations += 1
            next_population.append(trial)
            if trial.total_profit > best.total_profit:
                best = trial
        population = next_population
    return best


def _tournament(population, rng):
    first = population[rng.randrange(len(population))]
    second = population[rng.randrange(len(population))]
    return first if first.total_profit >= second.total_profit else second
