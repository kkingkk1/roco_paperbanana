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

public final class B000009 {
  private B000009() { }

  public static InitialTrial search(ProblemInstance problem, ProblemAccess api, SatelliteView view,
      InitialEvaluator evaluator, Budget budget, RandomGenerator rng, int maxEvaluations) {
    if (maxEvaluations < 1) throw new IllegalArgumentException("maxEvaluations must be positive");
    List<Integer> order = view.assignedTaskIds().stream().sorted().toList();
    budget.check();
    InitialTrial best = evaluator.evaluate(order);
    int evaluations = 1;
    boolean improved = true;
    while (improved && evaluations < maxEvaluations) {
      improved = false;
      outer: for (int from = 0; from < order.size(); from++) {
        for (int to = from + 1; to < order.size() && evaluations < maxEvaluations; to++) {
          budget.check();
          List<Integer> next = new ArrayList<>(order);
          java.util.Collections.reverse(next.subList(from, to + 1));
          InitialTrial trial = evaluator.evaluate(next); evaluations++;
          if (trial.totalProfit() > best.totalProfit()) {
            best = trial; order = List.copyOf(next); improved = true; break outer;
          }
        }
      }
    }
    return best;
  }
}
