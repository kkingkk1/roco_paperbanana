"""RKHMA��Դʽ��ʼ���䰸�� A-008��"""


def initial(problem, previously_assigned=None, rng=None, validated_candidates=None):
    """����(����ID, ����ID)���У��ɰ�(����,ʱ��)�ṩ��Դ����ʱ����֤�����"""
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
        lengths = [(satellite, sum(len(window.attitudes) - 2 for window in valid))
                   for satellite, _, valid in available]
        lengths = [(satellite, length) for satellite, length in lengths if length > 0]
        selected = max(lengths, key=lambda item: item[1])[0] if lengths else None
        if selected is not None:
            pairs.append((selected.id, task_id))
            assigned[selected.id].append(task)
    return pairs
