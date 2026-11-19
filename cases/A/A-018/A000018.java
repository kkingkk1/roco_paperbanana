package method.runtime;

import java.util.Arrays;
import java.util.Objects;

/**
 * Generalized-assignment heuristic based on MTHG.
 * Silvano Martello and Paolo Toth, Knapsack Problems: Algorithms and Computer
 * Implementations, Wiley, 1990. Source conditions are recorded with this case.
 */
public final class A000018 {
  private A000018() { }

  public record Assignment(int value, int[] resourceByTask) { }
  private record Initial(int value, int[] assignment, int upper, int threshold, int infinity) { }
  private record Attempt(int value, int[] assignment) { }

  public static Assignment solve(int[][] profit, int[][] weight, int[] capacity, boolean minimize) {
    Objects.requireNonNull(profit, "profit");
    Objects.requireNonNull(weight, "weight");
    Objects.requireNonNull(capacity, "capacity");
    int m = capacity.length;
    if (m < 2 || m > 50 || profit.length != m || weight.length != m) {
      throw new IllegalArgumentException("the number of resources must be 2..50");
    }
    int n = Objects.requireNonNull(profit[0], "profit row").length;
    if (n < 2 || n > 500) throw new IllegalArgumentException("the number of tasks must be 2..500");
    int[][] p = new int[m][n];
    int largestProfit = 0, largestWeight = 0, largestCapacity = 0;
    for (int i = 0; i < m; i++) {
      if (capacity[i] <= 0 || Objects.requireNonNull(profit[i], "profit row").length != n ||
          Objects.requireNonNull(weight[i], "weight row").length != n) {
        throw new IllegalArgumentException("matrix dimensions and capacities must be valid");
      }
      largestCapacity = Math.max(largestCapacity, capacity[i]);
      boolean resourceUsable = false;
      for (int j = 0; j < n; j++) {
        if (profit[i][j] <= 0 || weight[i][j] <= 0) throw new IllegalArgumentException("values must be positive");
        largestProfit = Math.max(largestProfit, profit[i][j]);
        largestWeight = Math.max(largestWeight, weight[i][j]);
        if (weight[i][j] <= capacity[i]) resourceUsable = true;
      }
      if (!resourceUsable) throw new IllegalArgumentException("every resource must fit at least one task");
    }
    if (((long) largestProfit + 1) * n + Math.max(largestWeight, largestCapacity) > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("integer values exceed the source arithmetic range");
    }
    int transform = largestProfit + 1;
    for (int j = 0; j < n; j++) {
      boolean fits = false;
      for (int i = 0; i < m; i++) {
        if (weight[i][j] <= capacity[i]) {
          p[i][j] = minimize ? transform - profit[i][j] : profit[i][j];
          fits = true;
        }
      }
      if (!fits) return new Assignment(0, empty(n));
    }
    Initial first = greedyProfit(p, weight, capacity);
    int bestValue = first.value();
    int[] best = first.assignment();
    if (bestValue != first.upper()) {
      float[][] parameters = {{1, 0, 0, 0, 1}, {1, 0, 1, 0, 0}, {0, 1, 0, 1, 0}};
      for (float[] parameter : parameters) {
        Attempt trial = alternative(p, weight, capacity, first.infinity(), parameter);
        if (trial.value() > bestValue) { bestValue = trial.value(); best = trial.assignment(); }
      }
    }
    if (bestValue <= first.threshold()) return new Assignment(0, empty(n));
    int value = minimize ? n * transform - bestValue : bestValue;
    return new Assignment(value, best);
  }

  private static Initial greedyProfit(int[][] p, int[][] w, int[] capacity) {
    int m = capacity.length, n = p[0].length;
    int[] remaining = capacity.clone(), maxWeight = new int[m];
    int[] first = new int[n], second = empty(n), penalty = new int[n], pending = new int[n];
    int upper = 0, threshold = 0, infinity = 0;
    for (int c : capacity) infinity = Math.max(infinity, c);
    for (int j = 0; j < n; j++) {
      int minimum = p[0][j], best = p[0][j], runnerUp = 0, bestResource = 0, secondResource = -1;
      for (int i = 1; i < m; i++) {
        minimum = Math.min(minimum, p[i][j]);
        if (runnerUp >= p[i][j]) continue;
        if (best >= p[i][j]) { runnerUp = p[i][j]; secondResource = i; }
        else { runnerUp = best; secondResource = bestResource; best = p[i][j]; bestResource = i; }
      }
      threshold += minimum;
      first[j] = bestResource;
      second[j] = secondResource;
      penalty[j] = runnerUp == 0 ? -1 : best - runnerUp;
      pending[j] = j;
      upper += best;
      maxWeight[bestResource] = Math.max(maxWeight[bestResource], w[bestResource][j]);
      if (secondResource >= 0) maxWeight[secondResource] = Math.max(maxWeight[secondResource], w[secondResource][j]);
    }
    if (threshold > 0) threshold--;
    infinity = Math.max(infinity, upper);
    for (int j = 0; j < n; j++) if (penalty[j] == -1) penalty[j] = infinity;
    int value = 0, count = n;
    int[] assignment = empty(n);
    while (count > 0) {
      int selected = 0;
      for (int k = 1; k < count; k++) if (penalty[pending[k]] > penalty[pending[selected]]) selected = k;
      int task = pending[selected], resource = first[task];
      value += p[resource][task];
      assignment[task] = resource;
      pending[selected] = pending[--count];
      if (count == 0) break;
      remaining[resource] -= w[resource][task];
      if (maxWeight[resource] <= remaining[resource]) continue;
      for (int k = 0; k < count; k++) {
        int j = pending[k];
        if (w[resource][j] <= remaining[resource]) continue;
        if (first[j] == resource) {
          if (penalty[j] == infinity) return new Initial(threshold, assignment, upper, threshold, infinity);
          first[j] = second[j];
        } else if (second[j] != resource) continue;
        int best = 0, secondResource = -1;
        for (int i = 0; i < m; i++) {
          if (i != first[j] && w[i][j] <= remaining[i] && p[i][j] > best) {
            best = p[i][j]; secondResource = i;
          }
        }
        if (secondResource < 0) penalty[j] = infinity;
        else {
          second[j] = secondResource;
          penalty[j] = p[first[j]][j] - best;
          maxWeight[secondResource] = Math.max(maxWeight[secondResource], w[secondResource][j]);
        }
      }
    }
    return new Initial(value, assignment, upper, threshold, infinity);
  }

