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

public final class B000036 {
  private B000036() { }

  public static LocalTrial search(ProblemInstance problem, ProblemAccess api, SatelliteView view,
      LocalContext context, LocalEvaluator evaluator, Budget budget, RandomGenerator rng, int maxEvaluations) {
    if (maxEvaluations < 1) throw new IllegalArgumentException("maxEvaluations must be positive");
    List<Integer> order = view.assignedTaskIds().stream()
        .sorted(Comparator.comparing((Integer id) -> !context.incoming().contains(id))
            .thenComparing(Comparator.comparingInt((Integer id) -> problem.task(id).getPriority()).reversed())
            .thenComparingInt(Integer::intValue)).toList();
    budget.check();
    return evaluator.rebuild(order);
  }
}
