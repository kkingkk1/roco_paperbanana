package method.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Transportation simplex with Dantzig entering pivots and a Bland fallback. */
public final class A000024 {
  private A000024() { }

  public record Assignment(double totalCost, int[] rowAssignment, int[] columnAssignment) { }
  public record AssignmentWithDuals(Assignment assignment, double[] rowDual, double[] columnDual) { }
  private record Basis(int[] rows, double[] rowDual, double[] columnDual) { }
  private record Cell(int row, int column, boolean positive) { }

  public static Assignment solve(double[][] matrix) {
    double[][] padded = padded(matrix);
    if (matrix.length == 0) return new Assignment(0.0, new int[0], new int[0]);
    return trim(matrix, simplex(padded).rows());
  }

  public static AssignmentWithDuals solveDuals(double[][] matrix) {
    double[][] padded = padded(matrix);
    int rows = matrix.length;
    if (rows == 0) return new AssignmentWithDuals(new Assignment(0.0, new int[0], new int[0]),
        new double[0], new double[0]);
    int n = padded.length, columns = matrix[0].length;
    Basis basis = simplex(padded);
    double[] rowDual = basis.rowDual().clone(), columnDual = basis.columnDual().clone();
    for (int i = 0; i < n; i++) {
      rowDual[i] = Double.POSITIVE_INFINITY;
      for (int j = 0; j < n; j++) rowDual[i] = sourceMin(rowDual[i], padded[i][j] - columnDual[j]);
    }
    AssignmentWithDuals result = sapDuals(padded, rowDual, columnDual);
    return new AssignmentWithDuals(trim(matrix, result.assignment().rowAssignment()),
        Arrays.copyOf(result.rowDual(), rows), Arrays.copyOf(result.columnDual(), columns));
  }

  private static double[][] padded(double[][] matrix) {
    Objects.requireNonNull(matrix, "matrix");
    if (matrix.length == 0) return new double[0][];
    int rows = matrix.length, columns = Objects.requireNonNull(matrix[0], "matrix row").length;
    double maximum = Double.NEGATIVE_INFINITY;
    for (double[] row : matrix) {
      if (Objects.requireNonNull(row, "matrix row").length != columns) {
        throw new IllegalArgumentException("matrix must be rectangular");
      }
      for (double value : row) maximum = sourceMax(maximum, value);
    }
    int n = Math.max(rows, columns);
    double[][] result = new double[n][n];
    for (int i = 0; i < n; i++) {
      Arrays.fill(result[i], maximum + 1.0);
      if (i < rows) System.arraycopy(matrix[i], 0, result[i], 0, columns);
    }
    return result;
  }

