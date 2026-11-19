"""RKHMA B3：初始 DE/PSO 混合搜索、重分配快修和全星校准。"""

from collections import Counter
from dataclasses import dataclass
from math import exp, floor

from satellite_scheduler.problem import transformation_time

from .rkhma_keys import RkhmaKeys, SourceRandom

_BOUND = 750.
_ENSEMBLE_OFFSET = 0x6C62272E07BB0142


@dataclass(frozen=True, slots=True)
class _Anchor:
    tasks: tuple[int, ...]
    genes: tuple[float, ...]
    order: tuple[int, ...]


class RkhmaHybridSearch:
    def __init__(self, shared_keys=None, *, population=None, evaluations=None):
        if ((population is None) != (evaluations is None)
                or (population is not None and (population < 6 or evaluations < population))):
            raise ValueError('B3 固定预算需要至少 6 个个体，评价次数不得小于种群大小')
        self._fixed = population, evaluations
        self.keys = shared_keys if shared_keys is not None else RkhmaKeys()
        self._shared = shared_keys is not None

    def prepare(self, problem, api, assignment, budget, seed):
        if not self._shared:
            self.keys.prepare(problem, api, assignment, budget, seed)
        self._seed = seed
        self._anchors = {}
        self._calibration = {}
        maximum = max(1, max(Counter(s for s in assignment.owners.values() if s is not None).values(), default=0))
        self.population, self.evaluations = self._fixed
        if self.population is None:
            self.population = max(6, floor(2.5 * maximum + .5))
            self.evaluations = max(self.population, 250 * maximum)

    def prime_initial(self, problem, api, view, evaluator, budget, rng):
        return evaluator.evaluate(self.keys.baseline(view.assigned_task_ids, view.satellite_id))

    def search_initial(self, problem, api, view, evaluator, budget, rng):
        return self._search(view, evaluator.evaluate, budget, self._anchors, commit=True)

    def search_initial_local(self, problem, api, view, context, feedback, evaluator, budget, rng):
        return None

    def uses_instance_budget_for_reassignment(self):
        return True

    def begin_calibration(self, problem, api, base, budget):
        self._calibration = self._anchors.copy()

    def search_calibration(self, problem, api, view, context, feedback, evaluator, budget, rng):
        if feedback.attempt:
            return None
        return self._search(view, evaluator.rebuild, budget, self._calibration, commit=True)

    def search_reassignment(self, problem, api, view, context, feedback, evaluator, budget, rng):
        if feedback.attempt:
            return None
        anchor = self._anchors.get(view.satellite_id)
        if anchor is None:
            return self._search(view, evaluator.rebuild, budget, self._anchors, commit=False)
        return self._repair(api, view, context, evaluator, budget, anchor)

    def _search(self, view, evaluate, budget, anchors, *, commit):
        sat = view.satellite_id
        tasks = self.keys.ordered(view.assigned_task_ids, sat)
        if not tasks:
            return evaluate(())
        inherited = anchors.get(sat)
        # 新星两份种群；继承态按来源的校准/局部预算折算，并保留下限。
        effective = self.evaluations
        if inherited is not None:
            ratio, generations = (.5, 20) if commit else (.2, 5)
            effective = min(effective, max(floor(effective * ratio + .5), self.population * generations))
        ensemble = 2 if commit and inherited is None else 1
        real = _encode(self.keys.baseline(tasks, sat), tasks)
        heuristic = tuple(min(1., self.keys.task_key[t] * 1.000001) for t in tasks)
        old = dict(zip(inherited.tasks, inherited.genes)) if inherited is not None else None
        donor = ()
        if not commit:
            current = set(tasks)
            best_template = ()
            for other in sorted(anchors, key=self.keys.satellite_index.get):
                if other != sat:
                    template = tuple(task for task in anchors[other].tasks if task in current)
                    if len(template) > len(best_template):
                        best_template = template
            if len(best_template) >= 2:
                donor = _encode(tuple(dict.fromkeys(best_template + tasks)), tasks)
        winner = None
        for index in range(ensemble):
            budget.check()
            rand = SourceRandom(self._seed + self.keys.satellite_index[sat] + index * _ENSEMBLE_OFFSET)
            population = _random_rows(self.population, len(tasks), -_BOUND, _BOUND, rand, budget)
            population[0], population[1] = real, heuristic
            if old is not None:
                population[2] = tuple(old.get(task, key) for task, key in zip(tasks, heuristic))
            if donor:
                population[3] = donor
            genes, order, trial = _hybrid(tasks, population, effective, evaluate, rand, budget)
            if winner is None or _profit(trial) > _profit(winner[2]):
                winner = genes, order, trial
        genes, order, trial = winner
        if commit:
            self._anchors[sat] = _Anchor(tasks, genes, order)
        return trial

    def _repair(self, api, view, context, evaluator, budget, anchor):
        sat = view.satellite_id
        base = [task for task in anchor.order if task in view.assigned_task_ids]
        anchors = []
        retained = 0
        for task in anchor.order:
            if task in context.outgoing:
                anchors.append(retained)
            elif task in view.assigned_task_ids:
                retained += 1
        existing_windows = tuple(window for task in base for window in api.windows(task, sat))
        present = set(base)
        scores = {}
        for task in context.incoming - present:
            budget.check()
            new_windows = api.windows(task, sat)
            conflicts = 0
            for window in new_windows:
                budget.check()
                conflicts += sum(window.start < old.end and window.end > old.start for old in existing_windows)
            overlap = conflicts / max(1, len(new_windows) * len(existing_windows))
            scores[task] = (4 * self.keys.task_key[task] + 2.5 * self.keys.pair(task, sat)
                            + 3 * (self.keys.tasks[task].priority / self.keys.max_priority) - 2 * overlap)
        added = sorted(scores, key=lambda task: (-scores[task], self.keys.task_index[task]))
        for task in added:
            best_order, best_score = None, float('-inf')
            for pos in _positions(len(base), anchors):
                order = base[:pos] + [task] + base[pos:]
                trial = evaluator.rebuild(order, frozenset({task}))
                fitness = _fitness(trial)
                if fitness > best_score:
                    best_order, best_score = order, fitness
            base = best_order
        # 撤除或交换后按来源重新解码，再用实际收益决定相邻交换。
        best = evaluator.rebuild(base)
        if not added:
            return best
        additions = set(added)
        first = next(i for i, task in enumerate(base) if task in additions)
        for pos in range(max(0, first - 5), len(base) - 1):
            base[pos], base[pos + 1] = base[pos + 1], base[pos]
            candidate = evaluator.rebuild(base)
            if _profit(candidate) > _profit(best):
                best = candidate
            else:
                base[pos], base[pos + 1] = base[pos + 1], base[pos]
        return best


