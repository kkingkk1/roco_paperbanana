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

public final class B000010 {
  private B000010() { }

  public static InitialTrial search(ProblemInstance problem, ProblemAccess api, SatelliteView view,
      InitialEvaluator evaluator, Budget budget, RandomGenerator rng, int maxEvaluations) {
    if (maxEvaluations < 1) throw new IllegalArgumentException("maxEvaluations must be positive");
    List<Integer> order = view.assignedTaskIds().stream().sorted().toList();
    budget.check();
    InitialTrial best = evaluator.evaluate(order);
    int evaluations = 1;
    int neighborhood = 0;
    while (neighborhood < 3 && evaluations < maxEvaluations) {
      boolean improved = false;
      outer: for (int i = 0; i < order.size(); i++) {
        for (int j = 0; j < order.size() && evaluations < maxEvaluations; j++) {
          if (i == j || (neighborhood != 1 && j < i)) continue;
          budget.check();
          List<Integer> next = new ArrayList<>(order);
          if (neighborhood == 0) {
            int value = next.get(i); next.set(i, next.get(j)); next.set(j, value);
          } else if (neighborhood == 1) {
            int task = next.remove(i); next.add(j, task);
          } else {
            java.util.Collections.reverse(next.subList(i, j + 1));
          }
          InitialTrial trial = evaluator.evaluate(next); evaluations++;
          if (trial.totalProfit() > best.totalProfit()) {
            best = trial; order = List.copyOf(next); improved = true; break outer;
          }
        }
      }
      neighborhood = improved ? 0 : neighborhood + 1;
    }
    return best;
  }
}
