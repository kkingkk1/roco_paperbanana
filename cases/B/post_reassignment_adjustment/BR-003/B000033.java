package method.cases.examples;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;
import method.runtime.Budget;
import method.runtime.ProblemAccess;
import method.runtime.SolverContracts.LocalContext;
import method.runtime.SolverContracts.LocalEvaluator;
import method.runtime.SolverContracts.LocalPlan;
import method.runtime.SolverContracts.LocalTrial;
import method.runtime.SolverContracts.Phase;
import method.runtime.SolverContracts.SatelliteView;
import model.ProblemInstance;

/** 针对重分配中新入任务或断边的三任务循环修补。 */
public final class B000033 {
  private B000033() { }

  public static LocalTrial search(ProblemInstance problem, ProblemAccess api, SatelliteView view,
      LocalContext context, LocalEvaluator evaluator, Budget budget, RandomGenerator rng, int maxEvaluations) {
    if (maxEvaluations < 1) throw new IllegalArgumentException("maxEvaluations must be positive");
    if (context.phase() != Phase.REASSIGN) throw new IllegalArgumentException("reassignment phase required");
    List<Integer> existing = view.observations().stream()
        .sorted(Comparator.comparingDouble(item -> item.getStartTime()))
        .map(item -> item.getTaskId()).toList();
    Set<Integer> present = Set.copyOf(existing);
    List<Integer> incoming = context.incoming().stream()
        .filter(view.assignedTaskIds()::contains).filter(id -> !present.contains(id))
        .sorted(Comparator.comparingInt((Integer id) -> problem.task(id).getPriority()).reversed()
            .thenComparingInt(Integer::intValue)).toList();
    Set<Integer> brokenEndpoints = new HashSet<>();
    context.brokenEdges().forEach(edge -> {
      brokenEndpoints.add(edge.previousTaskId());
      brokenEndpoints.add(edge.nextTaskId());
    });
    if (incoming.isEmpty() && brokenEndpoints.isEmpty()) return null;

    LocalTrial best = null;
    int evaluations = 0;
    for (int i = 0; i + 2 < existing.size() && evaluations < maxEvaluations; i++) {
      int a = existing.get(i), b = existing.get(i + 1), c = existing.get(i + 2);
      if (!brokenEndpoints.isEmpty() && !brokenEndpoints.contains(a)
          && !brokenEndpoints.contains(b) && !brokenEndpoints.contains(c)) continue;
      for (List<Integer> rotation : List.of(List.of(c, a, b), List.of(b, c, a))) {
        if (evaluations >= maxEvaluations) break;
        budget.check();
        List<Integer> order = new ArrayList<>(incoming);
        order.addAll(rotation);
        LocalTrial trial = evaluator.evaluate(new LocalPlan(view.hash(), Set.of(a, b, c), order));
        evaluations++;
        if (trial.totalProfit() != null && (best == null || trial.totalProfit() > best.totalProfit())) best = trial;
      }
    }
    return best;
  }
}
