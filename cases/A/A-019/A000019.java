package method.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Generalized-assignment branch and bound inspired by MTG.
 * Silvano Martello and Paolo Toth, Knapsack Problems: Algorithms and Computer
 * Implementations, Wiley, 1990. Source conditions are recorded with this case.
 */
public final class A000019 {
  private A000019() { }

  public record Assignment(int value, int[] resourceByTask, int bound, boolean optimal, long exploredNodes) { }

  /** maxNodes = -1 searches without a node limit; a nonnegative value limits visited nodes. */
  public static Assignment solve(int[][] profit, int[][] weight, int[] capacity, boolean minimize, long maxNodes) {
    Objects.requireNonNull(profit, "profit");
    Objects.requireNonNull(weight, "weight");
    Objects.requireNonNull(capacity, "capacity");
    if (maxNodes < -1) throw new IllegalArgumentException("maxNodes must be -1 or nonnegative");
    int m = capacity.length;
    if (m < 2 || m > 10 || profit.length != m || weight.length != m) {
      throw new IllegalArgumentException("the number of resources must be 2..10");
    }
    int n = Objects.requireNonNull(profit[0], "profit row").length;
    if (n < 2 || n > 100) throw new IllegalArgumentException("the number of tasks must be 2..100");
    int maxProfit = 0;
    for (int i = 0; i < m; i++) {
      if (capacity[i] <= 0 || Objects.requireNonNull(profit[i], "profit row").length != n ||
          Objects.requireNonNull(weight[i], "weight row").length != n) {
        throw new IllegalArgumentException("matrix dimensions and capacities must be valid");
      }
      boolean usable = false;
      for (int j = 0; j < n; j++) {
        if (profit[i][j] <= 0 || weight[i][j] <= 0) throw new IllegalArgumentException("values must be positive");
        maxProfit = Math.max(maxProfit, profit[i][j]);
        if (weight[i][j] <= capacity[i]) usable = true;
      }
      if (!usable) throw new IllegalArgumentException("every resource must fit at least one task");
    }
    if (((long) maxProfit + 1) * n > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("objective exceeds the source arithmetic range");
    }
    int transform = maxProfit + 1;
    int[][] adjusted = new int[m][n];
    for (int i = 0; i < m; i++) for (int j = 0; j < n; j++) {
      adjusted[i][j] = minimize ? transform - profit[i][j] : profit[i][j];
    }
    Search search = new Search(adjusted, weight, capacity.clone(), maxNodes);
    search.greedyIncumbent();
    double rootBound = search.upperBound(0);
    if (rootBound >= 0) search.visit(0, 0);
    boolean complete = !search.truncated;
    if (search.bestValue < 0 && complete) return new Assignment(0, empty(n), 0, true, search.visited);
    int value = search.bestValue < 0 ? 0 : minimize ? n * transform - search.bestValue : search.bestValue;
    int bound;
    if (complete && search.bestValue >= 0) bound = value;
    else if (rootBound < 0) bound = 0;
    else {
      int upper = (int) Math.floor(rootBound);
      bound = minimize ? n * transform - upper : upper;
    }
    return new Assignment(value, search.bestValue < 0 ? empty(n) : search.bestAssignment,
        bound, complete, search.visited);
  }

  private static final class Search {
    private final int[][] profit, weight;
    private final int[] remaining, assignment;
    private final long maxNodes;
    private int bestValue = -1;
    private int[] bestAssignment;
    private long visited;
    private boolean truncated;

    Search(int[][] profit, int[][] weight, int[] remaining, long maxNodes) {
      this.profit = profit;
      this.weight = weight;
      this.remaining = remaining;
      this.maxNodes = maxNodes;
      this.assignment = empty(profit[0].length);
    }

