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

public final class B000019 {
  private B000019() { }

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
      for (int from = 0; from < order.size(); from++) {
        for (int to = 0; to < order.size() && evaluations < maxEvaluations; to++) {
          if (from == to) continue;
          budget.check();
          List<Integer> next = new ArrayList<>(order);
          int task = next.remove(from); next.add(to, task);
          InitialTrial trial = evaluator.evaluate(next); evaluations++;
          if (trial.totalProfit() > passBest.totalProfit()) { passBest = trial; passOrder = next; }
        }
      }
      if (passBest == best) break;
      best = passBest; order = passOrder;
    }
    return best;
  }
}
