package method.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Greedy one-to-one matching; an unassigned row or column is represented by -1. */
public final class A000020 {
  private A000020() { }

  public record Assignment(double totalCost, int[] rowAssignment, int[] columnAssignment) { }

  private record Edge(int row, int column, double cost) { }

  public static Assignment solve(double[][] matrix) {
    Objects.requireNonNull(matrix, "matrix");
    int rows = matrix.length;
    if (rows == 0) return new Assignment(0.0, new int[0], new int[0]);
    int columns = Objects.requireNonNull(matrix[0], "matrix row").length;
    List<Edge> edges = new ArrayList<>();
    for (int row = 0; row < rows; row++) {
      if (Objects.requireNonNull(matrix[row], "matrix row").length != columns) {
        throw new IllegalArgumentException("matrix must be rectangular");
      }
      for (int column = 0; column < columns; column++) {
        if (Double.isFinite(matrix[row][column])) {
          edges.add(new Edge(row, column, matrix[row][column]));
        }
      }
    }
    // Stable ordering also treats -0.0 and +0.0 as equal, as in the source.
    edges.sort((left, right) -> left.cost() < right.cost() ? -1 : left.cost() > right.cost() ? 1 : 0);
    int[] rowAssignment = new int[rows];
    int[] columnAssignment = new int[columns];
    Arrays.fill(rowAssignment, -1);
    Arrays.fill(columnAssignment, -1);
    for (Edge edge : edges) {
      if (rowAssignment[edge.row()] == -1 && columnAssignment[edge.column()] == -1) {
        rowAssignment[edge.row()] = edge.column();
        columnAssignment[edge.column()] = edge.row();
      }
    }
    // Rust's f64 iterator sum uses negative zero as its additive identity.
    double total = -0.0;
    for (int row = 0; row < rows; row++) {
      if (rowAssignment[row] != -1) total += matrix[row][rowAssignment[row]];
    }
    return new Assignment(total, rowAssignment, columnAssignment);
  }
}
