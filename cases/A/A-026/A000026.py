"""通过Murty分区枚举多个最佳匹配。"""

import math
import sys
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


class _CandidateHeap:
    def __init__(self):
        self.entries = []

    @staticmethod
    def _greater(first, second):
        return first[0].total_cost < second[0].total_cost

    def _sift_up(self, position):
        value = self.entries[position]
        while position > 0:
            parent = (position - 1) // 2
            if not self._greater(value, self.entries[parent]):
                break
            self.entries[position] = self.entries[parent]
            position = parent
        self.entries[position] = value

    def add(self, candidate):
        self.entries.append(candidate)
        self._sift_up(len(self.entries) - 1)

    def remove(self):
        result = self.entries[0]
        last = self.entries.pop()
        if self.entries:
            self.entries[0] = last
            position, size = 0, len(self.entries)
            while 2 * position + 1 < size:
                child = 2 * position + 1
                if child + 1 < size and not self._greater(self.entries[child], self.entries[child + 1]):
                    child += 1
                self.entries[position] = self.entries[child]
                position = child
            self.entries[position] = last
            self._sift_up(position)
        return result


def _solve_subproblem(original, fixed, forbidden, fixed_count, sentinel):
    size = len(original)
    cost = [list(row) for row in original]
    for row, column in forbidden:
        cost[row][column] = sentinel
    for row, column in fixed:
        for other in range(size):
            if other != column:
                cost[row][other] = sentinel
        for other in range(size):
            if other != row:
                cost[other][column] = sentinel
    solution = _sap(cost, [0.0] * size, [0.0] * size, [-1] * size)
    if any(solution.row_assignment[row] != column for row, column in fixed):
        return None
    if any(solution.row_assignment[row] == column for row, column in forbidden):
        return None
    return (_result(original, solution.row_assignment, solution.column_assignment),
            tuple(fixed), tuple(forbidden), fixed_count)


def solve_k_best(matrix, k):
    if k < 0:
        raise ValueError("k must be nonnegative")
    rows = len(matrix)
    if rows == 0 or k == 0:
        return ()
    columns = len(matrix[0])
    maximum = float("-inf")
    for row in matrix:
        if len(row) != columns:
            raise ValueError("matrix must be rectangular")
        for value in row:
            maximum = _source_max(maximum, value)
    size = max(rows, columns)
    padded, max_abs = [], 0.0
    for index in range(size):
        row = [maximum + 1.0] * size
        if index < rows:
            row[:columns] = matrix[index]
        for value in row:
            if math.isfinite(value):
                max_abs = max(max_abs, abs(value))
        padded.append(row)
    sentinel = (size + 1.0) * max_abs + 1.0
    if not math.isfinite(sentinel):
        sentinel = sys.float_info.max / (size + 4.0)
    first = _solve_subproblem(padded, (), (), 0, sentinel)
    heap = _CandidateHeap()
    if first is not None:
        heap.add(first)
    results = []
    while heap.entries:
        assignment, original_fixed, forbidden, fixed_count = heap.remove()
        results.append(assignment if rows == columns else _trim(matrix, assignment))
        if len(results) >= k:
            break
        active = [(row, assignment.row_assignment[row]) for row in range(size)
                  if assignment.row_assignment[row] != -1]
        fixed = list(original_fixed)
        for index in range(fixed_count, len(active)):
            edge = active[index]
            next_forbidden = list(forbidden) + [edge]
            candidate = _solve_subproblem(padded, fixed, next_forbidden, len(fixed), sentinel)
            if candidate is not None:
                heap.add(candidate)
            fixed.append(edge)
    return tuple(results)
