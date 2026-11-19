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
import method.runtime.SolverContracts.LocalTrial;
import method.runtime.SolverContracts.SatelliteView;
import model.ProblemInstance;
import model.ScheduledTask;

/** Project adaptation of RKHMA's affected-satellite insertion and local refinement. */
public final class B000043 {
  private B000043() { }

  public static LocalTrial search(ProblemInstance problem, ProblemAccess api, SatelliteView view,
      LocalContext context, LocalEvaluator evaluator, Budget budget, RandomGenerator rng, int maxEvaluations) {
    if (maxEvaluations < 1) throw new IllegalArgumentException("maxEvaluations must be positive");
    Set<Integer> assigned = view.assignedTaskIds();
    List<Integer> base = new ArrayList<>();
    Set<Integer> included = new HashSet<>();
    for (ScheduledTask observation : view.observations().stream()
        .sorted(Comparator.comparingDouble(ScheduledTask::getStartTime)).toList()) {
      budget.check();
      if (assigned.contains(observation.getTaskId()) && included.add(observation.getTaskId())) {
        base.add(observation.getTaskId());
      }
    }
    List<Integer> pending = assigned.stream().filter(id -> !included.contains(id))
        .sorted(Comparator.comparing((Integer id) -> !context.incoming().contains(id))
            .thenComparing(Comparator.comparingInt((Integer id) -> problem.task(id).getPriority()).reversed())
            .thenComparingInt(Integer::intValue)).toList();
    boolean hasExistingOrder = !base.isEmpty();
    base.addAll(pending);
    budget.check();
    LocalTrial best = evaluator.rebuild(base);
    if (best.totalProfit() == null) best = null;
    int evaluations = 1;
    if (base.size() < 2 || evaluations >= maxEvaluations) return best;

    if (!hasExistingOrder) {
      List<List<Integer>> population = new ArrayList<>();
      population.add(List.copyOf(base));
      while (evaluations < maxEvaluations) {
        budget.check();
        List<Integer> candidate = new ArrayList<>(population.get(rng.nextInt(population.size())));
        int first = rng.nextInt(candidate.size());
        int second = rng.nextInt(candidate.size() - 1);
        if (second >= first) second++;
        java.util.Collections.swap(candidate, first, second);
        LocalTrial trial = evaluator.rebuild(candidate);
        evaluations++;
        if (trial.totalProfit() != null && (best == null || trial.totalProfit() > best.totalProfit())) {
          best = trial;
          population.add(List.copyOf(candidate));
          if (population.size() > 6) population.remove(0);
        }
      }
      return best;
    }

    List<Integer> working = new ArrayList<>(base);
    for (int task : pending) {
      for (int position = 0; position < working.size() && evaluations < maxEvaluations; position++) {
        budget.check();
        List<Integer> candidate = new ArrayList<>(working);
        candidate.remove(Integer.valueOf(task));
        candidate.add(position, task);
        LocalTrial trial = evaluator.rebuild(candidate);
        evaluations++;
        if (trial.totalProfit() != null && (best == null || trial.totalProfit() > best.totalProfit())) {
          best = trial;
          working = candidate;
        }
      }
      if (evaluations >= maxEvaluations) break;
    }
    for (int i = 0; i + 1 < working.size() && evaluations < maxEvaluations; i++) {
      budget.check();
      List<Integer> candidate = new ArrayList<>(working);
      java.util.Collections.swap(candidate, i, i + 1);
      LocalTrial trial = evaluator.rebuild(candidate);
      evaluations++;
      if (trial.totalProfit() != null && (best == null || trial.totalProfit() > best.totalProfit())) {
        best = trial;
        working = candidate;
      }
    }
    return best;
  }
}
