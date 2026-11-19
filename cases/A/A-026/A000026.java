package method.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Murty partitioning for multiple best linear assignments. */
public final class A000026 {
  private A000026() { }

  public record Assignment(double totalCost, int[] rowAssignment, int[] columnAssignment) { }
  private record Edge(int row, int column) { }
  private record Subproblem(List<Edge> fixed, List<Edge> forbidden, int fixedCount) { }
  private record Candidate(Assignment assignment, Subproblem subproblem) { }

  public static List<Assignment> solveKBest(double[][] matrix, int k) {
    Objects.requireNonNull(matrix, "matrix");
    if (k < 0) throw new IllegalArgumentException("k must be nonnegative");
    int rows = matrix.length;
    if (rows == 0 || k == 0) return List.of();
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
    double maxAbs = 0.0;
    for (int i = 0; i < n; i++) {
      Arrays.fill(padded[i], maximum + 1.0);
      if (i < rows) System.arraycopy(matrix[i], 0, padded[i], 0, columns);
      for (double value : padded[i]) if (Double.isFinite(value)) maxAbs = Math.max(maxAbs, Math.abs(value));
    }
    double sentinel = (n + 1.0) * maxAbs + 1.0;
    if (!Double.isFinite(sentinel)) sentinel = Double.MAX_VALUE / (n + 4.0);
    Subproblem initial = new Subproblem(List.of(), List.of(), 0);
    Candidate first = solveSubproblem(padded, initial, sentinel);
    CandidateHeap heap = new CandidateHeap();
    if (first != null) heap.add(first);
    List<Assignment> results = new ArrayList<>();
    while (!heap.isEmpty()) {
      Candidate candidate = heap.remove();
      Assignment assignment = candidate.assignment();
      results.add(rows == columns ? assignment : trim(matrix, assignment));
      if (results.size() >= k) break;
      List<Edge> active = new ArrayList<>();
      for (int r = 0; r < n; r++) if (assignment.rowAssignment()[r] != -1) {
        active.add(new Edge(r, assignment.rowAssignment()[r]));
      }
      List<Edge> fixed = new ArrayList<>(candidate.subproblem().fixed());
      for (int i = candidate.subproblem().fixedCount(); i < active.size(); i++) {
        Edge edge = active.get(i);
        List<Edge> forbidden = new ArrayList<>(candidate.subproblem().forbidden());
        forbidden.add(edge);
        Subproblem child = new Subproblem(List.copyOf(fixed), List.copyOf(forbidden), fixed.size());
        Candidate next = solveSubproblem(padded, child, sentinel);
        if (next != null) heap.add(next);
        fixed.add(edge);
      }
    }
    return results;
  }

  private static Candidate solveSubproblem(double[][] original, Subproblem subproblem, double sentinel) {
    int n = original.length;
    double[][] cost = new double[n][];
    for (int i = 0; i < n; i++) cost[i] = original[i].clone();
    for (Edge edge : subproblem.forbidden()) cost[edge.row()][edge.column()] = sentinel;
    for (Edge edge : subproblem.fixed()) {
      for (int j = 0; j < n; j++) if (j != edge.column()) cost[edge.row()][j] = sentinel;
      for (int i = 0; i < n; i++) if (i != edge.row()) cost[i][edge.column()] = sentinel;
    }
    Assignment solution = sap(cost);
    for (Edge edge : subproblem.fixed()) if (solution.rowAssignment()[edge.row()] != edge.column()) return null;
    for (Edge edge : subproblem.forbidden()) if (solution.rowAssignment()[edge.row()] == edge.column()) return null;
    return new Candidate(result(original, solution.rowAssignment(), solution.columnAssignment()), subproblem);
  }

  private static Assignment sap(double[][] cost) {
    int n = cost.length;
    double[] u = new double[n + 1], v = new double[n + 1];
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
    int[] rows = empty(n), columns = empty(n);
    for (int j = 1; j <= n; j++) if (matched[j] != 0) {
      rows[matched[j] - 1] = j - 1;
      columns[j - 1] = matched[j] - 1;
    }
    return result(cost, rows, columns);
  }

  private static Assignment trim(double[][] matrix, Assignment padded) {
    int rows = matrix.length, columns = matrix[0].length;
    int[] outRows = empty(rows), outColumns = empty(columns);
    for (int i = 0; i < rows; i++) if (padded.rowAssignment()[i] < columns) outRows[i] = padded.rowAssignment()[i];
    for (int j = 0; j < columns; j++) if (padded.columnAssignment()[j] < rows) outColumns[j] = padded.columnAssignment()[j];
    return result(matrix, outRows, outColumns);
  }

  private static Assignment result(double[][] matrix, int[] rows, int[] columns) {
    double total = -0.0;
    for (int i = 0; i < rows.length; i++) if (rows[i] != -1) total += matrix[i][rows[i]];
    return new Assignment(total, rows, columns);
  }

  private static int[] empty(int size) { int[] result = new int[size]; Arrays.fill(result, -1); return result; }
  private static double sourceMax(double a, double b) { return Double.isNaN(a) ? b : Double.isNaN(b) || a >= b ? a : b; }

  /** Reproduces the source binary heap's right-child tie handling for equal costs. */
  private static final class CandidateHeap {
    private final List<Candidate> entries = new ArrayList<>();
    boolean isEmpty() { return entries.isEmpty(); }
    private boolean greater(Candidate a, Candidate b) { return a.assignment().totalCost() < b.assignment().totalCost(); }
    void add(Candidate candidate) {
      entries.add(candidate);
      siftUp(entries.size() - 1, 0);
    }
    Candidate remove() {
      Candidate result = entries.get(0);
      Candidate last = entries.remove(entries.size() - 1);
      if (!entries.isEmpty()) {
        entries.set(0, last);
        int position = 0, size = entries.size();
        while (2 * position + 1 < size) {
          int child = 2 * position + 1;
          if (child + 1 < size && !greater(entries.get(child), entries.get(child + 1))) child++;
          entries.set(position, entries.get(child));
          position = child;
        }
        entries.set(position, last);
        siftUp(position, 0);
      }
      return result;
    }
    private void siftUp(int position, int start) {
      Candidate value = entries.get(position);
      while (position > start) {
        int parent = (position - 1) / 2;
        if (!greater(value, entries.get(parent))) break;
        entries.set(position, entries.get(parent));
        position = parent;
      }
      entries.set(position, value);
    }
  }
}
