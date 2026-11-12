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

public final class B000012 {
  private B000012() { }

  public static InitialTrial search(ProblemInstance problem, ProblemAccess api, SatelliteView view,
      InitialEvaluator evaluator, Budget budget, RandomGenerator rng, int maxEvaluations) {
    if (maxEvaluations < 1) throw new IllegalArgumentException("maxEvaluations must be positive");
    List<Integer> order = view.assignedTaskIds().stream().sorted().toList();
    budget.check();
    InitialTrial best = evaluator.evaluate(order);
    int evaluations = 1;
    if (order.size() < 2) return best;
    while (evaluations < maxEvaluations) {
      List<Integer> restart = new ArrayList<>(order);
      for (int i = restart.size() - 1; i > 0; i--) {
        int j = rng.nextInt(i + 1);
        int value = restart.get(i); restart.set(i, restart.get(j)); restart.set(j, value);
      }
      budget.check();
      InitialTrial current = evaluator.evaluate(restart); evaluations++;
      if (current.totalProfit() > best.totalProfit()) best = current;
      boolean improved = true;
      while (improved && evaluations < maxEvaluations) {
        improved = false;
        outer: for (int i = 0; i < restart.size(); i++) {
          for (int j = i + 1; j < restart.size() && evaluations < maxEvaluations; j++) {
            budget.check();
            List<Integer> next = new ArrayList<>(restart);
            int value = next.get(i); next.set(i, next.get(j)); next.set(j, value);
            InitialTrial trial = evaluator.evaluate(next); evaluations++;
            if (trial.totalProfit() > current.totalProfit()) {
              current = trial; restart = next; improved = true;
              if (trial.totalProfit() > best.totalProfit()) best = trial;
              break outer;
            }
          }
        }
      }
    }
    return best;
  }
}
