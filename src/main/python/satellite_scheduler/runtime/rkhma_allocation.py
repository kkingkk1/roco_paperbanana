"""RKHMA A3：复合初始归属与按实际试评分接受跨星移动。"""

from bisect import bisect_left, bisect_right, insort
from collections import deque
from math import ceil, isfinite

from satellite_scheduler.problem import ProblemAdapter

from .contracts import Assignment, Move
from .rkhma_keys import RkhmaKeys


class RkhmaAllocation:
    def __init__(self, keys=None, *, max_rounds=25, perturb_threshold=1, perturb_strength=.02):
        if max_rounds < 0 or perturb_threshold <= 0 or not isfinite(perturb_strength) or perturb_strength < 0:
            raise ValueError('A3 重分配参数无效')
        self.keys = keys if keys is not None else RkhmaKeys()
        self.max_rounds = max_rounds
        self.perturb_threshold = perturb_threshold
        self.perturb_strength = perturb_strength

    def initial_assign(self, problem, api, budget, rng):
        adapter = ProblemAdapter(problem)
        satellites = tuple(sat.id for sat in problem.satellites)
        loads = dict.fromkeys(satellites, 0)
        starts, ends = ({sat: [] for sat in satellites} for _ in range(2))
        assigned_windows = {sat: [] for sat in satellites}
        degenerate_satellites = set()
        owners = {}
        for task in sorted(problem.tasks, key=lambda item: -item.priority):
            budget.check()
            metrics = []
            visible = 0
            for sat in satellites:
                windows = api.windows(task.id, sat)
                usable = []
                for window in windows:
                    budget.check()
                    if next(adapter.candidates(window), None) is not None:
                        usable.append(window)
                visible += bool(usable)
                length = sum(len(window.attitudes) - 2 for window in usable)
                if length > 0:
                    metrics.append((sat, length, len(usable), windows))
            owners[task.id] = None
            if not metrics:
                continue
            longest = max(row[1] for row in metrics)
            most_windows = max(row[2] for row in metrics)
            max_load = max(1, max(loads[sat] for sat, *_ in metrics))
            scarcity = .5 / visible
            best, best_score = None, float('-inf')
            for sat, length, count, windows in metrics:
                budget.check()
                overlap = 0.
                if starts[sat]:
                    if sat in degenerate_satellites or any(w.start >= w.end for w in windows):
                        # 原始窗口包括不可观测的窗口；退化端点按原始重叠判定逐对计算。
                        pairs = 0
                        for window in windows:
                            budget.check()
                            pairs += sum(window.start < old.end and window.end > old.start
                                         for old in assigned_windows[sat])
                    else:
                        pairs = sum(bisect_left(starts[sat], w.end) - bisect_right(ends[sat], w.start)
                                    for w in windows)
                    overlap = pairs / (len(windows) * len(starts[sat]))
                score = (.37 * (1 + scarcity) * (length / longest) + .20 * (count / most_windows)
                         + .25 * (1 - scarcity) * (1 - loads[sat] / max_load) - .25 * overlap)
                if score > best_score:
                    best, best_score = sat, score
            owners[task.id] = best
            loads[best] += 1
            for window in api.windows(task.id, best):
                insort(starts[best], window.start)
                insort(ends[best], window.end)
                assigned_windows[best].append(window)
                if window.start >= window.end:
                    degenerate_satellites.add(best)
        return Assignment(owners)

    def prepare(self, problem, api, assignment, budget, seed):
        self.keys.prepare(problem, api, assignment, budget, seed)
        self._finished = False

    def accepts_non_improving_result(self):
        # 完成的来源校准结果允许低于本轮途中保存的最佳可行解。
        return True

    def search_reassignment(self, problem, api, root, feedback, evaluator, budget, rng):
        if self._finished or len(root.timelines) <= 1:
            return None
        self._finished = True
        working, result, profit = root, None, _profit(root)
        failures, no_migration, second_chances = {}, 0, 0
        recent = deque(maxlen=5)
        previous = profit

        def collect(state):
            missing = self.keys.tasks.keys() - _scheduled(state)
            for task in self.keys.tasks:
                failures[task] = failures.get(task, 0) + 1 if task in missing else 0
            return missing

        for round_index in range(max(1, self.max_rounds)):
            budget.check()
            missing = collect(working)
            if not missing:
                break
            # 第 0 轮只观察初始解；盈利上升后也等到下一轮停滞。
            if round_index == 0 or profit > previous:
                previous = profit
                continue
            previous = profit
            for task, failed in failures.items():
                if self.perturb_strength > 0 and failed >= self.perturb_threshold:
                    self.keys.perturb(task, failed / self.perturb_threshold, self.perturb_strength)
                    failures[task] = 0
            selected = self._migrate(api, working, missing, profit, evaluator, budget)
            if selected is None:
                no_migration += 1
            else:
                no_migration = 0
                collect(selected.state)
                result = self._calibrate(selected.state, evaluator)
                working, profit, previous = result.state, result.total_profit, result.total_profit
                missing = collect(working)
            recent.append((profit, len(missing)))
            if len(recent) == 5 and no_migration >= 4:
                profits, counts = zip(*recent)
                if (max(profits) - min(profits)) / (max(profits) + 1e-9) < .001 and max(counts) - min(counts) <= 2:
                    if second_chances == 2:
                        break
                    second_chances += 1
                    result = self._calibrate(working, evaluator)
                    working, profit = result.state, result.total_profit
        return result if result is not None and result.state.hash != root.hash else None

    @staticmethod
    def _calibrate(base, evaluator):
        trial = evaluator.calibrate(base)
        if trial.state is None:
            raise ValueError('A3 全星校准没有返回可行调度')
        evaluator.checkpoint(trial)
        return trial

    def _migrate(self, api, base, missing, profit, evaluator, budget):
        keys = self.keys
        max_profit = max(1, max(keys.tasks[t].priority for t in missing))
        max_sats = max(1, max(len(keys.pairs[t]) for t in missing))
        max_windows = max(1, max(keys.windows[t] for t in missing))

        def score(task):
            return (5 * (keys.tasks[task].priority / max_profit) + 2 * (len(keys.pairs[task]) / max_sats)
                    + keys.windows[task] / max_windows + 3 * keys.task_key[task])

        queue = sorted(missing, key=lambda task: (-score(task), keys.task_index[task]))
        priority_count = min(5, len(queue))
        cap, limit = max(1, min(12, ceil(.2 * len(queue)))), max(50, int(.2 * len(queue)))
        count, failed, last = 0, 0, None

        def evaluate(edits):
            budget.check()
            edits = {task: sat for task, sat in edits.items() if sat != base.assignment.owners[task]}
            if not edits or any(sat is not None and not api.has_candidate(task, sat, budget)
                                for task, sat in edits.items()):
                return None
            return evaluator.evaluate(base, Move(base.hash, edits))

        def accept(trial):
            nonlocal base, profit, last
            evaluator.checkpoint(trial)
            base, profit, last = trial.state, trial.total_profit, trial
            for task, sat in trial.move.edits.items():
                if sat is not None:
                    keys.feedback(task, sat, True)

        for index, task in enumerate(queue):
            if count >= cap:
                break
            targets = self._targets(task, base, 4 if index < priority_count else 2)
            best, failed_sat, failed_profit = None, None, float('-inf')
            for sat in targets:
                trial = evaluate({task: sat})
                if trial is not None and trial.state is not None and trial.total_profit > profit:
                    best = _better(best, trial)
                else:
                    observed = trial.total_profit if trial is not None and trial.state is not None else 0
                    if observed > failed_profit:
                        failed_sat, failed_profit = sat, observed
            if best is None and failed_sat is not None:
                keys.feedback(task, failed_sat, False)
            if best is None and (index < priority_count or len(queue) <= 10):
                targets = self._targets(task, base, 4)
                best = self._replacement(task, targets, api, base, evaluate, profit, budget)
                if best is None:
                    best = self._swap(task, targets, api, base, evaluate, profit, budget)
                else:
                    queue.extend(t for t, sat in best.move.edits.items() if sat is None and t not in queue)
            if best is None:
                failed += 1
                if failed >= limit:
                    break
            else:
                accept(best)
                count += 1
                failed = 0

        bundled = set()
        still_missing = set(queue) - _scheduled(base)
        for tasks in self._bundles([t for t in queue if t in still_missing], budget):
            if bundled.intersection(tasks):
                continue
            common = set.intersection(*(set(keys.pairs[t]) for t in tasks))
            best = None
            for sat in sorted(common, key=keys.satellite_index.get):
                trial = evaluate(dict.fromkeys(tasks, sat))
                if _improved(trial, profit):
                    best = _better(best, trial)
            if best is not None:
                accept(best)
                bundled.update(tasks)
        return last

    def _targets(self, task, base, limit):
        pairs = self.keys.pairs[task]
        candidates = [sat for sat in pairs if sat != base.assignment.owners[task]]
        if not candidates:
            return ()
        count = max(pairs[sat][0] for sat in candidates)
        length = max(pairs[sat][1] for sat in candidates)
        start = min(pairs[sat][2] for sat in candidates)

        def score(sat):
            n, span, first = pairs[sat]
            return (.346 * (n / count) + .274 * (span / length if length > 1e-12 else 0.)
                    + .18 * (1 / (1 + max(0., first - start))) + .20 * self.keys.pair(task, sat))

        return sorted(candidates, key=lambda sat: (-score(sat), self.keys.satellite_index[sat]))[:limit]

    def _replacement(self, task, targets, api, base, evaluate, profit, budget):
        keys = self.keys
        best = None
        for sat in targets:
            windows = api.windows(task, sat)
            eligible = []
            for item in base.timelines[sat]:
                budget.check()
                if (item.task.priority, keys.task_key[item.task.id]) >= (keys.tasks[task].priority, keys.task_key[task]):
                    continue
                overlap = _overlap(item, windows)
                if overlap or any(max(w.start - item.end, item.start - w.end) <= 60. for w in windows):
                    eligible.append((item, overlap))
            eligible.sort(key=lambda pair: (pair[1] == 0, pair[0].task.priority, -pair[1]))
            improved = False
            for item, _ in eligible[:3]:
                trial = evaluate({task: sat, item.task.id: None})
                if _improved(trial, profit):
                    improved, best = True, _better(best, trial)
            if not improved and len(eligible) >= 2:
                batch = sorted(eligible, key=lambda pair: -pair[1])[:3]
                for amount in range(2, len(batch) + 1):
                    edits = {item.task.id: None for item, _ in batch[:amount]}
                    trial = evaluate({**edits, task: sat})
                    if _improved(trial, profit):
                        best = _better(best, trial)
        return best

    def _swap(self, task, targets, api, base, evaluate, profit, budget):
        keys = self.keys
        best = None
        for sat in targets:
            windows = api.windows(task, sat)
            eligible = []
            for item in base.timelines[sat]:
                budget.check()
                if len(keys.pairs[item.task.id]) > 1 and _overlap(item, windows):
                    eligible.append(item.task.id)
            eligible.sort(key=lambda other: (keys.tasks[other].priority, keys.task_key[other]))
            for other in eligible[:3]:
                pairs = keys.pairs[other]
                receivers = sorted((s for s in pairs if s != sat), key=lambda s: -pairs[s][0])
                for receiver in receivers[:2]:
                    trial = evaluate({task: sat, other: receiver})
                    if _improved(trial, profit):
                        best = _better(best, trial)
        return best

    def _bundles(self, tasks, budget):
        pairs = self.keys.pairs
        bundles = []
        for index, first in enumerate(tasks):
            budget.check()
            if not pairs[first]:
                continue
            for j in range(index + 1, len(tasks)):
                budget.check()
                second = tasks[j]
                for sat, (_, _, a) in pairs[first].items():
                    if sat not in pairs[second] or abs(a - pairs[second][sat][2]) > 120:
                        continue
                    b = pairs[second][sat][2]
                    bundle = (first, second)
                    for k in range(j + 1, len(tasks)):
                        budget.check()
                        third = tasks[k]
                        if sat in pairs[third]:
                            c = pairs[third][sat][2]
                            if max(a, b, c) - min(a, b, c) <= 120:
                                bundle += (third,)
                                break
                    bundles.append(bundle)
                    break
                if len(bundles) >= 10:
                    break
            if len(bundles) >= 10:
                break
        return sorted(bundles, key=lambda group: (-len(group), -sum(self.keys.tasks[t].priority for t in group)))[:5]


def _overlap(observation, windows):
    return sum(max(0., min(observation.end, win.end) - max(observation.start, win.start)) for win in windows)


def _scheduled(state):
    return {item.task.id for row in state.timelines.values() for item in row}


def _profit(state):
    return sum(item.task.priority for row in state.timelines.values() for item in row)


def _improved(trial, profit):
    return trial is not None and trial.state is not None and trial.total_profit > profit


def _better(best, trial):
    return trial if best is None or trial.total_profit > best.total_profit else best
