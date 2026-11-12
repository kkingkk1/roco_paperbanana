package method.cases.examples;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.random.RandomGenerator;
import method.runtime.Budget;
import method.runtime.ProblemAccess;
import method.runtime.SolverContracts.BrokenEdge;
import method.runtime.SolverContracts.LocalContext;
import method.runtime.SolverContracts.LocalEvaluator;
import method.runtime.SolverContracts.LocalPlan;
import method.runtime.SolverContracts.LocalTrial;
import method.runtime.SolverContracts.SatelliteView;
import model.ProblemInstance;

public final class B000033 {
  private B000033() { }

  public static LocalTrial search(ProblemInstance problem, ProblemAccess api, SatelliteView view,
      LocalContext context, LocalEvaluator evaluator, Budget budget, RandomGenerator rng, int maxEvaluations) {
    if (maxEvaluations < 1) throw new IllegalArgumentException("maxEvaluations must be positive");
    List<Integer> existing = view.observations().stream()
        .sorted(Comparator.comparingDouble(item -> item.getStartTime()))
        .map(item -> item.getTaskId()).toList();
    LocalTrial best = null;
    int evaluations = 0;
    for (int i = 0; i + 2 < existing.size() && evaluations < maxEvaluations; i++) {
      int a = existing.get(i), b = existing.get(i + 1), c = existing.get(i + 2);
      for (List<Integer> sequence : List.of(List.of(c, a, b), List.of(b, c, a))) {
        if (evaluations >= maxEvaluations) break;
        budget.check();
        LocalTrial trial = evaluator.evaluate(new LocalPlan(view.hash(), Set.of(a, b, c), sequence)); evaluations++;
        if (trial.totalProfit() != null && (best == null || trial.totalProfit() > best.totalProfit())) best = trial;
      }
    }
    return best;
  }
}
