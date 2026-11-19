package method.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Bottleneck matching by threshold search and Hopcroft-Karp matching. */
public final class A000025 {
  private A000025() { }

  public record Assignment(double totalCost, int[] rowAssignment, int[] columnAssignment) { }
  private record Matching(int size, int[] rows, int[] columns) { }

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
    List<Double> costs = new ArrayList<>();
    for (int i = 0; i < size; i++) {
      Arrays.fill(padded[i], maximum + 1.0);
      if (i < rows) System.arraycopy(matrix[i], 0, padded[i], 0, columns);
      for (double value : padded[i]) if (Double.isFinite(value)) costs.add(value);
    }
    costs.sort((a, b) -> a < b ? -1 : a > b ? 1 : 0);
    List<Double> thresholds = new ArrayList<>();
    for (double value : costs) {
      if (thresholds.isEmpty() || Math.abs(value - thresholds.get(thresholds.size() - 1)) >= 1e-12) {
        thresholds.add(value);
      }
    }
    if (thresholds.isEmpty()) return new Assignment(0.0, empty(rows), empty(columns));

    int low = 0, high = thresholds.size() - 1, bestIndex = high;
    Matching best = null;
    while (low <= high) {
      int middle = (low + high) / 2;
      Matching candidate = hopcroftKarp(padded, thresholds.get(middle));
      if (candidate.size() == size) {
        bestIndex = middle;
        best = candidate;
        if (middle == 0) break;
        high = middle - 1;
      } else {
        low = middle + 1;
      }
    }
    if (best == null) best = hopcroftKarp(padded, thresholds.get(bestIndex));
    int[] rowAssignment = empty(rows), columnAssignment = empty(columns);
    double bottleneck = Double.NEGATIVE_INFINITY;
    for (int i = 0; i < rows; i++) {
      int column = best.rows()[i];
      if (column >= 0 && column < columns) {
        rowAssignment[i] = column;
        bottleneck = sourceMax(bottleneck, matrix[i][column]);
      }
    }
    for (int j = 0; j < columns; j++) {
      int row = best.columns()[j];
      if (row >= 0 && row < rows) columnAssignment[j] = row;
    }
    return new Assignment(Double.isFinite(bottleneck) ? bottleneck : 0.0, rowAssignment, columnAssignment);
  }

  private static Matching hopcroftKarp(double[][] cost, double threshold) {
    int n = cost.length;
    int[] pairU = new int[n + 1], pairV = new int[n + 1], distance = new int[n + 1];
    int matchingSize = 0;
    while (bfs(cost, threshold, pairU, pairV, distance)) {
      for (int u = 1; u <= n; u++) {
        if (pairU[u] == 0 && dfs(cost, threshold, u, pairU, pairV, distance)) matchingSize++;
      }
    }
    int[] rows = empty(n), columns = empty(n);
    for (int u = 1; u <= n; u++) if (pairU[u] != 0) rows[u - 1] = pairU[u] - 1;
    for (int v = 1; v <= n; v++) if (pairV[v] != 0) columns[v - 1] = pairV[v] - 1;
    return new Matching(matchingSize, rows, columns);
  }

  private static boolean bfs(double[][] cost, double threshold, int[] pairU, int[] pairV, int[] distance) {
    int n = cost.length;
    ArrayDeque<Integer> queue = new ArrayDeque<>();
    for (int u = 1; u <= n; u++) {
      if (pairU[u] == 0) {
        distance[u] = 0;
        queue.addLast(u);
      } else distance[u] = Integer.MAX_VALUE;
    }
    distance[0] = Integer.MAX_VALUE;
    while (!queue.isEmpty()) {
      int u = queue.removeFirst();
      if (distance[u] < distance[0]) {
        for (int v = 1; v <= n; v++) {
          if (cost[u - 1][v - 1] <= threshold + 1e-12) {
            int next = pairV[v];
            if (distance[next] == Integer.MAX_VALUE) {
              distance[next] = distance[u] + 1;
              queue.addLast(next);
            }
          }
        }
      }
    }
    return distance[0] != Integer.MAX_VALUE;
  }

  private static boolean dfs(double[][] cost, double threshold, int u, int[] pairU, int[] pairV, int[] distance) {
    if (u == 0) return true;
    for (int v = 1; v <= cost.length; v++) {
      if (cost[u - 1][v - 1] <= threshold + 1e-12) {
        int next = pairV[v];
        if (distance[next] == distance[u] + 1 && dfs(cost, threshold, next, pairU, pairV, distance)) {
          pairV[v] = u;
          pairU[u] = v;
          return true;
        }
      }
    }
    return false;
  }

  private static int[] empty(int size) {
    int[] result = new int[size];
    Arrays.fill(result, -1);
    return result;
  }

  private static double sourceMax(double left, double right) {
    return Double.isNaN(left) ? right : Double.isNaN(right) || left >= right ? left : right;
  }
}