def _positions(size, anchors):
    if size < 4:
        return range(size + 1)
    step = size / 3
    base = {0, size // 2, size, floor(step + .5), floor(2 * step + .5)}
    for anchor in anchors:
        base.update(min(size, max(0, anchor + offset)) for offset in range(-2, 3))
    return sorted(base)


def _encode(order, tasks):
    weights = {task: (len(order) - rank) / len(order) for rank, task in enumerate(order)}
    return tuple(weights[task] for task in tasks)


def _random_rows(amount, dimension, low, high, rng, budget):
    rows = []
    for _ in range(amount):
        budget.check()
        rows.append(tuple(low + (high - low) * rng.random() for _ in range(dimension)))
    return rows


def _hybrid(tasks, population, evaluations, evaluate, rng, budget):
    size, dimension = len(population), len(tasks)
    velocity = _random_rows(size, dimension, -40., 40., rng, budget)

    def assess(rows):
        candidates = []
        for genes in rows:
            budget.check()
            order = tuple(tasks[i] for i in sorted(range(dimension), key=genes.__getitem__, reverse=True))
            trial = evaluate(order)
            candidates.append((_fitness(trial), genes, order, trial))
        return candidates

    current = assess(population)
    best = max(current, key=lambda candidate: candidate[0])
    used, generation = size, 0
    while used < evaluations:
        budget.check()
        progress = generation / (evaluations - size)
        # 两个父算子依次更新全种群，在下一代开始前才重新检查评价次数。
        weight = .01 + (.1 - .01) / (1 + exp(12 * (progress - .4)))
        children = _differential(current, dimension, rng, budget, weight)
        current = _survivors(assess(children), current, size)
        children, velocity = _particles(current, best[1], velocity, progress, rng, budget)
        current = _survivors(assess(children), current, size)
        if current[0][0] > best[0]:
            best = current[0]
        used += 2 * size
        generation += 1
    return best[1:]


def _survivors(children, parents, size):
    # 来源 G5=0：并集保留前 N，适应度并列按先子代后父代的次序。
    return sorted(children + parents, key=lambda item: item[0], reverse=True)[:size]


def _differential(current, dimension, rng, budget, weight):
    size = len(current)

    def donor(excluded):
        if len(excluded) / size >= .3:
            candidates = [i for i in range(size) if i not in excluded]
            return candidates[rng.randrange(len(candidates))]
        i = rng.randrange(size)
        while i in excluded:
            i = rng.randrange(size)
        return i

    mutants = []
    for i in range(size):
        budget.check()
        a = donor({i})
        b = donor({i, a})
        c = donor({i, a, b})
        mutated = (x + weight * (y - z) for x, y, z in zip(current[a][1], current[b][1], current[c][1]))
        mutants.append(tuple(v if -_BOUND <= v <= _BOUND else -_BOUND + 2 * _BOUND * rng.random()
                             for v in mutated))
    children = []
    for item, mutant in zip(current, mutants):
        budget.check()
        forced = rng.randrange(dimension)
        children.append(tuple(mutant[j] if rng.random() <= .3 or j == forced else gene
                              for j, gene in enumerate(item[1])))
    return children


def _particles(current, global_best, velocity, progress, rng, budget):
    inertia, c2 = .9 - .5 * progress, .5 + 2 * progress
    children, updated = [], []
    for item, speed in zip(current, velocity):
        budget.check()
        child, row = [], []
        # 完整精英选择后，个体历史最优就是该存活个体本身。
        for x, v, best in zip(item[1], speed, global_best):
            rng.random()  # 来源认知项仍消耗 r1；pbest-x 为零。
            movement = min(80., max(-80., inertia * v + c2 * rng.random() * (best - x)))
            position = x + movement
            if position < -_BOUND:
                position, movement = -_BOUND + 75 * rng.random(), -movement
            elif position > _BOUND:
                position, movement = _BOUND - 75 * rng.random(), -movement
            child.append(position)
            row.append(movement)
        children.append(tuple(child))
        updated.append(tuple(row))
    return children, updated


def _profit(trial):
    return trial.total_profit


def _fitness(trial):
    observations = trial.observations if hasattr(trial, 'observations') else trial.result.observations
    gap_sum, count = 0., 0
    for left, right in zip(observations, observations[1:]):
        gap = right.start - left.end
        if gap > 0:
            gap_sum += (gap - transformation_time(left.end_record, right.start_record)) / gap
            count += 1
    return _profit(trial) + (.001 * (gap_sum / count) if count else 0.)
