"""全局竞价的初始任务分配。"""

from satellite_scheduler.runtime.contracts import Assignment


def assign(problem, api, budget):
    owners, load = {}, {}
    remaining = sorted(problem.tasks, key=lambda task: task.id)
    while remaining:
        budget.check()
        winner = best_satellite = best_key = None
        for task in remaining:
            for satellite in api.satellite_ids:
                budget.check()
                count = api.candidate_count(task.id, satellite, budget)
                if count == 0:
                    continue
                bid = float(task.priority) * count / (1.0 + load.get(satellite, 0) + task.duration)
                key = (-bid, task.id, satellite)
                if best_key is None or key < best_key:
                    winner, best_satellite, best_key = task, satellite, key
        if winner is None:
            owners.update((task.id, None) for task in remaining)
            break
        owners[winner.id] = best_satellite
        load[best_satellite] = load.get(best_satellite, 0) + winner.duration
        remaining.remove(winner)
    return Assignment(owners)
