"""�����·�����˵�epsilon��������ƥ�䡣"""

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
    if left == right == 0:
        return -0.0 if math.copysign(1, left) < 0 or math.copysign(1, right) < 0 else 0.0
    return left if left <= right else right


def _source_max(left, right):
    if math.isnan(left):
        return right
    if math.isnan(right):
        return left
    if left == right == 0:
        return 0.0 if math.copysign(1, left) > 0 or math.copysign(1, right) > 0 else -0.0
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
    if columns == 0:
        raise ValueError("matrix must have a column")
    maximum, minimum = float("-inf"), float("inf")
    for row in matrix:
        if len(row) != columns:
            raise ValueError("matrix must be rectangular")
        for value in row:
            maximum, minimum = _source_max(maximum, value), _source_min(minimum, value)
    if rows != columns:
        size = max(rows, columns)
        padded = []
        for index in range(size):
            row = [maximum + 1.0] * size
            if index < rows:
                row[:columns] = matrix[index]
            padded.append(row)
        return _trim(matrix, _sap(padded, [0.0] * size, [0.0] * size, [-1] * size))
    cost_scale = _source_max(_source_max(abs(maximum), abs(minimum)), 1.0)
    if cost_scale > 1e150:
        return _sap(matrix, [0.0] * rows, [0.0] * rows, [-1] * rows)
    prices, assigned_rows, assigned_columns = [0.0] * rows, [-1] * rows, [-1] * rows
    epsilon = abs(maximum - minimum) * 0.5
    if not math.isfinite(epsilon) or epsilon == 0.0:
        epsilon = cost_scale
    target = cost_scale * 1e-8
    for _ in range(60):
        if epsilon < target:
            break
        if not _auction_pass(matrix, prices, assigned_rows, assigned_columns, epsilon):
            break
        epsilon *= 0.5
    if -1 in assigned_rows:
        return _lapjv(matrix)
    return _result(matrix, assigned_rows, assigned_columns)


def _auction_pass(matrix, prices, rows, columns, epsilon):
    rows[:], columns[:] = [-1] * len(rows), [-1] * len(columns)
    unassigned = deque(range(len(rows)))
    for _ in range(200_000):
        if not unassigned:
            return True
        bidder = unassigned.popleft()
        best, best_value, second = -1, float("inf"), float("inf")
        for item in range(len(rows)):
            value = matrix[bidder][item] + prices[item]
            if value < best_value:
                second, best_value, best = best_value, value, item
            elif value < second:
                second = value
        if best == -1:
            raise ValueError("bidder has no comparable item")
        prices[best] += epsilon if second == float("inf") else second - best_value + epsilon
        if columns[best] != -1:
            displaced = columns[best]
            unassigned.append(displaced)
            rows[displaced] = -1
        rows[bidder], columns[best] = best, bidder
    return False


def _lapjv(matrix):
    size = len(matrix)
    v, rows, columns, claims = [0.0] * size, [-1] * size, [-1] * size, [0] * size
    for column in range(size):
        best_row, best_value = 0, matrix[0][column]
        for row in range(1, size):
            if matrix[row][column] < best_value:
                best_value, best_row = matrix[row][column], row
        v[column] = best_value
        claims[best_row] += 1
        prior = rows[best_row]
        if prior == -1:
            rows[best_row], columns[column] = column, best_row
        elif best_value < v[prior]:
            rows[best_row], columns[column], columns[prior] = column, best_row, -1
        else:
            columns[column] = -1
    for row in range(size):
        if claims[row] != 1:
            continue
        selected, other = rows[row], float("inf")
        for column in range(size):
            if column != selected and matrix[row][column] - v[column] < other:
                other = matrix[row][column] - v[column]
        if math.isfinite(other):
            v[selected] -= other
    u = [float("inf")] * size
    for row in range(size):
        for column in range(size):
            u[row] = _source_min(u[row], matrix[row][column] - v[column])
    return _sap(matrix, u, v, rows)
