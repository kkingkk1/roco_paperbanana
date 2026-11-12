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

public final class B000007 {
  private B000007() { }

  public static InitialTrial search(ProblemInstance problem, ProblemAccess api, SatelliteView view,
      InitialEvaluator evaluator, Budget budget, RandomGenerator rng, int maxEvaluations) {
    if (maxEvaluations < 1) throw new IllegalArgumentException("maxEvaluations must be positive");
    List<Integer> order = view.assignedTaskIds().stream().sorted().toList();
    budget.check();
    InitialTrial best = evaluator.evaluate(order);
    int evaluations = 1;
    while (evaluations < maxEvaluations) {
      InitialTrial passBest = best;
      List<Integer> passOrder = order;
      for (int i = 0; i < order.size(); i++) {
        for (int j = i + 1; j < order.size() && evaluations < maxEvaluations; j++) {
          budget.check();
          List<Integer> next = new ArrayList<>(order);
          int value = next.get(i); next.set(i, next.get(j)); next.set(j, value);
          InitialTrial trial = evaluator.evaluate(next); evaluations++;
          if (trial.totalProfit() > passBest.totalProfit()) {
            passBest = trial; passOrder = List.copyOf(next);
          }
        }
      }
      if (passBest == best) break;
      best = passBest; order = passOrder;
    }
    return best;
  }
}
