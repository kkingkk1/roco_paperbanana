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

public final class B000034 {
  private B000034() { }

  public static LocalTrial search(ProblemInstance problem, ProblemAccess api, SatelliteView view,
      LocalContext context, LocalEvaluator evaluator, Budget budget, RandomGenerator rng, int maxEvaluations) {
    if (maxEvaluations < 1) throw new IllegalArgumentException("maxEvaluations must be positive");
    Set<Integer> present = new HashSet<>();
    view.observations().forEach(item -> present.add(item.getTaskId()));
    List<Integer> pending = view.assignedTaskIds().stream().filter(id -> !present.contains(id))
        .sorted(Comparator.comparingInt((Integer id) -> problem.task(id).getPriority()).reversed()
            .thenComparingInt(Integer::intValue)).toList();
    List<Integer> removed = present.stream()
        .sorted(Comparator.comparingInt((Integer id) -> problem.task(id).getPriority())
            .thenComparingInt(Integer::intValue)).limit(2).toList();
    List<Integer> order = new ArrayList<>();
    pending.stream().filter(context.incoming()::contains).forEach(order::add);
    pending.stream().filter(id -> !context.incoming().contains(id)).forEach(order::add);
    removed.stream().sorted(Comparator.comparingInt((Integer id) -> problem.task(id).getPriority()).reversed()
        .thenComparingInt(Integer::intValue)).forEach(order::add);
    if (removed.isEmpty() && order.isEmpty()) return null;
    budget.check();
    LocalTrial trial = evaluator.evaluate(new LocalPlan(view.hash(), Set.copyOf(removed), order));
    return trial.totalProfit() == null ? null : trial;
  }
}
