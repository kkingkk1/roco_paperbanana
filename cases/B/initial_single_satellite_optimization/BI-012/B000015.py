"""Beam search over task prefixes with complete-order trial evaluation."""


def search(problem, api, view, evaluator, budget, rng, max_evaluations):
    if max_evaluations < 1:
        raise ValueError("max_evaluations must be positive")
    tasks = sorted(view.assigned_task_ids)
    budget.check()
    best = evaluator.evaluate(tasks)
    evaluations, beam = 1, [((), best)]
    for depth in range(len(tasks)):
        if evaluations >= max_evaluations:
            break
        expanded = []
        for prefix, _ in beam:
            for task in tasks:
                if task in prefix or evaluations >= max_evaluations:
                    continue
                budget.check()
                next_prefix = (*prefix, task)
                order = list(next_prefix) + [other for other in tasks if other not in next_prefix]
                trial = evaluator.evaluate(order)
                evaluations += 1
                if trial.total_profit > best.total_profit:
                    best = trial
                expanded.append((next_prefix, trial))
        expanded.sort(key=lambda node: (-node[1].total_profit, str(list(node[0]))))
        beam = expanded[:3]
        if not beam:
            break
    return best
