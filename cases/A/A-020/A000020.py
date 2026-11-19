"""按边成本排序的贪心一对一匹配。"""

import math
from dataclasses import dataclass


@dataclass(frozen=True)
class Assignment:
    total_cost: float
    row_assignment: tuple[int, ...]
    column_assignment: tuple[int, ...]


def solve(matrix):
    rows = len(matrix)
    if rows == 0:
        return Assignment(0.0, (), ())
    columns = len(matrix[0])
    edges = []
    for row, values in enumerate(matrix):
        if len(values) != columns:
            raise ValueError("matrix must be rectangular")
        for column, cost in enumerate(values):
            if math.isfinite(cost):
                edges.append((row, column, cost))
    edges.sort(key=lambda edge: edge[2])
    row_assignment, column_assignment = [-1] * rows, [-1] * columns
    for row, column, _ in edges:
        if row_assignment[row] == -1 and column_assignment[column] == -1:
            row_assignment[row] = column
            column_assignment[column] = row
    total = -0.0
    for row, column in enumerate(row_assignment):
        if column != -1:
            total += matrix[row][column]
    return Assignment(total, tuple(row_assignment), tuple(column_assignment))
