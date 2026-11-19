"""运输单纯形匹配，并可用最短增广路径求解对应对偶变量。"""

import math
from collections import deque
from dataclasses import dataclass


@dataclass(frozen=True)
class Assignment:
    total_cost: float
    row_assignment: tuple[int, ...]
    column_assignment: tuple[int, ...]


@dataclass(frozen=True)
class AssignmentWithDuals:
    assignment: Assignment
    row_dual: tuple[float, ...]
    column_dual: tuple[float, ...]


def _source_min(left, right):
    return right if math.isnan(left) else left if math.isnan(right) or left <= right else right


def _source_max(left, right):
    return right if math.isnan(left) else left if math.isnan(right) or left >= right else right


def _padded(matrix):
    if not matrix:
        return []
    rows, columns = len(matrix), len(matrix[0])
    maximum = float("-inf")
    for row in matrix:
        if len(row) != columns:
            raise ValueError("matrix must be rectangular")
        for value in row:
            maximum = _source_max(maximum, value)
    size = max(rows, columns)
    result = []
    for index in range(size):
        row = [maximum + 1.0] * size
        if index < rows:
            row[:columns] = matrix[index]
        result.append(row)
    return result


def _find(parent, item):
    while parent[item] != item:
        parent[item] = parent[parent[item]]
        item = parent[item]
    return item


def _union(parent, first, second):
    x, y = _find(parent, first), _find(parent, second)
    if x == y:
        return False
    parent[x] = y
    return True


def _simplex(cost):
    size = len(cost)
    flow = [[0.0] * size for _ in range(size)]
    basic = [[False] * size for _ in range(size)]
    tree = [[] for _ in range(2 * size)]
    parent, taken = list(range(2 * size)), [False] * size
    for row in range(size):
        best_column, best = 0, float("inf")
        for column in range(size):
            if not taken[column] and cost[row][column] < best:
                best, best_column = cost[row][column], column
        taken[best_column] = True
        flow[row][best_column] = 1.0
        basic[row][best_column] = True
        tree[row].append(size + best_column)
        tree[size + best_column].append(row)
        _union(parent, row, size + best_column)
    needed = size - 1
    for row in range(size):
        for column in range(size):
            if needed <= 0:
                break
            if not basic[row][column] and _union(parent, row, size + column):
                basic[row][column] = True
                tree[row].append(size + column)
                tree[size + column].append(row)
                needed -= 1
        if needed <= 0:
            break
    u, v, degenerate = [0.0] * size, [0.0] * size, 0
    for _ in range(50 * size * size + 1000):
        u, v = [float("nan")] * size, [float("nan")] * size
        u[0] = 0.0
        visited = [False] * (2 * size)
        visited[0] = True
        queue = deque((0,))
        while queue:
            node = queue.popleft()
            for following in tree[node]:
                if not visited[following]:
                    visited[following] = True
                    if node < size:
                        v[following - size] = cost[node][following - size] - u[node]
                    else:
                        u[following] = cost[following][node - size] - v[node - size]
                    queue.append(following)
        bland = degenerate >= 4 * size + 16
        enter_row = enter_column = -1
        best = -1e-9
        for row in range(size):
            for column in range(size):
                if basic[row][column]:
                    continue
                reduced = cost[row][column] - u[row] - v[column]
                if bland and reduced < -1e-9:
                    enter_row, enter_column = row, column
                    break
                if not bland and reduced < best:
                    best, enter_row, enter_column = reduced, row, column
            if bland and enter_row >= 0:
                break
        if enter_row < 0:
            break
        previous = [-1] * (2 * size)
        previous[enter_row] = enter_row
        queue.append(enter_row)
        while queue:
            node = queue.popleft()
            if node == size + enter_column:
                break
            for following in tree[node]:
                if previous[following] < 0:
                    previous[following] = node
                    queue.append(following)
        target = size + enter_column
        if previous[target] < 0:
            raise ValueError("simplex basis is disconnected")
        cycle = [(enter_row, enter_column, True)]
        positive, node = False, target
        while node != enter_row:
            before = previous[node]
            row = before if before < size else node
            column = node - size if before < size else before - size
            cycle.append((row, column, positive))
            positive = not positive
            node = before
        theta, leave_row, leave_column = float("inf"), -1, -1
        for row, column, positive in cycle:
            if positive:
                continue
            value = flow[row][column]
            if (value < theta - 1e-9 or (value < theta + 1e-9 and
                    (leave_row < 0 or row < leave_row or (row == leave_row and column < leave_column)))):
                theta, leave_row, leave_column = value, row, column
        if leave_row < 0:
            break
        degenerate = degenerate + 1 if theta <= 1e-9 else 0
        for row, column, positive in cycle:
            flow[row][column] += theta if positive else -theta
        basic[enter_row][enter_column] = True
        tree[enter_row].append(size + enter_column)
        tree[size + enter_column].append(enter_row)
        basic[leave_row][leave_column] = False
        tree[leave_row].remove(size + leave_column)
        tree[size + leave_column].remove(leave_row)
    assignment = [-1] * size
    for row in range(size):
        for column in range(size):
            if basic[row][column] and flow[row][column] > 0.5:
                assignment[row] = column
                break
    return assignment, u, v


def _trim(matrix, padded_rows):
    rows, columns = len(matrix), len(matrix[0])
    row_assignment, column_assignment = [-1] * rows, [-1] * columns
    padded_columns = [-1] * len(padded_rows)
    for row, column in enumerate(padded_rows):
        if column >= 0:
            padded_columns[column] = row
    total = -0.0
    for row in range(rows):
        column = padded_rows[row]
        if 0 <= column < columns:
            row_assignment[row] = column
            total += matrix[row][column]
    for column in range(columns):
        if padded_columns[column] < rows:
            column_assignment[column] = padded_columns[column]
    return Assignment(total, tuple(row_assignment), tuple(column_assignment))


def _sap_duals(cost, row_dual, column_dual):
    size = len(cost)
    u, v = [0.0] + list(row_dual), [0.0] + list(column_dual)
    matched, way = [0] * (size + 1), [0] * (size + 1)
    for row in range(1, size + 1):
        matched[0] = row
        previous_column = 0
        best, used = [float("inf")] * (size + 1), [False] * (size + 1)
        while True:
            used[previous_column] = True
            current_row, following, delta = matched[previous_column], 0, float("inf")
            for column in range(1, size + 1):
                if used[column]:
                    continue
                current = cost[current_row - 1][column - 1] - u[current_row] - v[column]
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
    assignment = [-1] * size
    for column in range(1, size + 1):
        if matched[column] != 0:
            assignment[matched[column] - 1] = column - 1
    return AssignmentWithDuals(_trim(cost, assignment), tuple(u[1:]), tuple(v[1:]))


def solve(matrix):
    padded = _padded(matrix)
    if not matrix:
        return Assignment(0.0, (), ())
    assignment, _, _ = _simplex(padded)
    return _trim(matrix, assignment)


def solve_duals(matrix):
    padded = _padded(matrix)
    if not matrix:
        return AssignmentWithDuals(Assignment(0.0, (), ()), (), ())
    size, columns = len(padded), len(matrix[0])
    _, row_dual, column_dual = _simplex(padded)
    for row in range(size):
        row_dual[row] = float("inf")
        for column in range(size):
            row_dual[row] = _source_min(row_dual[row], padded[row][column] - column_dual[column])
    result = _sap_duals(padded, row_dual, column_dual)
    return AssignmentWithDuals(_trim(matrix, result.assignment.row_assignment),
                               result.row_dual[:len(matrix)], result.column_dual[:columns])
