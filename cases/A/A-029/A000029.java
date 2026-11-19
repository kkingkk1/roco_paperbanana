package method.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/** Asymmetric greedy initialization followed by row and unused-column swaps. */
public final class A000029 {
  private A000029() { }

  public record Assignment(int[] rows, int[] columns) { }
  private record Moves(int[] rowIndex, float[] rowDelta, int[] columnIndex, float[] columnDelta) { }

  public static Assignment optimize(double[][] input, boolean minimize, boolean shuffle,
                                    int maximumIterations, int stagnationLimit,
                                    double stagnationTolerance, int rowBatchSize) {
    Objects.requireNonNull(input, "input");
    int[] order = new int[input.length];
    for (int i = 0; i < order.length; i++) order[i] = i;
    if (shuffle) for (int i = order.length - 1; i > 0; i--) {
      int j = ThreadLocalRandom.current().nextInt(i + 1);
      int temp = order[i]; order[i] = order[j]; order[j] = temp;
    }
    return optimize(input, minimize, order, maximumIterations, stagnationLimit, stagnationTolerance, rowBatchSize);
  }

  public static Assignment optimize(double[][] input, boolean minimize, int[] rowOrder,
                                    int maximumIterations, int stagnationLimit,
                                    double stagnationTolerance, int rowBatchSize) {
    Objects.requireNonNull(input, "input");
    int rows = input.length;
    if (rows == 0) throw new IllegalArgumentException("matrix must not be empty");
    int columns = Objects.requireNonNull(input[0], "matrix row").length;
    if (columns < rows) throw new IllegalArgumentException("rows must not exceed columns");
    if (rowBatchSize <= 0) throw new IllegalArgumentException("row batch size must be positive");
    if (rowOrder == null || rowOrder.length != rows) throw new IllegalArgumentException("invalid row order");
    boolean[] seen = new boolean[rows];
    double minimum = Double.POSITIVE_INFINITY, maximum = Double.NEGATIVE_INFINITY;
    for (int row : rowOrder) {
      if (row < 0 || row >= rows || seen[row]) throw new IllegalArgumentException("invalid row order");
      seen[row] = true;
    }
    for (double[] row : input) {
      if (Objects.requireNonNull(row, "matrix row").length != columns) {
        throw new IllegalArgumentException("matrix must be rectangular");
      }
      for (double value : row) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("matrix values must be finite");
        minimum = Math.min(minimum, value);
        maximum = Math.max(maximum, value);
      }
    }
    double range = maximum - minimum;
    if (!Double.isFinite(range)) throw new IllegalArgumentException("matrix range must be finite");
    double[][] benefit = new double[rows][columns];
    for (int i = 0; i < rows; i++) for (int j = 0; j < columns; j++) {
      double scaled = range > 0.0 ? (input[i][j] - minimum) / range : 0.0;
      benefit[i][j] = minimize ? 1.0 - scaled : scaled;
    }
    int[] assignment = new int[rows];
    boolean[] available = new boolean[columns];
    Arrays.fill(available, true);
    for (int row : rowOrder) {
      int best = -1;
      for (int col = 0; col < columns; col++) {
        if (available[col] && (best == -1 || benefit[row][col] > benefit[row][best])) best = col;
      }
      assignment[row] = best;
      available[best] = false;
    }
    Moves moves = moves(benefit, assignment, available);
    float rowMax = maximum(moves.rowDelta()), columnMax = maximum(moves.columnDelta());
    double currentMax = Math.max(rowMax, columnMax);
    double tolerance = currentMax * stagnationTolerance;
    int iterations = 0, stale = 0;
    while (currentMax > 0.0) {
      if (rowMax > columnMax) {
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < rows; i++) if (moves.rowDelta()[i] > 0) candidates.add(i);
        Moves currentMoves = moves;
        candidates.sort((a, b) -> {
          int byGain = Float.compare(currentMoves.rowDelta()[b], currentMoves.rowDelta()[a]);
          return byGain != 0 ? byGain : Integer.compare(a, b);
        });
        boolean[] locked = new boolean[rows];
        List<Integer> batch = new ArrayList<>();
        for (int row : candidates) {
          if (batch.size() >= rowBatchSize) break;
          int other = moves.rowIndex()[row];
          if (locked[row] || locked[other]) continue;
          batch.add(row);
          locked[row] = true;
          locked[other] = true;
        }
        for (int row : batch) {
          int other = moves.rowIndex()[row];
          int temp = assignment[row]; assignment[row] = assignment[other]; assignment[other] = temp;
          boolean[] affected = new boolean[rows];
          for (int i = 0; i < rows; i++) affected[i] = moves.rowIndex()[i] == row || moves.rowIndex()[i] == other;
          for (int i = 0; i < rows; i++) if (affected[i]) updateRow(benefit, assignment, moves, i);
        }
      } else {
        int row = 0;
        for (int i = 1; i < rows; i++) if (moves.columnDelta()[i] > moves.columnDelta()[row]) row = i;
        int old = assignment[row], next = moves.columnIndex()[row];
        assignment[row] = next;
        available[old] = true;
        available[next] = false;
      }
      moves = moves(benefit, assignment, available);
      rowMax = maximum(moves.rowDelta());
      columnMax = maximum(moves.columnDelta());
      currentMax = Math.max(rowMax, columnMax);
      iterations++;
      if (maximumIterations > 0 && iterations >= maximumIterations) break;
      stale = stagnationLimit > 0 && currentMax <= tolerance ? stale + 1 : 0;
      if (stagnationLimit > 0 && stale >= stagnationLimit) break;
    }
    int[] rowIndices = new int[rows];
    for (int i = 0; i < rows; i++) rowIndices[i] = i;
    return new Assignment(rowIndices, assignment);
  }

  private static Moves moves(double[][] benefit, int[] assignment, boolean[] available) {
    int rows = assignment.length;
    Moves result = new Moves(new int[rows], new float[rows], new int[rows], new float[rows]);
    for (int i = 0; i < rows; i++) {
      updateRow(benefit, assignment, result, i);
      int best = -1;
      for (int j = 0; j < available.length; j++) if (available[j] &&
          (best == -1 || benefit[i][j] > benefit[i][best])) best = j;
      result.columnIndex()[i] = best;
      result.columnDelta()[i] = best == -1 ? Float.NEGATIVE_INFINITY :
          (float) (benefit[i][best] - benefit[i][assignment[i]]);
    }
    return result;
  }

  private static void updateRow(double[][] benefit, int[] assignment, Moves moves, int row) {
    int best = -1;
    double bestDelta = Double.NEGATIVE_INFINITY;
    for (int other = 0; other < assignment.length; other++) {
      double delta = row == other ? -1.0 :
          (benefit[row][assignment[other]] + benefit[other][assignment[row]]) -
          (benefit[row][assignment[row]] + benefit[other][assignment[other]]);
      if (best == -1 || delta > bestDelta) { best = other; bestDelta = delta; }
    }
    moves.rowIndex()[row] = best;
    moves.rowDelta()[row] = (float) bestDelta;
  }

  private static float maximum(float[] values) {
    float result = Float.NEGATIVE_INFINITY;
    for (float value : values) result = Math.max(result, value);
    return result;
  }
}
