package method.runtime;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Objects;

/** Epsilon-scaling auction with the source's shortest-path and LAPJV fallbacks. */
public final class A000022 {
  private static final int MAX_PHASE_BIDS = 200_000;
  private static final int MAX_PHASES = 60;

  private A000022() { }

  public record Assignment(double totalCost, int[] rowAssignment, int[] columnAssignment) { }

  public static Assignment solve(double[][] matrix) {
    Objects.requireNonNull(matrix, "matrix");
    int rows = matrix.length;
    if (rows == 0) return new Assignment(0.0, new int[0], new int[0]);
    int columns = Objects.requireNonNull(matrix[0], "matrix row").length;
    if (columns == 0) throw new IllegalArgumentException("matrix must have a column");
    double maximum = Double.NEGATIVE_INFINITY, minimum = Double.POSITIVE_INFINITY;
    for (double[] row : matrix) {
      if (Objects.requireNonNull(row, "matrix row").length != columns) {
        throw new IllegalArgumentException("matrix must be rectangular");
      }
      for (double value : row) {
        maximum = sourceMax(maximum, value);
        minimum = sourceMin(minimum, value);
      }
    }
    if (rows != columns) {
      int size = Math.max(rows, columns);
      double[][] padded = new double[size][size];
      for (int i = 0; i < size; i++) {
        Arrays.fill(padded[i], maximum + 1.0);
        if (i < rows) System.arraycopy(matrix[i], 0, padded[i], 0, columns);
      }
      return trim(matrix, sap(padded, new double[size], new double[size], empty(size)));
    }
    double costScale = sourceMax(sourceMax(Math.abs(maximum), Math.abs(minimum)), 1.0);
    if (costScale > 1e150) return sap(matrix, new double[rows], new double[rows], empty(rows));

    double[] prices = new double[rows];
    int[] rowAssignment = empty(rows), columnAssignment = empty(rows);
    double epsilon = Math.abs(maximum - minimum) * 0.5;
    if (!Double.isFinite(epsilon) || epsilon == 0.0) epsilon = costScale;
    double target = costScale * 1e-8;
    for (int phase = 0; phase < MAX_PHASES; phase++) {
      if (epsilon < target) break;
      if (!auctionPass(matrix, prices, rowAssignment, columnAssignment, epsilon)) break;
      epsilon *= 0.5;
    }
    for (int assigned : rowAssignment) if (assigned == -1) return lapjv(matrix);
    return result(matrix, rowAssignment, columnAssignment);
  }

  private static boolean auctionPass(double[][] matrix, double[] prices, int[] rows, int[] columns,
                                     double epsilon) {
    Arrays.fill(rows, -1);
    Arrays.fill(columns, -1);
    ArrayDeque<Integer> unassigned = new ArrayDeque<>();
    for (int row = 0; row < rows.length; row++) unassigned.addLast(row);
    for (int bid = 0; bid < MAX_PHASE_BIDS; bid++) {
      if (unassigned.isEmpty()) return true;
      int bidder = unassigned.removeFirst();
      int best = -1;
      double bestValue = Double.POSITIVE_INFINITY, second = Double.POSITIVE_INFINITY;
      for (int item = 0; item < rows.length; item++) {
        double value = matrix[bidder][item] + prices[item];
        if (value < bestValue) {
          second = bestValue;
          bestValue = value;
          best = item;
        } else if (value < second) {
          second = value;
        }
      }
      if (best == -1) throw new IllegalArgumentException("bidder has no comparable item");
      prices[best] += second == Double.POSITIVE_INFINITY ? epsilon : second - bestValue + epsilon;
      if (columns[best] != -1) {
        int displaced = columns[best];
        unassigned.addLast(displaced);
        rows[displaced] = -1;
      }
      rows[bidder] = best;
      columns[best] = bidder;
    }
    return false;
  }

