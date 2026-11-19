"""二分成本阈值，并用Hopcroft–Karp搜索瓶颈匹配。"""

import math
from collections import deque
from dataclasses import dataclass


@dataclass(frozen=True)
class Assignment:
    total_cost: float
    row_assignment: tuple[int, ...]
    column_assignment: tuple[int, ...]


def _source_max(left, right):
    return right if math.isnan(left) else left if math.isnan(right) or left >= right else right


def _hopcroft_karp(cost, threshold):
    size = len(cost)
    pair_u, pair_v, distance = [0] * (size + 1), [0] * (size + 1), [0] * (size + 1)
    infinity = 2**31 - 1

    def bfs():
        queue = deque()
        for row in range(1, size + 1):
            if pair_u[row] == 0:
                distance[row] = 0
                queue.append(row)
            else:
                distance[row] = infinity
        distance[0] = infinity
        while queue:
            row = queue.popleft()
            if distance[row] < distance[0]:
                for column in range(1, size + 1):
                    if cost[row - 1][column - 1] <= threshold + 1e-12:
                        following = pair_v[column]
                        if distance[following] == infinity:
                            distance[following] = distance[row] + 1
                            queue.append(following)
        return distance[0] != infinity

    def dfs(row):
        if row == 0:
            return True
        for column in range(1, size + 1):
            if cost[row - 1][column - 1] <= threshold + 1e-12:
                following = pair_v[column]
                if distance[following] == distance[row] + 1 and dfs(following):
                    pair_v[column], pair_u[row] = row, column
                    return True
        return False

    matching_size = 0
    while bfs():
        for row in range(1, size + 1):
            if pair_u[row] == 0 and dfs(row):
                matching_size += 1
    rows = tuple(pair_u[row] - 1 if pair_u[row] else -1 for row in range(1, size + 1))
    columns = tuple(pair_v[column] - 1 if pair_v[column] else -1 for column in range(1, size + 1))
    return matching_size, rows, columns


def solve(matrix):
    rows = len(matrix)
    if rows == 0:
        return Assignment(0.0, (), ())
    columns = len(matrix[0])
    maximum = float("-inf")
    for values in matrix:
        if len(values) != columns:
            raise ValueError("matrix must be rectangular")
        for value in values:
            maximum = _source_max(maximum, value)
    size = max(rows, columns)
    padded = []
    for row in range(size):
        values = [maximum + 1.0] * size
        if row < rows:
            values[:columns] = matrix[row]
        padded.append(values)
    costs = sorted(value for values in padded for value in values if math.isfinite(value))
    thresholds = []
    for value in costs:
        if not thresholds or abs(value - thresholds[-1]) >= 1e-12:
            thresholds.append(value)
    if not thresholds:
        return Assignment(0.0, tuple([-1] * rows), tuple([-1] * columns))
    low, high, best_index, best = 0, len(thresholds) - 1, len(thresholds) - 1, None
    while low <= high:
        middle = (low + high) // 2
        candidate = _hopcroft_karp(padded, thresholds[middle])
        if candidate[0] == size:
            best_index, best = middle, candidate
            if middle == 0:
                break
            high = middle - 1
        else:
            low = middle + 1
    if best is None:
        best = _hopcroft_karp(padded, thresholds[best_index])
    row_assignment, column_assignment = [-1] * rows, [-1] * columns
    bottleneck = float("-inf")
    for row in range(rows):
        column = best[1][row]
        if 0 <= column < columns:
            row_assignment[row] = column
            bottleneck = _source_max(bottleneck, matrix[row][column])
    for column in range(columns):
        row = best[2][column]
        if 0 <= row < rows:
            column_assignment[column] = row
    return Assignment(bottleneck if math.isfinite(bottleneck) else 0.0,
                      tuple(row_assignment), tuple(column_assignment))
