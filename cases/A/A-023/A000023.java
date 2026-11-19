package method.runtime;

import java.util.Arrays;
import java.util.Objects;
import java.util.PriorityQueue;

/** Minimum-cost matching by successive shortest paths on a bipartite flow network. */
public final class A000023 {
  private A000023() { }

  public record Assignment(double totalCost, int[] rowAssignment, int[] columnAssignment) { }
  private record QueueEntry(double distance, int node) { }

  public static Assignment solve(double[][] matrix) {
    Objects.requireNonNull(matrix, "matrix");
    int rows = matrix.length;
    if (rows == 0) return new Assignment(0.0, new int[0], new int[0]);
    int columns = Objects.requireNonNull(matrix[0], "matrix row").length;
    double maximum = Double.NEGATIVE_INFINITY;
    for (double[] row : matrix) {
      if (Objects.requireNonNull(row, "matrix row").length != columns) {
        throw new IllegalArgumentException("matrix must be rectangular");
      }
      for (double value : row) maximum = sourceMax(maximum, value);
    }
    int size = Math.max(rows, columns);
    double[][] padded = new double[size][size];
    double minimum = Double.POSITIVE_INFINITY;
    for (int i = 0; i < size; i++) {
      Arrays.fill(padded[i], maximum + 1.0);
      if (i < rows) System.arraycopy(matrix[i], 0, padded[i], 0, columns);
      for (double value : padded[i]) minimum = sourceMin(minimum, value);
    }
    double shift = minimum < 0.0 ? -minimum : 0.0;
    double[][] costs = new double[size][size];
    for (int i = 0; i < size; i++) {
      for (int j = 0; j < size; j++) costs[i][j] = padded[i][j] + shift;
    }

    int nodes = 2 * size + 2, source = 0, sink = nodes - 1;
    int[] rowMatch = empty(size), columnMatch = empty(size);
    double[] potential = new double[nodes];
    for (int flow = 0; flow < size; flow++) {
      double[] distance = new double[nodes];
      Arrays.fill(distance, Double.POSITIVE_INFINITY);
      int[] parent = empty(nodes);
      PriorityQueue<QueueEntry> queue = new PriorityQueue<>((left, right) -> {
        int byDistance = left.distance() < right.distance() ? -1 : left.distance() > right.distance() ? 1 : 0;
        return byDistance != 0 ? byDistance : Integer.compare(left.node(), right.node());
      });
      distance[source] = 0.0;
      queue.add(new QueueEntry(0.0, source));
      while (!queue.isEmpty()) {
        QueueEntry entry = queue.remove();
        double current = entry.distance();
        int node = entry.node();
        if (current > distance[node] + 1e-12) continue;
        if (node == sink) break;
        if (node == source) {
          for (int i = 0; i < size; i++) {
            if (rowMatch[i] == -1) {
              int next = 1 + i;
              relax(current + (0.0 + potential[source] - potential[next]), node, next, distance, parent, queue);
            }
          }
        } else if (node <= size) {
          int row = node - 1;
          for (int column = 0; column < size; column++) {
            if (rowMatch[row] != column) {
              int next = size + 1 + column;
              relax(current + (costs[row][column] + potential[node] - potential[next]),
                  node, next, distance, parent, queue);
            }
          }
        } else {
          int column = node - (size + 1);
          int row = columnMatch[column];
          if (row != -1) {
            int next = 1 + row;
            relax(current + (-costs[row][column] + potential[node] - potential[next]),
                node, next, distance, parent, queue);
          } else {
            relax(current + (0.0 + potential[node] - potential[sink]), node, sink, distance, parent, queue);
          }
        }
      }
      double sinkDistance = distance[sink];
      if (!Double.isFinite(sinkDistance)) break;
      for (int node = 0; node < nodes; node++) {
        potential[node] += distance[node] < sinkDistance ? distance[node] : sinkDistance;
      }
      int node = sink;
      while (node != source) {
        int prior = parent[node];
        if (prior >= 1 && prior <= size && node >= size + 1 && node <= 2 * size) {
          int row = prior - 1, column = node - (size + 1);
          rowMatch[row] = column;
          columnMatch[column] = row;
        }
        node = prior;
      }
    }
    int[] resultRows = empty(rows), resultColumns = empty(columns);
    for (int i = 0; i < rows; i++) if (rowMatch[i] < columns) resultRows[i] = rowMatch[i];
    for (int j = 0; j < columns; j++) if (columnMatch[j] < rows) resultColumns[j] = columnMatch[j];
    double total = -0.0;
    for (int i = 0; i < rows; i++) if (resultRows[i] != -1) total += matrix[i][resultRows[i]];
    return new Assignment(total, resultRows, resultColumns);
  }

  private static void relax(double nextDistance, int node, int next, double[] distance, int[] parent,
                            PriorityQueue<QueueEntry> queue) {
    if (nextDistance + 1e-12 < distance[next]) {
      distance[next] = nextDistance;
      parent[next] = node;
      queue.add(new QueueEntry(nextDistance, next));
    }
  }

  private static int[] empty(int size) {
    int[] result = new int[size];
    Arrays.fill(result, -1);
    return result;
  }

  private static double sourceMin(double left, double right) {
    return Double.isNaN(left) ? right : Double.isNaN(right) ? left : Math.min(left, right);
  }

  private static double sourceMax(double left, double right) {
    return Double.isNaN(left) ? right : Double.isNaN(right) ? left : Math.max(left, right);
  }
}
