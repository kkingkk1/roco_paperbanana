"""按任务的卫星得分遗憾值进行初始分配。"""

from satellite_scheduler.runtime.contracts import Assignment


def assign(problem, api, budget):
    owners, load = {}, {}
    remaining = sorted(problem.tasks, key=lambda task: task.id)
    while remaining:
        budget.check()
        chosen = best_satellite = best_key = None
        for task in remaining:
            choices = []
            for satellite in api.satellite_ids:
                budget.check()
                count = api.candidate_count(task.id, satellite, budget)
                if count:
                    choices.append((count / (1.0 + load.get(satellite, 0)), satellite))
            if not choices:
                continue
            choices.sort(key=lambda item: (-item[0], item[1]))
            regret = float("inf") if len(choices) == 1 else choices[0][0] - choices[1][0]
            key = (-regret, -task.priority, task.id)
            if best_key is None or key < best_key:
                chosen, best_satellite, best_key = task, choices[0][1], key
        if chosen is None:
            owners.update((task.id, None) for task in remaining)
            break
        owners[chosen.id] = best_satellite
        load[best_satellite] = load.get(best_satellite, 0) + chosen.duration
        remaining.remove(chosen)
    return Assignment(owners)
