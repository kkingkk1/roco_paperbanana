"""通过二部流网络的逐次最短路径求解一对一匹配。"""

import heapq
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
    padded, minimum = [], float("inf")
    for row in range(size):
        values = [maximum + 1.0] * size
        if row < rows:
            values[:columns] = matrix[row]
        for value in values:
            minimum = _source_min(minimum, value)
        padded.append(values)
    shift = -minimum if minimum < 0 else 0.0
    costs = [[value + shift for value in row] for row in padded]
    nodes, source, sink = 2 * size + 2, 0, 2 * size + 1
    row_match, column_match = [-1] * size, [-1] * size
    potential = [0.0] * nodes
    for _ in range(size):
        distance, parent = [float("inf")] * nodes, [-1] * nodes
        distance[source] = 0.0
        queue = [(0.0, source)]

        def relax(next_distance, node, following):
            if next_distance + 1e-12 < distance[following]:
                distance[following] = next_distance
                parent[following] = node
                heapq.heappush(queue, (next_distance, following))

        while queue:
            current, node = heapq.heappop(queue)
            if current > distance[node] + 1e-12:
                continue
            if node == sink:
                break
            if node == source:
                for row in range(size):
                    if row_match[row] == -1:
                        following = 1 + row
                        relax(current + (0.0 + potential[source] - potential[following]), node, following)
            elif node <= size:
                row = node - 1
                for column in range(size):
                    if row_match[row] != column:
                        following = size + 1 + column
                        relax(current + (costs[row][column] + potential[node] - potential[following]),
                              node, following)
            else:
                column = node - (size + 1)
                row = column_match[column]
                if row != -1:
                    following = 1 + row
                    relax(current + (-costs[row][column] + potential[node] - potential[following]),
                          node, following)
                else:
                    relax(current + (0.0 + potential[node] - potential[sink]), node, sink)
        sink_distance = distance[sink]
        if not math.isfinite(sink_distance):
            break
        for node in range(nodes):
            potential[node] += distance[node] if distance[node] < sink_distance else sink_distance
        node = sink
        while node != source:
            prior = parent[node]
            if 1 <= prior <= size and size + 1 <= node <= 2 * size:
                row, column = prior - 1, node - (size + 1)
                row_match[row], column_match[column] = column, row
            node = prior
    result_rows, result_columns = [-1] * rows, [-1] * columns
    for row in range(rows):
        if row_match[row] < columns:
            result_rows[row] = row_match[row]
    for column in range(columns):
        if column_match[column] < rows:
            result_columns[column] = column_match[column]
    total = -0.0
    for row, column in enumerate(result_rows):
        if column != -1:
            total += matrix[row][column]
    return Assignment(total, tuple(result_rows), tuple(result_columns))
