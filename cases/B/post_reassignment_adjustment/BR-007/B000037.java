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

public final class B000037 {
  private B000037() { }

  public static LocalTrial search(ProblemInstance problem, ProblemAccess api, SatelliteView view,
      LocalContext context, LocalEvaluator evaluator, Budget budget, RandomGenerator rng, int maxEvaluations) {
    if (maxEvaluations < 1) throw new IllegalArgumentException("maxEvaluations must be positive");
    Map<Integer, Long> counts = new HashMap<>();
    for (int task : view.assignedTaskIds()) {
      budget.check(); counts.put(task, api.candidateCount(task, view.satelliteId(), budget));
    }
    List<Integer> scarcity = view.assignedTaskIds().stream()
        .sorted(Comparator.comparingLong((Integer id) -> counts.get(id))
            .thenComparing(Comparator.comparingInt((Integer id) -> problem.task(id).getPriority()).reversed())
            .thenComparingInt(Integer::intValue)).toList();
    budget.check();
    LocalTrial first = evaluator.rebuild(scarcity);
    if (maxEvaluations == 1) return first;
    List<Integer> density = view.assignedTaskIds().stream()
        .sorted(Comparator.comparingDouble((Integer id) ->
            (double) problem.task(id).getPriority() / problem.task(id).getImagingDuration()).reversed()
            .thenComparingInt(Integer::intValue)).toList();
    budget.check();
    LocalTrial second = evaluator.rebuild(density);
    if (first.totalProfit() == null) return second.totalProfit() == null ? null : second;
    return second.totalProfit() != null && second.totalProfit() > first.totalProfit() ? second : first;
  }
}
