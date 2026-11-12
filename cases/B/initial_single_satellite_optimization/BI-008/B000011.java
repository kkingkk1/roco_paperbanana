package method.cases.examples;

import java.util.ArrayList;
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

public final class B000011 {
  private B000011() { }

  public static InitialTrial search(ProblemInstance problem, ProblemAccess api, SatelliteView view,
      InitialEvaluator evaluator, Budget budget, RandomGenerator rng, int maxEvaluations) {
    if (maxEvaluations < 1) throw new IllegalArgumentException("maxEvaluations must be positive");
    List<Integer> order = view.assignedTaskIds().stream().sorted().toList();
    budget.check();
    InitialTrial best = evaluator.evaluate(order);
    int evaluations = 1;
    InitialTrial current = best;
    double temperature = 10.0;
    while (evaluations < maxEvaluations && order.size() >= 2) {
      budget.check();
      int from = rng.nextInt(order.size());
      int to = rng.nextInt(order.size() - 1);
      if (to >= from) to++;
      List<Integer> next = new ArrayList<>(order);
      int task = next.remove(from); next.add(to, task);
      InitialTrial trial = evaluator.evaluate(next); evaluations++;
      double delta = (double) trial.totalProfit() - current.totalProfit();
      if (delta >= 0 || rng.nextDouble() < Math.exp(delta / Math.max(temperature, 1e-12))) {
        current = trial; order = List.copyOf(next);
      }
      if (trial.totalProfit() > best.totalProfit()) best = trial;
      temperature *= 0.99;
    }
    return best;
  }
}