  private static Basis simplex(double[][] cost) {
    int n = cost.length;
    double[][] flow = new double[n][n];
    boolean[][] basic = new boolean[n][n];
    List<List<Integer>> tree = new ArrayList<>();
    for (int node = 0; node < 2 * n; node++) tree.add(new ArrayList<>());
    int[] parent = new int[2 * n];
    for (int node = 0; node < parent.length; node++) parent[node] = node;
    boolean[] taken = new boolean[n];
    for (int r = 0; r < n; r++) {
      int bestColumn = 0;
      double best = Double.POSITIVE_INFINITY;
      for (int c = 0; c < n; c++) {
        if (!taken[c] && cost[r][c] < best) { best = cost[r][c]; bestColumn = c; }
      }
      taken[bestColumn] = true;
      flow[r][bestColumn] = 1.0;
      basic[r][bestColumn] = true;
      tree.get(r).add(n + bestColumn);
      tree.get(n + bestColumn).add(r);
      union(parent, r, n + bestColumn);
    }
    int needed = n - 1;
    for (int r = 0; r < n && needed > 0; r++) {
      for (int c = 0; c < n && needed > 0; c++) {
        if (!basic[r][c] && union(parent, r, n + c)) {
          basic[r][c] = true;
          tree.get(r).add(n + c);
          tree.get(n + c).add(r);
          needed--;
        }
      }
    }
    double[] u = new double[n], v = new double[n];
    int degenerate = 0;
    for (int iter = 0; iter < 50 * n * n + 1000; iter++) {
      Arrays.fill(u, Double.NaN);
      Arrays.fill(v, Double.NaN);
      u[0] = 0.0;
      boolean[] visited = new boolean[2 * n];
      visited[0] = true;
      ArrayDeque<Integer> queue = new ArrayDeque<>();
      queue.add(0);
      while (!queue.isEmpty()) {
        int node = queue.removeFirst();
        for (int next : tree.get(node)) {
          if (!visited[next]) {
            visited[next] = true;
            if (node < n) v[next - n] = cost[node][next - n] - u[node];
            else u[next] = cost[next][node - n] - v[node - n];
            queue.addLast(next);
          }
        }
      }
      boolean bland = degenerate >= 4 * n + 16;
      int enterRow = -1, enterColumn = -1;
      double best = -1e-9;
      search: for (int r = 0; r < n; r++) {
        for (int c = 0; c < n; c++) {
          if (!basic[r][c]) {
            double reduced = cost[r][c] - u[r] - v[c];
            if (bland && reduced < -1e-9) { enterRow = r; enterColumn = c; break search; }
            if (!bland && reduced < best) { best = reduced; enterRow = r; enterColumn = c; }
          }
        }
      }
      if (enterRow < 0) break;
      int[] previous = new int[2 * n];
      Arrays.fill(previous, -1);
      previous[enterRow] = enterRow;
      queue.add(enterRow);
      while (!queue.isEmpty()) {
        int node = queue.removeFirst();
        if (node == n + enterColumn) break;
        for (int next : tree.get(node)) if (previous[next] < 0) {
          previous[next] = node;
          queue.addLast(next);
        }
      }
      int target = n + enterColumn;
      if (previous[target] < 0) throw new IllegalArgumentException("simplex basis is disconnected");
      List<Cell> cycle = new ArrayList<>();
      cycle.add(new Cell(enterRow, enterColumn, true));
      boolean positive = false;
      for (int node = target; node != enterRow; node = previous[node]) {
        int before = previous[node];
        int r = before < n ? before : node;
        int c = before < n ? node - n : before - n;
        cycle.add(new Cell(r, c, positive));
        positive = !positive;
      }
      double theta = Double.POSITIVE_INFINITY;
      int leaveRow = -1, leaveColumn = -1;
      for (Cell cell : cycle) if (!cell.positive()) {
        int r = cell.row(), c = cell.column();
        double f = flow[r][c];
        if (f < theta - 1e-9 || (f < theta + 1e-9 &&
            (leaveRow < 0 || r < leaveRow || (r == leaveRow && c < leaveColumn)))) {
          theta = f; leaveRow = r; leaveColumn = c;
        }
      }
      if (leaveRow < 0) break;
      degenerate = theta <= 1e-9 ? degenerate + 1 : 0;
      for (Cell cell : cycle) {
        if (cell.positive()) flow[cell.row()][cell.column()] += theta;
        else flow[cell.row()][cell.column()] -= theta;
      }
      basic[enterRow][enterColumn] = true;
      tree.get(enterRow).add(n + enterColumn);
      tree.get(n + enterColumn).add(enterRow);
      basic[leaveRow][leaveColumn] = false;
      tree.get(leaveRow).remove(Integer.valueOf(n + leaveColumn));
      tree.get(n + leaveColumn).remove(Integer.valueOf(leaveRow));
    }
    int[] assignment = empty(n);
    for (int r = 0; r < n; r++) for (int c = 0; c < n; c++) {
      if (basic[r][c] && flow[r][c] > 0.5) { assignment[r] = c; break; }
    }
    return new Basis(assignment, u, v);
  }

  private static int find(int[] parent, int x) {
    while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; }
    return x;
  }

  private static boolean union(int[] parent, int a, int b) {
    int x = find(parent, a), y = find(parent, b);
    if (x == y) return false;
    parent[x] = y;
    return true;
  }

  private static AssignmentWithDuals sapDuals(double[][] cost, double[] rowDual, double[] columnDual) {
    int n = cost.length;
    double[] u = new double[n + 1], v = new double[n + 1];
    System.arraycopy(rowDual, 0, u, 1, n);
    System.arraycopy(columnDual, 0, v, 1, n);
    int[] matched = new int[n + 1], way = new int[n + 1];
    for (int i = 1; i <= n; i++) {
      matched[0] = i;
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
    int[] assignment = empty(n);
    for (int j = 1; j <= n; j++) if (matched[j] != 0) assignment[matched[j] - 1] = j - 1;
    return new AssignmentWithDuals(trim(cost, assignment), Arrays.copyOfRange(u, 1, n + 1),
        Arrays.copyOfRange(v, 1, n + 1));
  }

  private static Assignment trim(double[][] matrix, int[] paddedRows) {
    int rows = matrix.length, columns = matrix[0].length;
    int[] rowAssignment = empty(rows), columnAssignment = empty(columns), paddedColumns = empty(paddedRows.length);
    for (int i = 0; i < paddedRows.length; i++) {
      if (paddedRows[i] >= 0) paddedColumns[paddedRows[i]] = i;
    }
    double total = -0.0;
    for (int i = 0; i < rows; i++) {
      int column = paddedRows[i];
      if (column >= 0 && column < columns) {
        rowAssignment[i] = column;
        total += matrix[i][column];
      }
    }
    for (int j = 0; j < columns; j++) if (paddedColumns[j] < rows) columnAssignment[j] = paddedColumns[j];
    return new Assignment(total, rowAssignment, columnAssignment);
  }

  private static int[] empty(int size) { int[] result = new int[size]; Arrays.fill(result, -1); return result; }
  private static double sourceMax(double a, double b) { return Double.isNaN(a) ? b : Double.isNaN(b) || a >= b ? a : b; }
  private static double sourceMin(double a, double b) { return Double.isNaN(a) ? b : Double.isNaN(b) || a <= b ? a : b; }
}
