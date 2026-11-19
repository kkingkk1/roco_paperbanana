"""RKHMA来源式初始分配案例 A-005。"""


def initial(problem, previously_assigned=None, rng=None, validated_candidates=None):
    """返回(卫星ID, 任务ID)序列；可按(窗口,时长)提供来源运行时已验证结果。"""
    assigned = {sat.id: list((previously_assigned or {}).get(sat.id, ()))
                for sat in problem.satellites}
    validated_candidates = validated_candidates or {}
    sequence = sorted(problem.tasks, key=lambda task: -task.priority)
    pairs = []
    for listed in sequence:
        task_id = listed.id
        if task_id < 0 or task_id >= len(problem.tasks):
            continue
        task = problem.tasks[task_id]
        available = []
        for satellite in problem.satellites:
            windows = problem.windows_for(task.id, satellite.id)
            valid = [window for window in windows if validated_candidates.get(
                (window, task.duration), len(window.attitudes) > task.duration + 2)]
            if valid:
                available.append((satellite, windows, valid))
        if not available:
            continue
        selected = max(available, key=lambda item: len(item[2]))[0]
        if selected is not None:
            pairs.append((selected.id, task_id))
            assigned[selected.id].append(task)
    return pairs
