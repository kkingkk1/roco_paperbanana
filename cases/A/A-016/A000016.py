"""优先减少同星窗口冲突，再按任务时长负载分配。"""

from satellite_scheduler.runtime.contracts import Assignment


def _overlap(left, right, budget):
    for first in left:
        for second in right:
            budget.check()
            if first.start < second.end and second.start < first.end:
                return True
    return False


def assign(problem, api, budget):
    owners, assigned, load = {}, {}, {}
    ordered = sorted(problem.tasks, key=lambda task: (-task.priority, task.id))
    for task in ordered:
        budget.check()
        best = best_key = None
        for satellite in api.satellite_ids:
            budget.check()
            if not api.has_candidate(task.id, satellite, budget):
                continue
            conflicts = 0
            for other in assigned.get(satellite, ()):
                budget.check()
                if _overlap(api.windows(task.id, satellite), api.windows(other, satellite), budget):
                    conflicts += 1
            key = (conflicts, load.get(satellite, 0), satellite)
            if best_key is None or key < best_key:
                best, best_key = satellite, key
        owners[task.id] = best
        if best is not None:
            assigned.setdefault(best, []).append(task.id)
            load[best] = load.get(best, 0) + task.duration
    return Assignment(owners)
