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

public final class B000032 {
  private B000032() { }

  public static LocalTrial search(ProblemInstance problem, ProblemAccess api, SatelliteView view,
      LocalContext context, LocalEvaluator evaluator, Budget budget, RandomGenerator rng, int maxEvaluations) {
    if (maxEvaluations < 1) throw new IllegalArgumentException("maxEvaluations must be positive");
    Set<Integer> present = new HashSet<>();
    view.observations().forEach(item -> present.add(item.getTaskId()));
    List<Integer> pending = view.assignedTaskIds().stream().filter(id -> !present.contains(id))
        .sorted(Comparator.comparingInt((Integer id) -> problem.task(id).getPriority()).reversed()
            .thenComparingInt(Integer::intValue)).toList();
    List<Integer> existing = present.stream().sorted().toList();
    LocalTrial best = null;
    int evaluations = 0;
    for (int task : pending) {
      for (int i = 0; i < existing.size(); i++) {
        for (int j = i + 1; j < existing.size() && evaluations < maxEvaluations; j++) {
          budget.check();
          int first = existing.get(i), second = existing.get(j);
          LocalTrial trial = evaluator.evaluate(new LocalPlan(view.hash(), Set.of(first, second),
              List.of(task, first, second))); evaluations++;
          if (trial.totalProfit() != null && (best == null || trial.totalProfit() > best.totalProfit())) best = trial;
        }
      }
      if (evaluations >= maxEvaluations) break;
    }
    return best;
  }
}
