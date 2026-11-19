"""RKHMA A3/B3 使用的任务、星窗分数和来源随机流。"""

from collections import defaultdict
from math import log, sqrt


class SourceRandom:
    """保留来源 48 位随机流，使同一输入的小样例可逐项对照。"""

    _multiplier = 0x5DEECE66D
    _mask = (1 << 48) - 1

    def __init__(self, seed):
        self._state = (seed ^ self._multiplier) & self._mask
        self._spare = None

    def _next(self, bits):
        self._state = (self._state * self._multiplier + 11) & self._mask
        return self._state >> (48 - bits)

    def random(self):
        return ((self._next(26) << 27) + self._next(27)) / (1 << 53)

    def randrange(self, bound):
        if bound & (bound - 1) == 0:
            return (bound * self._next(31)) >> 31
        while True:
            bits = self._next(31)
            value = bits % bound
            if bits - value + bound <= 1 << 31:
                return value

    def gaussian(self):
        if self._spare is not None:
            value, self._spare = self._spare, None
            return value
        while True:
            x, y = 2 * self.random() - 1, 2 * self.random() - 1
            radius = x * x + y * y
            if 0 < radius < 1:
                scale = sqrt(-2 * log(radius) / radius)
                self._spare = y * scale
                return x * scale


def _clip(value):
    return min(1.0, max(0.0, value))


class RkhmaKeys:
    def prepare(self, problem, api, assignment, budget, seed):
        self.random = SourceRandom(seed ^ 0x9E3779B97F4A7C15)
        self.tasks = {task.id: task for task in problem.tasks}
        self.task_index = {task: index for index, task in enumerate(self.tasks)}
        self.satellite_index = {sat.id: index for index, sat in enumerate(problem.satellites)}
        self.initial = assignment.owners
        self.rank = {}
        self.pairs = {}
        self.task_key, self.pair_key = {}, {}
        self.max_priority = max(1, max((task.priority for task in self.tasks.values()), default=0))
        loads = defaultdict(int)
        for task, sat in self.initial.items():
            if sat is not None:
                self.rank[task] = loads[sat]
                loads[sat] += 1
        for task in self.tasks:
            features = {}
            for sat in self.satellite_index:
                budget.check()
                windows = api.windows(task, sat)
                if windows:
                    features[sat] = (len(windows), sum(max(0., window.end - window.start) for window in windows),
                                     min(window.start for window in windows))
            self.pairs[task] = features
        self.windows = {task: sum(pair[0] for pair in pairs.values()) for task, pairs in self.pairs.items()}
        max_sats = max(1, max((len(pairs) for pairs in self.pairs.values()), default=0))
        max_windows = max(1, max(self.windows.values(), default=0))
        for task, item in self.tasks.items():
            budget.check()
            features = self.pairs[task]
            feasible = (len(features) / max_sats + self.windows[task] / max_windows) / 2
            self.task_key[task] = _clip(.55 * (item.priority / self.max_priority) + .30 * feasible
                                        + .15 * self.random.random())
            if not features:
                continue
            count = max(pair[0] for pair in features.values())
            length = max(1., max(pair[1] for pair in features.values()))
            start = min(pair[2] for pair in features.values())
            for sat, (n, span, first) in features.items():
                self.pair_key[task, sat] = _clip(.40 * (n / count) + .30 * (span / length)
                                                + .20 * (1 / (1 + max(0., first - start)))
                                                + .10 * self.random.random())

    def pair(self, task, sat):
        return self.pair_key.get((task, sat), 0.0)

    def feedback(self, task, sat, success):
        self.pair_key[task, sat] = _clip(self.pair(task, sat) + (.08 if success else -.01))

    def perturb(self, task, failures, strength):
        boost = .25 * failures * strength
        noise = self.random.gaussian() * strength * .25
        self.task_key[task] = _clip(self.task_key[task] + boost + noise)

    def ordered(self, tasks, sat):
        def score(task):
            rank = self.rank.get(task, float('inf'))
            return ((1.5 if self.initial[task] == sat else 0.) + self.task_key[task]
                    + .65 * self.pair(task, sat) + .001 / (1 + rank))
        return tuple(sorted(tasks, key=lambda task: (-score(task), self.task_index[task])))

    def baseline(self, tasks, sat):
        return tuple(sorted(tasks, key=lambda task: (self.initial[task] != sat,
                                                     self.rank.get(task, float('inf')),
                                                     self.task_key[task], self.task_index[task])))
