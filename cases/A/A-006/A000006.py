"""RKHMA来源式初始分配案例 A-006。"""


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
        candidates = [(satellite, next((window for window in windows if window.attitudes), None))
                      for satellite, windows, _ in available]
        candidates = [(satellite, window) for satellite, window in candidates if window is not None]
        selected = min(candidates, key=lambda item: abs(item[1].attitudes[0].roll))[0] if candidates else None
        if selected is not None:
            pairs.append((selected.id, task_id))
            assigned[selected.id].append(task)
    return pairs
