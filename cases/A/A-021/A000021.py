"""通过归约、零元素标记和交替路径求解匈牙利匹配。"""

import math
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
    return min(left, right)


def _source_max(left, right):
    if math.isnan(left):
        return right
    if math.isnan(right):
        return left
    if left == right == 0:
        return 0.0 if math.copysign(1, left) > 0 or math.copysign(1, right) > 0 else -0.0
    return max(left, right)


def solve(matrix):
    rows = len(matrix)
    if not rows:
        return Assignment(0.0, (), ())
    columns = len(matrix[0])
    if not columns:
        raise ValueError("matrix must have a column")
    maximum = float("-inf")
    for row in matrix:
        if len(row) != columns:
            raise ValueError("matrix must be rectangular")
        for value in row:
            maximum = _source_max(maximum, value)
    size = max(rows, columns)
    cost, scale = [], 1.0
    for i in range(size):
        values = [maximum + 1.0] * size
        if i < rows:
            values[:columns] = matrix[i]
        for value in values:
            scale = _source_max(scale, abs(value))
        cost.append(values)
    epsilon = scale * 1e-9
    for row in cost:
        minimum = float("inf")
        for value in row:
            minimum = _source_min(minimum, value)
        for column in range(size):
            row[column] -= minimum
    for column in range(size):
        minimum = float("inf")
        for row in range(size):
            minimum = _source_min(minimum, cost[row][column])
        for row in range(size):
            cost[row][column] -= minimum
    marks = [[0] * size for _ in range(size)]
    row_covered, column_covered = [False] * size, [False] * size
    for row in range(size):
        for column in range(size):
            if abs(cost[row][column]) < epsilon and not row_covered[row] and not column_covered[column]:
                marks[row][column] = 1
                row_covered[row] = column_covered[column] = True
    row_covered, column_covered = [False] * size, [False] * size
    path_row = path_column = 0
    step = 3
    while step != 7:
        if step == 3:
            for column in range(size):
                if any(marks[row][column] == 1 for row in range(size)):
                    column_covered[column] = True
            step = 7 if sum(column_covered) >= size else 4
        elif step == 4:
            found = None
            for row in range(size):
                if row_covered[row]:
                    continue
                for column in range(size):
                    if not column_covered[column] and abs(cost[row][column]) < epsilon:
                        found = row, column
                        break
                if found is not None:
                    break
            if found is None:
                step = 6
            else:
                row, column = found
                marks[row][column] = 2
                star = next((j for j in range(size) if marks[row][j] == 1), None)
                if star is not None:
                    row_covered[row] = True
                    column_covered[star] = False
                else:
                    path_row, path_column, step = row, column, 5
        elif step == 5:
            path = [(path_row, path_column)]
            while True:
                column = path[-1][1]
                star_row = next((i for i in range(size) if marks[i][column] == 1), None)
                if star_row is None:
                    break
                path.append((star_row, column))
                prime = next((j for j in range(size) if marks[star_row][j] == 2), None)
                if prime is None:
                    raise RuntimeError("missing primed zero")
                path.append((star_row, prime))
            for row, column in path:
                marks[row][column] = 0 if marks[row][column] == 1 else 1
            row_covered, column_covered = [False] * size, [False] * size
            for row in marks:
                for column in range(size):
                    if row[column] == 2:
                        row[column] = 0
            step = 3
        elif step == 6:
            minimum = float("inf")
            for row in range(size):
                if row_covered[row]:
                    continue
                for column in range(size):
                    if not column_covered[column] and cost[row][column] < minimum:
                        minimum = cost[row][column]
            if not math.isfinite(minimum):
                raise ValueError("matching cannot expose a finite uncovered cost")
            for row in range(size):
                for column in range(size):
                    if row_covered[row]:
                        cost[row][column] += minimum
                    if not column_covered[column]:
                        cost[row][column] -= minimum
            step = 4
        else:
            raise RuntimeError("invalid matching step")
    row_assignment, column_assignment = [-1] * rows, [-1] * columns
    for row in range(rows):
        for column in range(columns):
            if marks[row][column] == 1:
                row_assignment[row] = column
                column_assignment[column] = row
    total = -0.0
    for row, column in enumerate(row_assignment):
        if column != -1:
            total += matrix[row][column]
    return Assignment(total, tuple(row_assignment), tuple(column_assignment))
