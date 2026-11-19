"""Simulated annealing with insertion moves for initial ordering."""

import math


def search(problem, api, view, evaluator, budget, rng, max_evaluations):
    if max_evaluations < 1:
        raise ValueError("max_evaluations must be positive")
    order = sorted(view.assigned_task_ids)
    budget.check()
    best = current = evaluator.evaluate(order)
    evaluations, temperature = 1, 10.0
    while evaluations < max_evaluations and len(order) >= 2:
        budget.check()
        source = rng.randrange(len(order))
        target = rng.randrange(len(order) - 1)
        if target >= source:
            target += 1
        candidate = order.copy()
        candidate.insert(target, candidate.pop(source))
        trial = evaluator.evaluate(candidate)
        evaluations += 1
        delta = float(trial.total_profit) - current.total_profit
        if delta >= 0 or rng.random() < math.exp(delta / max(temperature, 1e-12)):
            current, order = trial, candidate
        if trial.total_profit > best.total_profit:
            best = trial
        temperature *= .99
    return best
