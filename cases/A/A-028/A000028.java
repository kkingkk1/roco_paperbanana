package method.runtime;

import java.util.Arrays;
import java.util.Objects;

/** Score-based quota assignment with greedy initialization and paired swaps. */
public final class A000028 {
  private A000028() { }

  public record Assignment(int[][] rowsByResource, float totalScore, String stopReason, int iterations) { }

  public static Assignment solve(float[][] scores, int[] quotas, double maxSeconds, double absoluteThreshold,
                                 double percentThreshold, int maxIterations, boolean skipFirstPhase) {
    Objects.requireNonNull(scores, "scores");
    Objects.requireNonNull(quotas, "quotas");
    int rows = scores.length, columns = quotas.length, total = 0;
    if (columns < 2) throw new IllegalArgumentException("at least two resources are required");
    if (maxSeconds < 0 || absoluteThreshold < 0 || percentThreshold < 0 || maxIterations < 0) {
      throw new IllegalArgumentException("limits must be nonnegative");
    }
    for (int quota : quotas) {
      if (quota <= 0) throw new IllegalArgumentException("quotas must be positive");
      total = Math.addExact(total, quota);
    }
    if (total != rows) throw new IllegalArgumentException("quotas must sum to the number of rows");
    for (float[] row : scores) {
      if (Objects.requireNonNull(row, "score row").length != columns) {
        throw new IllegalArgumentException("matrix must be rectangular and match quotas");
      }
      for (float value : row) if (!Float.isFinite(value) || value == -Float.MAX_VALUE) {
        throw new IllegalArgumentException("scores must be finite and greater than -Float.MAX_VALUE");
      }
    }
    if (columns == 2) { maxIterations = 0; skipFirstPhase = false; }
    long start = System.nanoTime();
    int[] assignment = new int[rows];
    if (skipFirstPhase) {
      int position = 0;
      for (int column = 0; column < columns; column++) {
        for (int i = 0; i < quotas[column]; i++) assignment[position++] = column;
      }
    } else {
      float[] priority = new float[rows];
      int[] order = new int[rows], remaining = quotas.clone();
      for (int i = 0; i < rows; i++) {
        float best = scores[i][0], second = -Float.MAX_VALUE;
        for (int j = 1; j < columns; j++) {
          if (scores[i][j] > best) { second = best; best = scores[i][j]; }
          else if (scores[i][j] > second) second = scores[i][j];
        }
        if (second == -Float.MAX_VALUE) second = best;
        priority[i] = best + (best - second);
        order[i] = i;
      }
      quicksort(priority, order, 0, rows - 1);
      for (int row : order) {
        int best = -1;
        float bestValue = -Float.MAX_VALUE;
        for (int col = 0; col < columns; col++) if (remaining[col] > 0 && scores[row][col] > bestValue) {
          bestValue = scores[row][col]; best = col;
        }
        if (best < 0) throw new IllegalArgumentException("no assignable score remains");
        assignment[row] = best;
        remaining[best]--;
      }
    }
    int[][] groups = new int[columns][];
    int[] positions = new int[columns];
    for (int col = 0; col < columns; col++) groups[col] = new int[quotas[col]];
    for (int row = 0; row < rows; row++) {
      int col = assignment[row];
      groups[col][positions[col]++] = row;
    }
    float sum = compensatedSum(scores, groups);
    if (maxIterations == 0) return new Assignment(groups, sum, "no_swap", 0);
    if (secondsSince(start) > maxSeconds) return new Assignment(groups, sum, "first_step", 0);

    float[][] cached = new float[columns][columns];
    boolean[] previouslyUnchanged = new boolean[columns];
    for (int iteration = 0; iteration < maxIterations; iteration++) {
      float[] gainPerColumn = new float[columns];
      boolean[] unchanged = new boolean[columns];
      Arrays.fill(unchanged, true);
      for (int a = 0; a < columns - 1; a++) {
        boolean oldA = previouslyUnchanged[a];
        for (int b = a + 1; b < columns; b++) {
          if (iteration > 0 && oldA && previouslyUnchanged[b]) continue;
          float[] gainA = new float[quotas[a]], gainB = new float[quotas[b]];
          float maxA, maxB;
          if (iteration > 0 && oldA) {
            maxA = cached[a][b];
            maxB = gains(scores, groups[b], b, a, gainB);
            cached[b][a] = maxB;
            if (maxA + maxB <= 0) continue;
            maxA = gains(scores, groups[a], a, b, gainA);
            cached[a][b] = maxA;
          } else if (iteration > 0 && previouslyUnchanged[b]) {
            maxB = cached[b][a];
            maxA = gains(scores, groups[a], a, b, gainA);
            cached[a][b] = maxA;
            if (maxA + maxB <= 0) continue;
            maxB = gains(scores, groups[b], b, a, gainB);
            cached[b][a] = maxB;
          } else {
            maxA = gains(scores, groups[a], a, b, gainA);
            maxB = gains(scores, groups[b], b, a, gainB);
            cached[a][b] = maxA;
            cached[b][a] = maxB;
          }
          if (maxA + maxB <= 0) continue;
          boolean[] usedA = new boolean[quotas[a]], usedB = new boolean[quotas[b]];
          int count = Math.min(quotas[a], quotas[b]);
          int[] selectedA = new int[count], selectedB = new int[count];
          int swaps = 0;
          while (swaps < count) {
            int i = bestUnused(gainA, usedA), j = bestUnused(gainB, usedB);
            if (i < 0 || j < 0 || gainA[i] + gainB[j] <= 0) break;
            selectedA[swaps] = i; selectedB[swaps] = j;
            usedA[i] = true; usedB[j] = true; swaps++;
          }
          for (int s = 0; s < swaps; s++) {
            int i = selectedA[s], j = selectedB[s];
            int rowA = groups[a][i], rowB = groups[b][j];
            groups[a][i] = rowB; groups[b][j] = rowA;
            gainPerColumn[a] += scores[rowB][a] - scores[rowA][a];
            gainPerColumn[b] += scores[rowA][b] - scores[rowB][b];
          }
          if (swaps > 0) {
            if (iteration == 0) { unchanged[a] = false; unchanged[b] = false; }
            else { unchanged[a] &= maxA < 0; unchanged[b] &= maxB < 0; }
          }
        }
      }
      previouslyUnchanged = unchanged;
      float increase = 0;
      for (float gain : gainPerColumn) increase += gain;
      sum += increase;
      int completed = iteration + 1;
      if (maxSeconds > 0 && secondsSince(start) > maxSeconds) return new Assignment(groups, sum, "time", completed);
      if (increase <= 0 || (absoluteThreshold > 0 && increase < absoluteThreshold) ||
          (percentThreshold > 0 && 100 * increase / sum < percentThreshold)) {
        return new Assignment(groups, sum, "tolerance", completed);
      }
    }
    return new Assignment(groups, sum, "iteration_limit", maxIterations);
  }