    void greedyIncumbent() {
      int[] localRemaining = remaining.clone(), localAssignment = empty(assignment.length);
      int total = 0;
      for (int count = 0; count < assignment.length; count++) {
        int task = chooseTask(localAssignment, localRemaining);
        if (task < 0) return;
        int best = -1;
        for (int i = 0; i < remaining.length; i++) if (weight[i][task] <= localRemaining[i] &&
            (best < 0 || profit[i][task] > profit[best][task])) best = i;
        if (best < 0) return;
        localAssignment[task] = best;
        localRemaining[best] -= weight[best][task];
        total += profit[best][task];
      }
      bestValue = total;
      bestAssignment = localAssignment;
    }

    void visit(int depth, int value) {
      if (maxNodes >= 0 && visited >= maxNodes) { truncated = true; return; }
      visited++;
      if (depth == assignment.length) {
        if (value > bestValue) { bestValue = value; bestAssignment = assignment.clone(); }
        return;
      }
      double upper = upperBound(value);
      if (upper < 0 || (bestValue >= 0 && upper < (double) bestValue + 1.0)) return;
      int task = chooseTask(assignment, remaining);
      List<Integer> options = new ArrayList<>();
      for (int i = 0; i < remaining.length; i++) if (weight[i][task] <= remaining[i]) options.add(i);
      options.sort((a, b) -> {
        int comparison = Integer.compare(profit[b][task], profit[a][task]);
        return comparison != 0 ? comparison : Integer.compare(a, b);
      });
      for (int resource : options) {
        assignment[task] = resource;
        remaining[resource] -= weight[resource][task];
        visit(depth + 1, value + profit[resource][task]);
        remaining[resource] += weight[resource][task];
        assignment[task] = -1;
        if (truncated) return;
      }
    }

    /** A fractional knapsack for each resource relaxes the one-resource-per-task constraint. */
    double upperBound(int current) {
      long perTask = current;
      for (int j = 0; j < assignment.length; j++) if (assignment[j] == -1) {
        int maximum = -1;
        for (int i = 0; i < remaining.length; i++) {
          if (weight[i][j] <= remaining[i]) maximum = Math.max(maximum, profit[i][j]);
        }
        if (maximum < 0) return -1;
        perTask += maximum;
      }
      double knapsack = current;
      for (int resource = 0; resource < remaining.length; resource++) {
        final int i = resource;
        List<Integer> tasks = new ArrayList<>();
        for (int j = 0; j < assignment.length; j++) {
          if (assignment[j] == -1 && weight[i][j] <= remaining[i]) tasks.add(j);
        }
        tasks.sort((a, b) -> {
          long left = (long) profit[i][a] * weight[i][b];
          long right = (long) profit[i][b] * weight[i][a];
          int comparison = Long.compare(right, left);
          return comparison != 0 ? comparison : Integer.compare(a, b);
        });
        int room = remaining[i];
        for (int task : tasks) {
          if (weight[i][task] <= room) {
            knapsack = Math.nextUp(knapsack + profit[i][task]);
            room -= weight[i][task];
          } else {
            double product = Math.nextUp((double) profit[i][task] * room);
            double fraction = Math.nextUp(product / weight[i][task]);
            knapsack = Math.nextUp(knapsack + fraction);
            break;
          }
        }
      }
      return Math.min(perTask, knapsack);
    }

    int chooseTask(int[] partial, int[] room) {
      int chosen = -1, fewest = Integer.MAX_VALUE, largestRegret = -1;
      for (int task = 0; task < partial.length; task++) if (partial[task] == -1) {
        int count = 0, best = 0, second = 0;
        for (int i = 0; i < room.length; i++) if (weight[i][task] <= room[i]) {
          count++;
          int value = profit[i][task];
          if (value > best) { second = best; best = value; }
          else if (value > second) second = value;
        }
        int regret = best - second;
        if (count < fewest || (count == fewest && regret > largestRegret)) {
          chosen = task; fewest = count; largestRegret = regret;
        }
      }
      return chosen;
    }
  }

  private static int[] empty(int size) { int[] result = new int[size]; Arrays.fill(result, -1); return result; }
}
