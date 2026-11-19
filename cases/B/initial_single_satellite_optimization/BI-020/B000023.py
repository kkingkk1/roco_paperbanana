"""Ant-style task-order search with rank-independent pheromone updates."""


def search(problem, api, view, evaluator, budget, rng, max_evaluations):
    if max_evaluations < 1:
        raise ValueError("max_evaluations must be positive")
    tasks = sorted(view.assigned_task_ids)
    budget.check()
    best = evaluator.evaluate(tasks)
    evaluations, n = 1, len(tasks)
    if n == 0:
        return best
    pheromone = [[1.0] * n for _ in range(n + 1)]
    while evaluations < max_evaluations:
        budget.check()
        used, order, previous = [False] * n, [], n
        for _ in range(n):
            budget.check()
            total = sum(pheromone[previous][i] for i in range(n) if not used[i])
            pick = rng.random() * total
            selected = -1
            for i in range(n):
                if not used[i]:
                    selected = i
                    pick -= pheromone[previous][i]
                    if pick <= 0:
                        break
            used[selected] = True
            order.append(tasks[selected])
            previous = selected
        trial = evaluator.evaluate(order)
        evaluations += 1
        if trial.total_profit > best.total_profit:
            best = trial
        for row in pheromone:
            for i in range(n):
                row[i] = max(1e-12, row[i] * .9)
        previous = n
        for task in best.priorities:
            index = tasks.index(task)
            pheromone[previous][index] += 1.0
            previous = index
    return best
