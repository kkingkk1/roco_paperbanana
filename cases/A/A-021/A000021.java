package method.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Hungarian matching by zero reduction, marking and alternating paths. */
public final class A000021 {
  private A000021() { }

  public record Assignment(double totalCost, int[] rowAssignment, int[] columnAssignment) { }
  private enum Mark { NONE, STAR, PRIME }

  public static Assignment solve(double[][] matrix) {
    Objects.requireNonNull(matrix, "matrix");
    int rows = matrix.length;
    if (rows == 0) return new Assignment(0.0, new int[0], new int[0]);
    int columns = Objects.requireNonNull(matrix[0], "matrix row").length;
    if (columns == 0) throw new IllegalArgumentException("matrix must have a column");
    double maximum = Double.NEGATIVE_INFINITY;
    for (double[] row : matrix) {
      if (Objects.requireNonNull(row, "matrix row").length != columns) {
        throw new IllegalArgumentException("matrix must be rectangular");
      }
      for (double value : row) {
        maximum = sourceMax(maximum, value);
      }
    }
    int size = Math.max(rows, columns);
    double[][] cost = new double[size][size];
    double scale = 1.0;
    for (int i = 0; i < size; i++) {
      Arrays.fill(cost[i], maximum + 1.0);
      if (i < rows) System.arraycopy(matrix[i], 0, cost[i], 0, columns);
      for (double value : cost[i]) scale = sourceMax(scale, Math.abs(value));
    }
    double epsilon = scale * 1e-9;
    for (double[] row : cost) {
      double minimum = Double.POSITIVE_INFINITY;
      for (double value : row) minimum = sourceMin(minimum, value);
      for (int j = 0; j < size; j++) row[j] -= minimum;
    }
    for (int j = 0; j < size; j++) {
      double minimum = Double.POSITIVE_INFINITY;
      for (int i = 0; i < size; i++) minimum = sourceMin(minimum, cost[i][j]);
      for (int i = 0; i < size; i++) cost[i][j] -= minimum;
    }

    Mark[][] marks = new Mark[size][size];
    for (Mark[] row : marks) Arrays.fill(row, Mark.NONE);
    boolean[] rowCovered = new boolean[size];
    boolean[] colCovered = new boolean[size];
    for (int i = 0; i < size; i++) {
      for (int j = 0; j < size; j++) {
        if (Math.abs(cost[i][j]) < epsilon && !rowCovered[i] && !colCovered[j]) {
          marks[i][j] = Mark.STAR;
          rowCovered[i] = true;
          colCovered[j] = true;
        }
      }
    }
    Arrays.fill(rowCovered, false);
    Arrays.fill(colCovered, false);
    int pathRow = 0, pathColumn = 0, step = 3;
    while (step != 7) {
      switch (step) {
        case 3 -> {
          int covered = 0;
          for (int j = 0; j < size; j++) {
            for (int i = 0; i < size; i++) {
              if (marks[i][j] == Mark.STAR) { colCovered[j] = true; break; }
            }
            if (colCovered[j]) covered++;
          }
          step = covered >= size ? 7 : 4;
        }
        case 4 -> {
          int foundRow = -1, foundColumn = -1;
          scan: for (int i = 0; i < size; i++) {
            if (rowCovered[i]) continue;
            for (int j = 0; j < size; j++) {
              if (!colCovered[j] && Math.abs(cost[i][j]) < epsilon) {
                foundRow = i;
                foundColumn = j;
                break scan;
              }
            }
          }
          if (foundRow == -1) {
            step = 6;
          } else {
            marks[foundRow][foundColumn] = Mark.PRIME;
            int star = -1;
            for (int j = 0; j < size; j++) {
              if (marks[foundRow][j] == Mark.STAR) { star = j; break; }
            }
            if (star != -1) {
              rowCovered[foundRow] = true;
              colCovered[star] = false;
            } else {
              pathRow = foundRow;
              pathColumn = foundColumn;
              step = 5;
            }
          }
        }
        case 5 -> {
          List<int[]> path = new ArrayList<>();
          path.add(new int[]{pathRow, pathColumn});
          while (true) {
            int column = path.getLast()[1], starRow = -1;
            for (int i = 0; i < size; i++) {
              if (marks[i][column] == Mark.STAR) { starRow = i; break; }
            }
            if (starRow == -1) break;
            path.add(new int[]{starRow, column});
            int primeColumn = -1;
            for (int j = 0; j < size; j++) {
              if (marks[starRow][j] == Mark.PRIME) { primeColumn = j; break; }
            }
            if (primeColumn == -1) throw new IllegalStateException("missing primed zero");
            path.add(new int[]{starRow, primeColumn});
          }
          for (int[] edge : path) {
            marks[edge[0]][edge[1]] = marks[edge[0]][edge[1]] == Mark.STAR ? Mark.NONE : Mark.STAR;
          }
          Arrays.fill(rowCovered, false);
          Arrays.fill(colCovered, false);
          for (Mark[] row : marks) {
            for (int j = 0; j < size; j++) if (row[j] == Mark.PRIME) row[j] = Mark.NONE;
          }
          step = 3;
        }
        case 6 -> {
          double minimum = Double.POSITIVE_INFINITY;
          for (int i = 0; i < size; i++) {
            if (rowCovered[i]) continue;
            for (int j = 0; j < size; j++) {
              if (!colCovered[j] && cost[i][j] < minimum) minimum = cost[i][j];
            }
          }
          if (!Double.isFinite(minimum)) {
            throw new IllegalArgumentException("matching cannot expose a finite uncovered cost");
          }
          for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
              if (rowCovered[i]) cost[i][j] += minimum;
              if (!colCovered[j]) cost[i][j] -= minimum;
            }
          }
          step = 4;
        }
        default -> throw new IllegalStateException("invalid matching step");
      }
    }
    int[] rowAssignment = new int[rows];
    int[] columnAssignment = new int[columns];
    Arrays.fill(rowAssignment, -1);
    Arrays.fill(columnAssignment, -1);
    for (int i = 0; i < rows; i++) {
      for (int j = 0; j < columns; j++) {
        if (marks[i][j] == Mark.STAR) {
          rowAssignment[i] = j;
          columnAssignment[j] = i;
        }
      }
    }
    double total = -0.0;
    for (int i = 0; i < rows; i++) if (rowAssignment[i] != -1) total += matrix[i][rowAssignment[i]];
    return new Assignment(total, rowAssignment, columnAssignment);
  }

  private static double sourceMin(double left, double right) {
    return Double.isNaN(left) ? right : Double.isNaN(right) ? left : Math.min(left, right);
  }

  private static double sourceMax(double left, double right) {
    return Double.isNaN(left) ? right : Double.isNaN(right) ? left : Math.max(left, right);
  }
}
