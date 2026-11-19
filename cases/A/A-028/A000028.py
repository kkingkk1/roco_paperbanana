"""按资源配额初始化并通过跨资源任务交换改进总得分。"""

import math
import struct
import time
from dataclasses import dataclass


_FLOAT_MAX = struct.unpack(">f", bytes.fromhex("7f7fffff"))[0]


@dataclass(frozen=True)
class Assignment:
    rows_by_resource: tuple[tuple[int, ...], ...]
    total_score: float
    stop_reason: str
    iterations: int


def _float32(value):
    try:
        return struct.unpack(">f", struct.pack(">f", value))[0]
    except OverflowError:
        return math.copysign(float("inf"), value)


def _divide(first, second):
    if second == 0:
        if first == 0 or math.isnan(first):
            return float("nan")
        return math.copysign(float("inf"), math.copysign(1.0, first) * math.copysign(1.0, second))
    return _float32(first / second)


def _quicksort(scores, order):
    pending = [(0, len(order) - 1)]
    while pending:
        low, high = pending.pop()
        if low >= high:
            continue
        pivot, boundary = scores[order[high]], low - 1
        for index in range(low, high):
            if scores[order[index]] > pivot:
                boundary += 1
                order[boundary], order[index] = order[index], order[boundary]
        split = boundary + 1
        order[split], order[high] = order[high], order[split]
        pending.append((split + 1, high))
        pending.append((low, split - 1))


def _compensated_sum(scores, groups):
    total = compensation = 0.0
    for column, group in enumerate(groups):
        for row in group:
            value = _float32(scores[row][column] - compensation)
            following = _float32(total + value)
            compensation = _float32(_float32(following - total) - value)
            total = following
    return total


def _gains(scores, rows, source, target):
    values, maximum = [], float("-inf")
    for row in rows:
        value = _float32(scores[row][target] - scores[row][source])
        values.append(value)
        if value > maximum:
            maximum = value
    return maximum, values


def _best_unused(values, used):
    best = -1
    for index, value in enumerate(values):
        if not used[index] and (best < 0 or value > values[best]):
            best = index
    return best


