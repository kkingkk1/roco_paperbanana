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

public final class B000021 {
  private B000021() { }

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
      for (int i = 0; i + 1 < order.size() && evaluations < maxEvaluations; i++) {
        budget.check();
        List<Integer> next = new ArrayList<>(order);
        Collections.swap(next, i, i + 1);
        InitialTrial trial = evaluator.evaluate(next); evaluations++;
        if (trial.totalProfit() > best.totalProfit()) {
          best = trial; order = next; improved = true; break;
        }
      }
    }
    return best;
  }
}
