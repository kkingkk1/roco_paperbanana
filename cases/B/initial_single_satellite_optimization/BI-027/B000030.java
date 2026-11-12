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

public final class B000030 {
  private B000030() { }

  public static InitialTrial search(ProblemInstance problem, ProblemAccess api, SatelliteView view,
      InitialEvaluator evaluator, Budget budget, RandomGenerator rng, int maxEvaluations) {
    if (maxEvaluations < 1) throw new IllegalArgumentException("maxEvaluations must be positive");
    Map<Integer, double[]> envelopes = new HashMap<>();
    for (int task : view.assignedTaskIds()) {
      budget.check();
      double first = Double.POSITIVE_INFINITY, last = Double.NEGATIVE_INFINITY;
      for (var candidate : api.candidates(task, view.satelliteId(), budget)) {
        budget.check(); first = Math.min(first, candidate.getStartTime()); last = Math.max(last, candidate.getEndTime());
      }
      envelopes.put(task, new double[]{first, last});
    }
    Map<Integer, Double> scores = new HashMap<>();
    for (int task : view.assignedTaskIds()) {
      budget.check();
      int conflicts = 0;
      double[] current = envelopes.get(task);
      for (int other : view.assignedTaskIds()) {
        budget.check();
        double[] candidate = envelopes.get(other);
        if (other != task && current[0] < candidate[1] && candidate[0] < current[1]) conflicts++;
      }
      scores.put(task, (double) problem.task(task).getPriority() / (1 + conflicts));
    }
    List<Integer> order = view.assignedTaskIds().stream()
        .sorted(Comparator.comparingDouble((Integer id) -> scores.get(id)).reversed()
            .thenComparingInt(Integer::intValue)).toList();
    budget.check(); return evaluator.evaluate(order);
  }
}
