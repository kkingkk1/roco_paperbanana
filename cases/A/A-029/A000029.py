"""非对称贪心初始化，随后比较行交换与未用列替换。"""

import math
import struct
from dataclasses import dataclass


@dataclass(frozen=True)
class Assignment:
    rows: tuple[int, ...]
    columns: tuple[int, ...]


def _float32(value):
    return struct.unpack(">f", struct.pack(">f", value))[0]


def _update_row(benefit, assignment, row_index, row_delta, row):
    best, best_delta = -1, float("-inf")
    for other in range(len(assignment)):
        delta = (-1.0 if row == other else
                 (benefit[row][assignment[other]] + benefit[other][assignment[row]]) -
                 (benefit[row][assignment[row]] + benefit[other][assignment[other]]))
        if best == -1 or delta > best_delta:
            best, best_delta = other, delta
    row_index[row], row_delta[row] = best, _float32(best_delta)


def _moves(benefit, assignment, available):
    rows = len(assignment)
    row_index, row_delta, column_index, column_delta = [0] * rows, [0.0] * rows, [0] * rows, [0.0] * rows
    for row in range(rows):
        _update_row(benefit, assignment, row_index, row_delta, row)
        best = -1
        for column, free in enumerate(available):
            if free and (best == -1 or benefit[row][column] > benefit[row][best]):
                best = column
        column_index[row] = best
        column_delta[row] = (float("-inf") if best == -1 else
                             _float32(benefit[row][best] - benefit[row][assignment[row]]))
    return row_index, row_delta, column_index, column_delta


def optimize(matrix, minimize, row_order, maximum_iterations, stagnation_limit,
             stagnation_tolerance, row_batch_size, *, rng=None):
    rows = len(matrix)
    if rows == 0:
        raise ValueError("matrix must not be empty")
    columns = len(matrix[0])
    if columns < rows:
        raise ValueError("rows must not exceed columns")
    if row_batch_size <= 0:
        raise ValueError("row batch size must be positive")
    if isinstance(row_order, bool):
        shuffle = row_order
        row_order = list(range(rows))
        if shuffle:
            if rng is None:
                raise ValueError("random generator is required when shuffling")
            for index in range(rows - 1, 0, -1):
                other = rng.randrange(index + 1)
                row_order[index], row_order[other] = row_order[other], row_order[index]
    if row_order is None or len(row_order) != rows or set(row_order) != set(range(rows)):
        raise ValueError("invalid row order")
    minimum, maximum = float("inf"), float("-inf")
    for row in matrix:
        if len(row) != columns:
            raise ValueError("matrix must be rectangular")
        for value in row:
            if not math.isfinite(value):
                raise ValueError("matrix values must be finite")
            minimum, maximum = min(minimum, value), max(maximum, value)
    value_range = maximum - minimum
    if not math.isfinite(value_range):
        raise ValueError("matrix range must be finite")
    benefit = []
    for row in matrix:
        values = []
        for value in row:
            scaled = (value - minimum) / value_range if value_range > 0 else 0.0
            values.append(1.0 - scaled if minimize else scaled)
        benefit.append(values)
    assignment, available = [0] * rows, [True] * columns
    for row in row_order:
        best = -1
        for column, free in enumerate(available):
            if free and (best == -1 or benefit[row][column] > benefit[row][best]):
                best = column
        assignment[row] = best
        available[best] = False
    row_index, row_delta, column_index, column_delta = _moves(benefit, assignment, available)
    row_max, column_max = max(row_delta), max(column_delta)
    current_max = max(row_max, column_max)
    tolerance = current_max * stagnation_tolerance
    iterations = stale = 0
    while current_max > 0:
        if row_max > column_max:
            candidates = sorted((row for row in range(rows) if row_delta[row] > 0),
                                key=lambda row: (-row_delta[row], row))
            locked, batch = [False] * rows, []
            for row in candidates:
                if len(batch) >= row_batch_size:
                    break
                other = row_index[row]
                if locked[row] or locked[other]:
                    continue
                batch.append(row)
                locked[row] = locked[other] = True
            for row in batch:
                other = row_index[row]
                assignment[row], assignment[other] = assignment[other], assignment[row]
                affected = [index for index in range(rows) if row_index[index] in (row, other)]
                for index in affected:
                    _update_row(benefit, assignment, row_index, row_delta, index)
        else:
            row = max(range(rows), key=lambda index: column_delta[index])
            old, following = assignment[row], column_index[row]
            assignment[row] = following
            available[old], available[following] = True, False
        row_index, row_delta, column_index, column_delta = _moves(benefit, assignment, available)
        row_max, column_max = max(row_delta), max(column_delta)
        current_max = max(row_max, column_max)
        iterations += 1
        if maximum_iterations > 0 and iterations >= maximum_iterations:
            break
        stale = stale + 1 if stagnation_limit > 0 and current_max <= tolerance else 0
        if stagnation_limit > 0 and stale >= stagnation_limit:
            break
    return Assignment(tuple(range(rows)), tuple(assignment))
