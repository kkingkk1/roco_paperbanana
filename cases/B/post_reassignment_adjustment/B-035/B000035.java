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

public final class B000035 {
  private B000035() { }

  public static LocalTrial search(ProblemInstance problem, ProblemAccess api, SatelliteView view,
      LocalContext context, LocalEvaluator evaluator, Budget budget, RandomGenerator rng, int maxEvaluations) {
    if (maxEvaluations < 1) throw new IllegalArgumentException("maxEvaluations must be positive");
    Set<Integer> present = new HashSet<>();
    view.observations().forEach(item -> present.add(item.getTaskId()));
    List<Integer> pending = view.assignedTaskIds().stream().filter(id -> !present.contains(id))
        .sorted(Comparator.comparingInt((Integer id) -> problem.task(id).getPriority()).reversed()
            .thenComparingInt(Integer::intValue)).toList();
    if (context.brokenEdges().isEmpty()) return null;
    LocalTrial best = null;
    for (int policy = 0; policy < 2 && policy < maxEvaluations; policy++) {
      Set<Integer> released = new LinkedHashSet<>();
      for (BrokenEdge edge : context.brokenEdges()) {
        budget.check();
        int a = edge.previousTaskId(), b = edge.nextTaskId();
        int first = problem.task(a).getPriority(), second = problem.task(b).getPriority();
        released.add(policy == 0 ? (first <= second ? a : b) : (first <= second ? b : a));
      }
      List<Integer> order = new ArrayList<>();
      pending.stream().filter(context.incoming()::contains).forEach(order::add);
      order.addAll(released);
      budget.check();
      LocalTrial trial = evaluator.evaluate(new LocalPlan(view.hash(), released, order));
      if (trial.totalProfit() != null && (best == null || trial.totalProfit() > best.totalProfit())) best = trial;
    }
    return best;
  }
}