def solve(scores, quotas, max_seconds, absolute_threshold, percent_threshold, max_iterations, skip_first_phase):
    rows, columns = len(scores), len(quotas)
    if columns < 2:
        raise ValueError("at least two resources are required")
    if max_seconds < 0 or absolute_threshold < 0 or percent_threshold < 0 or max_iterations < 0:
        raise ValueError("limits must be nonnegative")
    total = 0
    for quota in quotas:
        if quota <= 0:
            raise ValueError("quotas must be positive")
        total += quota
        if total > 2**31 - 1:
            raise OverflowError("integer overflow")
    if total != rows:
        raise ValueError("quotas must sum to the number of rows")
    converted = []
    for row in scores:
        if len(row) != columns:
            raise ValueError("matrix must be rectangular and match quotas")
        values = [_float32(value) for value in row]
        if any(not math.isfinite(value) or value == -_FLOAT_MAX for value in values):
            raise ValueError("scores must be finite and greater than -Float.MAX_VALUE")
        converted.append(values)
    scores = converted
    if columns == 2:
        max_iterations, skip_first_phase = 0, False
    start = time.monotonic_ns()
    assignment = [0] * rows
    if skip_first_phase:
        position = 0
        for column in range(columns):
            for _ in range(quotas[column]):
                assignment[position] = column
                position += 1
    else:
        priority, order, remaining = [0.0] * rows, list(range(rows)), list(quotas)
        for row in range(rows):
            best, second = scores[row][0], -_FLOAT_MAX
            for column in range(1, columns):
                value = scores[row][column]
                if value > best:
                    second, best = best, value
                elif value > second:
                    second = value
            if second == -_FLOAT_MAX:
                second = best
            priority[row] = _float32(best + _float32(best - second))
        _quicksort(priority, order)
        for row in order:
            best, best_value = -1, -_FLOAT_MAX
            for column in range(columns):
                if remaining[column] > 0 and scores[row][column] > best_value:
                    best, best_value = column, scores[row][column]
            if best < 0:
                raise ValueError("no assignable score remains")
            assignment[row] = best
            remaining[best] -= 1
    groups = [[] for _ in range(columns)]
    for row, column in enumerate(assignment):
        groups[column].append(row)
    total_score = _compensated_sum(scores, groups)

    def result(reason, iterations):
        return Assignment(tuple(tuple(group) for group in groups), total_score, reason, iterations)

    if max_iterations == 0:
        return result("no_swap", 0)
    if (time.monotonic_ns() - start) / 1_000_000_000.0 > max_seconds:
        return result("first_step", 0)
    cached = [[0.0] * columns for _ in range(columns)]
    previously_unchanged = [False] * columns
    for iteration in range(max_iterations):
        gain_per_column, unchanged = [0.0] * columns, [True] * columns
        for first in range(columns - 1):
            old_first = previously_unchanged[first]
            for second in range(first + 1, columns):
                if iteration > 0 and old_first and previously_unchanged[second]:
                    continue
                if iteration > 0 and old_first:
                    max_first = cached[first][second]
                    max_second, gain_second = _gains(scores, groups[second], second, first)
                    cached[second][first] = max_second
                    if _float32(max_first + max_second) <= 0:
                        continue
                    max_first, gain_first = _gains(scores, groups[first], first, second)
                    cached[first][second] = max_first
                elif iteration > 0 and previously_unchanged[second]:
                    max_second = cached[second][first]
                    max_first, gain_first = _gains(scores, groups[first], first, second)
                    cached[first][second] = max_first
                    if _float32(max_first + max_second) <= 0:
                        continue
                    max_second, gain_second = _gains(scores, groups[second], second, first)
                    cached[second][first] = max_second
                else:
                    max_first, gain_first = _gains(scores, groups[first], first, second)
                    max_second, gain_second = _gains(scores, groups[second], second, first)
                    cached[first][second], cached[second][first] = max_first, max_second
                if _float32(max_first + max_second) <= 0:
                    continue
                used_first, used_second = [False] * quotas[first], [False] * quotas[second]
                selected = []
                while len(selected) < min(quotas[first], quotas[second]):
                    i, j = _best_unused(gain_first, used_first), _best_unused(gain_second, used_second)
                    if i < 0 or j < 0 or _float32(gain_first[i] + gain_second[j]) <= 0:
                        break
                    selected.append((i, j))
                    used_first[i] = used_second[j] = True
                for i, j in selected:
                    row_first, row_second = groups[first][i], groups[second][j]
                    groups[first][i], groups[second][j] = row_second, row_first
                    gain_per_column[first] = _float32(gain_per_column[first]
                                                     + _float32(scores[row_second][first] - scores[row_first][first]))
                    gain_per_column[second] = _float32(gain_per_column[second]
                                                      + _float32(scores[row_first][second] - scores[row_second][second]))
                if selected:
                    if iteration == 0:
                        unchanged[first] = unchanged[second] = False
                    else:
                        unchanged[first] &= max_first < 0
                        unchanged[second] &= max_second < 0
        previously_unchanged = unchanged
        increase = 0.0
        for gain in gain_per_column:
            increase = _float32(increase + gain)
        total_score = _float32(total_score + increase)
        completed = iteration + 1
        if max_seconds > 0 and (time.monotonic_ns() - start) / 1_000_000_000.0 > max_seconds:
            return result("time", completed)
        percent = _divide(_float32(100 * increase), total_score) if percent_threshold > 0 else 0.0
        if (increase <= 0 or (absolute_threshold > 0 and increase < absolute_threshold)
                or (percent_threshold > 0 and percent < percent_threshold)):
            return result("tolerance", completed)
    return result("iteration_limit", max_iterations)
