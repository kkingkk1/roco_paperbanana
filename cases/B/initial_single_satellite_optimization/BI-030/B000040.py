"""RKHMA-inspired random-key DE/PSO initial single-satellite search."""

import math


class _Particle:
    def __init__(self, keys, trial):
        self.keys = keys
        self.velocity = [0.0] * len(keys)
        self.personal_best = keys.copy()
        self.current = trial
        self.best = trial


def _order(tasks, keys):
    return [tasks[index] for index in sorted(range(len(tasks)), key=lambda index: (-keys[index], tasks[index]))]


def _distinct_index(rng, size, first, second, third):
    while True:
        result = rng.randrange(size)
        if result != first and result != second and result != third:
            return result


def _clamp(value):
    return max(0.0, min(1.0, value))


def search(problem, api, view, evaluator, budget, rng, max_evaluations):
    if max_evaluations < 1:
        raise ValueError("max_evaluations must be positive")
    tasks = sorted(view.assigned_task_ids)
    budget.check()
    first = evaluator.evaluate(tasks)
    if len(tasks) < 2 or max_evaluations == 1:
        return first
    dimension = len(tasks)
    population_size = min(max_evaluations, max(6, math.floor(2.5 * dimension + .5)))
    initial_keys = [1.0 - i / dimension for i in range(dimension)]
    population = [_Particle(initial_keys, first)]
    global_best, global_keys, evaluations = first, initial_keys.copy(), 1
    while len(population) < population_size:
        budget.check()
        keys = [rng.random() for _ in range(dimension)]
        trial = evaluator.evaluate(_order(tasks, keys))
        evaluations += 1
        population.append(_Particle(keys, trial))
        if trial.total_profit > global_best.total_profit:
            global_best, global_keys = trial, keys.copy()
    generation = 0
    while evaluations < max_evaluations:
        for index, particle in enumerate(population):
            if evaluations >= max_evaluations:
                break
            budget.check()
            velocity = particle.velocity
            if generation % 2 == 0 and len(population) >= 4:
                a = _distinct_index(rng, len(population), index, -1, -1)
                b = _distinct_index(rng, len(population), index, a, -1)
                c = _distinct_index(rng, len(population), index, a, b)
                candidate = particle.keys.copy()
                forced = rng.randrange(dimension)
                for i in range(dimension):
                    if i == forced or rng.random() < .9:
                        candidate[i] = _clamp(population[a].keys[i]
                                              + .5 * (population[b].keys[i] - population[c].keys[i]))
            else:
                candidate, velocity = [0.0] * dimension, [0.0] * dimension
                for i in range(dimension):
                    velocity[i] = (.7 * particle.velocity[i]
                                   + 1.4 * rng.random() * (particle.personal_best[i] - particle.keys[i])
                                   + 1.4 * rng.random() * (global_keys[i] - particle.keys[i]))
                    candidate[i] = _clamp(particle.keys[i] + velocity[i])
            trial = evaluator.evaluate(_order(tasks, candidate))
            evaluations += 1
            if trial.total_profit >= particle.current.total_profit:
                particle.keys, particle.velocity, particle.current = candidate, velocity, trial
            if trial.total_profit > particle.best.total_profit:
                particle.personal_best, particle.best = candidate.copy(), trial
            if trial.total_profit > global_best.total_profit:
                global_best, global_keys = trial, candidate.copy()
        generation += 1
    return global_best
