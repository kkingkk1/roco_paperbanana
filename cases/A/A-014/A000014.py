"""先用增广路径匹配卫星，再按负载分配剩余任务。"""

from satellite_scheduler.runtime.contracts import Assignment


def _augment(task, matched, seen, api, budget):
    for satellite in api.satellite_ids:
        budget.check()
        if satellite in seen or not api.has_candidate(task, satellite, budget):
            continue
        seen.add(satellite)
        old = matched.get(satellite)
        if old is None or _augment(old, matched, seen, api, budget):
            matched[satellite] = task
            return True
    return False


def assign(problem, api, budget):
    ordered = sorted(problem.tasks, key=lambda task: (-task.priority, task.id))
    matched, load = {}, {}
    for task in ordered:
        _augment(task.id, matched, set(), api, budget)
    owners = {task.id: None for task in ordered}
    for satellite, task in matched.items():
        owners[task] = satellite
        load[satellite] = load.get(satellite, 0) + problem.task(task).duration
    for task in ordered:
        budget.check()
        if owners[task.id] is not None:
            continue
        selected = None
        for satellite in api.satellite_ids:
            budget.check()
            if not api.has_candidate(task.id, satellite, budget):
                continue
            if selected is None or (load.get(satellite, 0), satellite) < (load.get(selected, 0), selected):
                selected = satellite
        if selected is not None:
            owners[task.id] = selected
            load[selected] = load.get(selected, 0) + task.duration
    return Assignment(owners)