  private static double secondsSince(long start) { return (System.nanoTime() - start) / 1_000_000_000.0; }

  private static float compensatedSum(float[][] scores, int[][] groups) {
    float sum = 0, compensation = 0;
    for (int col = 0; col < groups.length; col++) for (int row : groups[col]) {
      float y = scores[row][col] - compensation;
      float next = sum + y;
      compensation = (next - sum) - y;
      sum = next;
    }
    return sum;
  }

  private static float gains(float[][] scores, int[] rows, int from, int to, float[] result) {
    float maximum = Float.NEGATIVE_INFINITY;
    for (int i = 0; i < rows.length; i++) {
      float value = scores[rows[i]][to] - scores[rows[i]][from];
      result[i] = value;
      if (value > maximum) maximum = value;
    }
    return maximum;
  }

  private static int bestUnused(float[] values, boolean[] used) {
    int best = -1;
    for (int i = 0; i < values.length; i++) if (!used[i] && (best < 0 || values[i] > values[best])) best = i;
    return best;
  }

  private static void quicksort(float[] scores, int[] order, int low, int high) {
    if (low >= high) return;
    float pivot = scores[order[high]];
    int boundary = low - 1;
    for (int j = low; j < high; j++) if (scores[order[j]] > pivot) {
      int tmp = order[++boundary]; order[boundary] = order[j]; order[j] = tmp;
    }
    int tmp = order[boundary + 1]; order[boundary + 1] = order[high]; order[high] = tmp;
    int split = boundary + 1;
    quicksort(scores, order, low, split - 1);
    quicksort(scores, order, split + 1, high);
  }
}
