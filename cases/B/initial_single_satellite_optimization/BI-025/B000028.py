"""Initial ordering by shortest imaging duration."""


def search(problem, api, view, evaluator, budget, rng, max_evaluations):
    if max_evaluations < 1:
        raise ValueError("max_evaluations must be positive")
    order = sorted(view.assigned_task_ids,
                   key=lambda task: (problem.task(task).duration, -problem.task(task).priority, task))
    budget.check()
    return evaluator.evaluate(order)