  private static Attempt alternative(int[][] p, int[][] w, int[] capacity, int infinity, float[] a) {
    int m = capacity.length, n = p[0].length;
    int[] remaining = capacity.clone(), maxWeight = new int[m], minWeight = new int[m], reserve = new int[m];
    int[][] adjusted = new int[m][n];
    for (int i = 0; i < m; i++) {
      adjusted[i] = w[i].clone();
      minWeight[i] = infinity;
      for (int j = 0; j < n; j++) minWeight[i] = Math.min(minWeight[i], adjusted[i][j]);
      reserve[i] = remaining[i] - minWeight[i];
    }
    int[] first = empty(n), second = empty(n), pending = new int[n], assignment = empty(n);
    float[] penalty = new float[n];
    for (int j = 0; j < n; j++) {
      if (!updateOptions(p, adjusted, remaining, reserve, maxWeight, first, second, penalty, j, infinity, a)) {
        return new Attempt(0, assignment);
      }
      pending[j] = j;
    }
    int count = n, value = 0;
    while (count > 0) {
      int selected = 0;
      for (int k = 1; k < count; k++) if (penalty[pending[k]] > penalty[pending[selected]]) selected = k;
      int task = pending[selected], resource = first[task];
      value += p[resource][task];
      assignment[task] = resource;
      pending[selected] = pending[--count];
      remaining[resource] -= w[resource][task];
      if (count == 0) break;
      boolean[] changed = new boolean[m];
      boolean any = false;
      for (int i = 0; i < m; i++) {
        if (adjusted[i][task] <= minWeight[i]) {
          minWeight[i] = infinity;
          for (int k = 0; k < count; k++) minWeight[i] = Math.min(minWeight[i], adjusted[i][pending[k]]);
          if ((long) minWeight[i] + maxWeight[i] > remaining[i]) { changed[i] = true; any = true; }
        }
        reserve[i] = remaining[i] - minWeight[i];
      }
      if (maxWeight[resource] > remaining[resource]) { changed[resource] = true; any = true; }
      if (!any) continue;
      for (int k = 0; k < count; k++) {
        int j = pending[k], best = first[j];
        if (penalty[j] >= infinity) {
          if (adjusted[best][j] > remaining[best]) return new Attempt(0, assignment);
          continue;
        }
        boolean update = changed[best] && adjusted[best][j] > reserve[best];
        int next = second[j];
        if (!update && next >= 0 && changed[next] && adjusted[next][j] > reserve[next]) update = true;
        if (update && !updateOptions(p, adjusted, remaining, reserve, maxWeight, first, second, penalty, j, infinity, a)) {
          return new Attempt(0, assignment);
        }
      }
    }
    for (int j = 0; j < n; j++) {
      int old = assignment[j], best = old, bestProfit = p[old][j];
      for (int i = 0; i < m; i++) {
        if (w[i][j] <= remaining[i] && p[i][j] > bestProfit) { best = i; bestProfit = p[i][j]; }
      }
      if (best != old) {
        assignment[j] = best;
        value += p[best][j] - p[old][j];
        remaining[old] += w[old][j];
        remaining[best] -= w[best][j];
      }
    }
    return new Attempt(value, assignment);
  }

  private static boolean updateOptions(int[][] p, int[][] weight, int[] remaining, int[] reserve,
                                       int[] maxWeight, int[] first, int[] second, float[] penalty,
                                       int task, int infinity, float[] a) {
    float best = -infinity, runnerUp = -infinity;
    int bestResource = -1, secondResource = -1;
    for (int i = 0; i < remaining.length; i++) {
      if (weight[i][task] > remaining[i]) continue;
      if (weight[i][task] > reserve[i]) weight[i][task] = remaining[i];
      float w = weight[i][task], value = p[i][task], room = remaining[i];
      float score = (-a[0] * w + a[1] * value) / (a[2] * room + a[3] * w + a[4]);
      if (runnerUp >= score) continue;
      if (best >= score) { runnerUp = score; secondResource = i; }
      else { runnerUp = best; secondResource = bestResource; best = score; bestResource = i; }
    }
    if (bestResource < 0) return false;
    first[task] = bestResource;
    second[task] = secondResource;
    penalty[task] = runnerUp > -infinity ? best - runnerUp : infinity;
    maxWeight[bestResource] = Math.max(maxWeight[bestResource], weight[bestResource][task]);
    if (secondResource >= 0) maxWeight[secondResource] = Math.max(maxWeight[secondResource], weight[secondResource][task]);
    return true;
  }

  private static int[] empty(int size) { int[] result = new int[size]; Arrays.fill(result, -1); return result; }
}
