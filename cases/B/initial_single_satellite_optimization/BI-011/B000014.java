package method.cases.examples;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.random.RandomGenerator;
import method.runtime.Budget;
import method.runtime.ProblemAccess;
import method.runtime.SolverContracts.InitialEvaluator;
import method.runtime.SolverContracts.InitialTrial;
import method.runtime.SolverContracts.SatelliteView;
import model.ProblemInstance;

/** Single-satellite initialization with late acceptance. */
public final class B000014 {
  private B000014() { }

  public static InitialTrial search(ProblemInstance problem, ProblemAccess api, SatelliteView view,
      InitialEvaluator evaluator, Budget budget, RandomGenerator rng, int maxEvaluations) {
    if (maxEvaluations < 1) throw new IllegalArgumentException("maxEvaluations must be positive");
    List<Integer> order = view.assignedTaskIds().stream().sorted().toList();
    budget.check();
    InitialTrial best = evaluator.evaluate(order);
    InitialTrial current = best;
    long[] history = new long[5];
    Arrays.fill(history, current.totalProfit());
    int evaluations = 1;
    while (evaluations < maxEvaluations && order.size() >= 2) {
      budget.check();
      int first = rng.nextInt(order.size());
      int second = rng.nextInt(order.size() - 1);
      if (second >= first) second++;
      List<Integer> next = new ArrayList<>(order);
      int value = next.get(first); next.set(first, next.get(second)); next.set(second, value);
      InitialTrial trial = evaluator.evaluate(next);
      int slot = (evaluations - 1) % history.length;
      evaluations++;
      if (trial.totalProfit() >= current.totalProfit() || trial.totalProfit() >= history[slot]) {
        current = trial; order = next;
      }
      history[slot] = current.totalProfit();
      if (trial.totalProfit() > best.totalProfit()) best = trial;
    }
    return best;
  }
}
