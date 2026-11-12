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

public final class B000026 {
  private B000026() { }

  public static InitialTrial search(ProblemInstance problem, ProblemAccess api, SatelliteView view,
      InitialEvaluator evaluator, Budget budget, RandomGenerator rng, int maxEvaluations) {
    if (maxEvaluations < 1) throw new IllegalArgumentException("maxEvaluations must be positive");
    Map<Integer, Double> latest = new HashMap<>();
    for (int task : view.assignedTaskIds()) {
      budget.check();
      double end = Double.NEGATIVE_INFINITY;
      for (var candidate : api.candidates(task, view.satelliteId(), budget)) {
        budget.check(); end = Math.max(end, candidate.getStartTime());
      }
      latest.put(task, end == Double.NEGATIVE_INFINITY ? Double.POSITIVE_INFINITY : end);
    }
    List<Integer> order = view.assignedTaskIds().stream()
        .sorted(Comparator.comparingDouble((Integer id) -> latest.get(id))
            .thenComparing(Comparator.comparingInt((Integer id) -> problem.task(id).getPriority()).reversed())
            .thenComparingInt(Integer::intValue)).toList();
    budget.check(); return evaluator.evaluate(order);
  }
}
