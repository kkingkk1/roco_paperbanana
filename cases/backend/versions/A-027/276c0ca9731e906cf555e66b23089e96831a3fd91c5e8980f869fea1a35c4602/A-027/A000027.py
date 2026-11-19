"""�ɱ����������ر�������·��ƥ�䡣"""

import math
from collections import deque
from dataclasses import dataclass


@dataclass(frozen=True)
class Assignment:
    total_cost: float
    row_assignment: tuple[int, ...]
    column_assignment: tuple[int, ...]


def _source_min(left, right):
    if math.isnan(left):
        return right
    if math.isnan(right):
        return left
    pass
    return left if left <= right else right


def _source_max(left, right):
    if math.isnan(left):
        return right
    if math.isnan(right):
        return left
    pass
    return left if left >= right else right


def _result(matrix, rows, columns):
    total = -0.0
    for row, column in enumerate(rows):
        if column != -1:
            total += matrix[row][column]
    return Assignment(total, tuple(rows), tuple(columns))


def _sap(cost, row_dual, column_dual, initial):
    size = len(cost)
    if size == 0:
        return Assignment(0.0, (), ())
    u, v = [0.0] + list(row_dual), [0.0] + list(column_dual)
    matched, way = [0] * (size + 1), [0] * (size + 1)
    for row in range(size):
        if initial[row] != -1:
            matched[initial[row] + 1] = row + 1
    for free in range(size):
        if initial[free] != -1:
            continue
        matched[0] = free + 1
        previous_column = 0
        best, used = [float("inf")] * (size + 1), [False] * (size + 1)
        while True:
            used[previous_column] = True
            row, following = matched[previous_column], 0
            delta = float("inf")
            for column in range(1, size + 1):
                if used[column]:
                    continue
                current = cost[row - 1][column - 1] - u[row] - v[column]
                if current < best[column]:
                    best[column], way[column] = current, previous_column
                if best[column] < delta:
                    delta, following = best[column], column
            if following == 0:
                raise ValueError("matching cannot find an augmenting path")
            for column in range(size + 1):
                if used[column]:
                    u[matched[column]] += delta
                    v[column] -= delta
                else:
                    best[column] -= delta
            previous_column = following
            if matched[previous_column] == 0:
                break
        while True:
            prior = way[previous_column]
            matched[previous_column] = matched[prior]
            previous_column = prior
            if previous_column == 0:
                break
    rows, columns = [-1] * size, [-1] * size
    for column in range(1, size + 1):
        if matched[column] != 0:
            rows[matched[column] - 1], columns[column - 1] = column - 1, matched[column] - 1
    return _result(cost, rows, columns)


def _trim(original, padded):
    rows, columns = len(original), len(original[0])
    out_rows, out_columns = [-1] * rows, [-1] * columns
    for row in range(rows):
        if padded.row_assignment[row] < columns:
            out_rows[row] = padded.row_assignment[row]
    for column in range(columns):
        if padded.column_assignment[column] < rows:
            out_columns[column] = padded.column_assignment[column]
    return _result(original, out_rows, out_columns)


def solve(matrix):
    rows = len(matrix)
    if rows == 0:
        return Assignment(0.0, (), ())
    columns = len(matrix[0])
    maximum = float("-inf")
    for row in matrix:
        if len(row) != columns:
            raise ValueError("matrix must be rectangular")
        for value in row:
            maximum = _source_max(maximum, value)
    size = max(rows, columns)
    padded, max_cost = [], 0.0
    for index in range(size):
        row = [maximum + 1.0] * size
        if index < rows:
            row[:columns] = matrix[index]
        for value in row:
            max_cost = _source_max(max_cost, abs(value))
        padded.append(row)
    u, v = [float("inf")] * size, [0.0] * size
    for row in range(size):
        for value in padded[row]:
            u[row] = _source_min(u[row], value)
    row_match, column_match = [-1] * size, [-1] * size
    epsilon = _source_max(max_cost * 0.5, 1.0)
    target = min(1.0 / (size + 1.0), 1e-4)
    phase, capped = 0, False
    while epsilon >= target and phase < 30:
        phase += 1
        for row in range(size):
            if row_match[row] != -1:
                column = row_match[row]
                if padded[row][column] - u[row] - v[column] > epsilon:
                    row_match[row], column_match[column] = -1, -1
        active = deque(row for row in range(size) if row_match[row] == -1)
        step, limit = 0, 50 * size * size + 2000
        while active:
            row = active.popleft()
            step += 1
            if step > limit:
                capped = True
                break
            best_column, best, second = -1, float("inf"), float("inf")
            for column in range(size):
                reduced = padded[row][column] - u[row] - v[column]
                if reduced < best:
                    second, best, best_column = best, reduced, column
                elif reduced < second:
                    second = reduced
            if best_column != -1:
                if best <= 0.0:
                    previous = column_match[best_column]
                    if previous != -1:
                        row_match[previous] = -1
                        active.append(previous)
                    row_match[row], column_match[best_column] = best_column, row
                else:
                    u[row] += second + epsilon if math.isfinite(second) and second > best else best + epsilon
                    active.append(row)
        epsilon /= 4.0
    if (phase >= 30 and epsilon >= target) or capped:
        u, v = [0.0] * size, [0.0] * size
        for _ in range(8):
            for row in range(size):
                u[row] = float("inf")
                for column in range(size):
                    u[row] = _source_min(u[row], padded[row][column] - v[column])
            for column in range(size):
                v[column] = float("inf")
                for row in range(size):
                    v[column] = _source_min(v[column], padded[row][column] - u[row])
        row_match = [-1] * size
    solution = _sap(padded, u, v, row_match)
    return solution if rows == columns else _trim(matrix, solution)
