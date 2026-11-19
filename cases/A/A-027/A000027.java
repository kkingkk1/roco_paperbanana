package method.runtime;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Objects;

/** Cost-scaling push-relabel matching followed by shortest-path completion. */
public final class A000027 {
  private A000027() { }

  public record Assignment(double totalCost, int[] rowAssignment, int[] columnAssignment) { }

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
    int n = Math.max(rows, columns);
    double[][] padded = new double[n][n];
    double maxCost = 0.0;
    for (int i = 0; i < n; i++) {
      Arrays.fill(padded[i], maximum + 1.0);
      if (i < rows) System.arraycopy(matrix[i], 0, padded[i], 0, columns);
      for (double value : padded[i]) maxCost = sourceMax(maxCost, Math.abs(value));
    }
    double[] u = new double[n], v = new double[n];
    for (int i = 0; i < n; i++) {
      u[i] = Double.POSITIVE_INFINITY;
      for (double value : padded[i]) u[i] = sourceMin(u[i], value);
    }
    int[] rowMatch = empty(n), columnMatch = empty(n);
    double epsilon = sourceMax(maxCost * 0.5, 1.0);
    double target = Math.min(1.0 / (n + 1.0), 1e-4);
    int phase = 0;
    boolean capped = false;
    while (epsilon >= target && phase < 30) {
      phase++;
      for (int r = 0; r < n; r++) if (rowMatch[r] != -1) {
        int c = rowMatch[r];
        if (padded[r][c] - u[r] - v[c] > epsilon) { rowMatch[r] = -1; columnMatch[c] = -1; }
      }
      ArrayDeque<Integer> active = new ArrayDeque<>();
      for (int r = 0; r < n; r++) if (rowMatch[r] == -1) active.addLast(r);
      int step = 0, limit = 50 * n * n + 2000;
      while (!active.isEmpty()) {
        int r = active.removeFirst();
        if (++step > limit) { capped = true; break; }
        int bestColumn = -1;
        double best = Double.POSITIVE_INFINITY, second = Double.POSITIVE_INFINITY;
        for (int c = 0; c < n; c++) {
          double reduced = padded[r][c] - u[r] - v[c];
          if (reduced < best) { second = best; best = reduced; bestColumn = c; }
          else if (reduced < second) second = reduced;
        }
        if (bestColumn != -1) {
          if (best <= 0.0) {
            int previous = columnMatch[bestColumn];
            if (previous != -1) { rowMatch[previous] = -1; active.addLast(previous); }
            rowMatch[r] = bestColumn;
            columnMatch[bestColumn] = r;
          } else {
            u[r] += Double.isFinite(second) && second > best ? second + epsilon : best + epsilon;
            active.addLast(r);
          }
        }
      }
      epsilon /= 4.0;
    }
    if ((phase >= 30 && epsilon >= target) || capped) {
      Arrays.fill(u, 0.0);
      Arrays.fill(v, 0.0);
      for (int round = 0; round < 8; round++) {
        for (int i = 0; i < n; i++) {
          u[i] = Double.POSITIVE_INFINITY;
          for (int j = 0; j < n; j++) u[i] = sourceMin(u[i], padded[i][j] - v[j]);
        }
        for (int j = 0; j < n; j++) {
          v[j] = Double.POSITIVE_INFINITY;
          for (int i = 0; i < n; i++) v[j] = sourceMin(v[j], padded[i][j] - u[i]);
        }
      }
      rowMatch = empty(n);
    }
    Assignment solution = sap(padded, u, v, rowMatch);
    if (rows == columns) return solution;
    int[] outRows = empty(rows), outColumns = empty(columns);
    for (int i = 0; i < rows; i++) if (solution.rowAssignment()[i] < columns) outRows[i] = solution.rowAssignment()[i];
    for (int j = 0; j < columns; j++) if (solution.columnAssignment()[j] < rows) outColumns[j] = solution.columnAssignment()[j];
    return result(matrix, outRows, outColumns);
  }

  private static Assignment sap(double[][] cost, double[] rowDual, double[] columnDual, int[] initial) {
    int n = cost.length;
    double[] u = new double[n + 1], v = new double[n + 1];
    System.arraycopy(rowDual, 0, u, 1, n);
    System.arraycopy(columnDual, 0, v, 1, n);
    int[] matched = new int[n + 1], way = new int[n + 1];
    for (int i = 0; i < n; i++) if (initial[i] != -1) matched[initial[i] + 1] = i + 1;
    for (int free = 0; free < n; free++) if (initial[free] == -1) {
      matched[0] = free + 1;
      int j0 = 0;
      double[] best = new double[n + 1];
      Arrays.fill(best, Double.POSITIVE_INFINITY);
      boolean[] used = new boolean[n + 1];
      do {
        used[j0] = true;
        int row = matched[j0], j1 = 0;
        double delta = Double.POSITIVE_INFINITY;
        for (int j = 1; j <= n; j++) if (!used[j]) {
          double current = cost[row - 1][j - 1] - u[row] - v[j];
          if (current < best[j]) { best[j] = current; way[j] = j0; }
          if (best[j] < delta) { delta = best[j]; j1 = j; }
        }
        if (j1 == 0) throw new IllegalArgumentException("matching cannot find an augmenting path");
        for (int j = 0; j <= n; j++) {
          if (used[j]) { u[matched[j]] += delta; v[j] -= delta; }
          else best[j] -= delta;
        }
        j0 = j1;
      } while (matched[j0] != 0);
      do { int prior = way[j0]; matched[j0] = matched[prior]; j0 = prior; } while (j0 != 0);
    }
    int[] rows = empty(n), columns = empty(n);
    for (int j = 1; j <= n; j++) if (matched[j] != 0) {
      rows[matched[j] - 1] = j - 1;
      columns[j - 1] = matched[j] - 1;
    }
    return result(cost, rows, columns);
  }

  private static Assignment result(double[][] matrix, int[] rows, int[] columns) {
    double total = -0.0;
    for (int i = 0; i < rows.length; i++) if (rows[i] != -1) total += matrix[i][rows[i]];
    return new Assignment(total, rows, columns);
  }

  private static int[] empty(int size) { int[] result = new int[size]; Arrays.fill(result, -1); return result; }
  private static double sourceMax(double a, double b) { return Double.isNaN(a) ? b : Double.isNaN(b) || a >= b ? a : b; }
  private static double sourceMin(double a, double b) { return Double.isNaN(a) ? b : Double.isNaN(b) || a <= b ? a : b; }
}