  private static Assignment lapjv(double[][] matrix) {
    int size = matrix.length;
    double[] v = new double[size];
    int[] rows = empty(size), columns = empty(size), claims = new int[size];
    for (int j = 0; j < size; j++) {
      int bestRow = 0;
      double bestValue = matrix[0][j];
      for (int i = 1; i < size; i++) {
        if (matrix[i][j] < bestValue) { bestValue = matrix[i][j]; bestRow = i; }
      }
      v[j] = bestValue;
      claims[bestRow]++;
      int prior = rows[bestRow];
      if (prior == -1) {
        rows[bestRow] = j;
        columns[j] = bestRow;
      } else if (bestValue < v[prior]) {
        rows[bestRow] = j;
        columns[j] = bestRow;
        columns[prior] = -1;
      } else {
        columns[j] = -1;
      }
    }
    for (int i = 0; i < size; i++) {
      if (claims[i] != 1) continue;
      int selected = rows[i];
      double other = Double.POSITIVE_INFINITY;
      for (int j = 0; j < size; j++) {
        if (j != selected && matrix[i][j] - v[j] < other) other = matrix[i][j] - v[j];
      }
      if (Double.isFinite(other)) v[selected] -= other;
    }
    double[] u = new double[size];
    for (int i = 0; i < size; i++) {
      u[i] = Double.POSITIVE_INFINITY;
      for (int j = 0; j < size; j++) u[i] = sourceMin(u[i], matrix[i][j] - v[j]);
    }
    return sap(matrix, u, v, rows);
  }

  private static Assignment sap(double[][] cost, double[] rowDual, double[] columnDual, int[] initial) {
    int size = cost.length;
    if (size == 0) return new Assignment(0.0, new int[0], new int[0]);
    double[] u = new double[size + 1], v = new double[size + 1];
    System.arraycopy(rowDual, 0, u, 1, size);
    System.arraycopy(columnDual, 0, v, 1, size);
    int[] matchedRow = new int[size + 1], way = new int[size + 1];
    for (int i = 0; i < size; i++) if (initial[i] != -1) matchedRow[initial[i] + 1] = i + 1;
    for (int free = 0; free < size; free++) {
      if (initial[free] != -1) continue;
      matchedRow[0] = free + 1;
      int j0 = 0;
      double[] best = new double[size + 1];
      Arrays.fill(best, Double.POSITIVE_INFINITY);
      boolean[] used = new boolean[size + 1];
      do {
        used[j0] = true;
        int row = matchedRow[j0], j1 = 0;
        double delta = Double.POSITIVE_INFINITY;
        for (int j = 1; j <= size; j++) {
          if (used[j]) continue;
          double current = cost[row - 1][j - 1] - u[row] - v[j];
          if (current < best[j]) { best[j] = current; way[j] = j0; }
          if (best[j] < delta) { delta = best[j]; j1 = j; }
        }
        if (j1 == 0) throw new IllegalArgumentException("matching cannot find an augmenting path");
        for (int j = 0; j <= size; j++) {
          if (used[j]) { u[matchedRow[j]] += delta; v[j] -= delta; }
          else best[j] -= delta;
        }
        j0 = j1;
      } while (matchedRow[j0] != 0);
      do {
        int prior = way[j0];
        matchedRow[j0] = matchedRow[prior];
        j0 = prior;
      } while (j0 != 0);
    }
    int[] rows = empty(size), columns = empty(size);
    for (int j = 1; j <= size; j++) {
      if (matchedRow[j] != 0) { rows[matchedRow[j] - 1] = j - 1; columns[j - 1] = matchedRow[j] - 1; }
    }
    return result(cost, rows, columns);
  }

  private static Assignment trim(double[][] original, Assignment padded) {
    int rows = original.length, columns = original[0].length;
    int[] rowAssignment = empty(rows), columnAssignment = empty(columns);
    for (int i = 0; i < rows; i++) {
      if (padded.rowAssignment()[i] < columns) rowAssignment[i] = padded.rowAssignment()[i];
    }
    for (int j = 0; j < columns; j++) {
      if (padded.columnAssignment()[j] < rows) columnAssignment[j] = padded.columnAssignment()[j];
    }
    return result(original, rowAssignment, columnAssignment);
  }

  private static Assignment result(double[][] matrix, int[] rows, int[] columns) {
    double total = -0.0;
    for (int i = 0; i < rows.length; i++) if (rows[i] != -1) total += matrix[i][rows[i]];
    return new Assignment(total, rows, columns);
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
