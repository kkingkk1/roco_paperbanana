"""枚举合法初始归属，最小化各卫星任务时长负载的平方和。"""

from satellite_scheduler.runtime.contracts import Assignment


def assign(problem, api, budget):
    choices = {}
    for task in problem.tasks:
        eligible = []
        for satellite in api.satellite_ids:
            budget.check()
            if api.has_candidate(task.id, satellite, budget):
                eligible.append(satellite)
        choices[task.id] = eligible
    tasks = sorted(problem.tasks, key=lambda task: (len(choices[task.id]), task.id))
    load, current = {}, {}
    best, best_cost = None, float("inf")

    def visit(index, cost):
        nonlocal best, best_cost
        budget.check()
        if cost >= best_cost:
            return
        if index == len(tasks):
            best, best_cost = dict(current), cost
            return
        task = tasks[index]
        eligible = choices[task.id]
        if not eligible:
            current[task.id] = None
            visit(index + 1, cost)
            del current[task.id]
            return
        for satellite in eligible:
            budget.check()
            old = load.get(satellite, 0)
            updated = old + task.duration
            load[satellite] = updated
            current[task.id] = satellite
            visit(index + 1, cost + float(updated) * updated - float(old) * old)
            load[satellite] = old
            del current[task.id]

    visit(0, 0.0)
    if best is None:
        raise RuntimeError("no assignment produced")
    return Assignment(best)
