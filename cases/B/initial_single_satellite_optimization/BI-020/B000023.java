package method.cases.examples;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;
import method.runtime.Budget;
import method.runtime.ProblemAccess;
import method.runtime.SolverContracts.InitialEvaluator;
import method.runtime.SolverContracts.InitialTrial;
import method.runtime.SolverContracts.SatelliteView;
import model.ProblemInstance;

public final class B000023 {
  private B000023() { }

  public static InitialTrial search(ProblemInstance problem, ProblemAccess api, SatelliteView view,
      InitialEvaluator evaluator, Budget budget, RandomGenerator rng, int maxEvaluations) {
    if (maxEvaluations < 1) throw new IllegalArgumentException("maxEvaluations must be positive");
    List<Integer> order = view.assignedTaskIds().stream().sorted().toList();
    budget.check();
    InitialTrial best = evaluator.evaluate(order);
    int evaluations = 1;
    int n = order.size();
    if (n == 0) return best;
    List<Integer> tasks = order;
    double[][] pheromone = new double[n + 1][n];
    for (double[] row : pheromone) java.util.Arrays.fill(row, 1.0);
    while (evaluations < maxEvaluations) {
      budget.check();
      boolean[] used = new boolean[n];
      List<Integer> next = new ArrayList<>();
      int previous = n;
      for (int step = 0; step < n; step++) {
        budget.check();
        double total = 0;
        for (int i = 0; i < n; i++) if (!used[i]) total += pheromone[previous][i];
        double pick = rng.nextDouble() * total;
        int selected = -1;
        for (int i = 0; i < n; i++) if (!used[i]) {
          selected = i;
          pick -= pheromone[previous][i];
          if (pick <= 0) break;
        }
        used[selected] = true; next.add(tasks.get(selected)); previous = selected;
      }
      InitialTrial trial = evaluator.evaluate(next); evaluations++;
      if (trial.totalProfit() > best.totalProfit()) best = trial;
      for (double[] row : pheromone) for (int i = 0; i < n; i++) row[i] = Math.max(1e-12, row[i] * .9);
      previous = n;
      for (int task : best.priorities()) {
        int index = tasks.indexOf(task);
        pheromone[previous][index] += 1.0; previous = index;
      }
    }
    return best;
  }
}
