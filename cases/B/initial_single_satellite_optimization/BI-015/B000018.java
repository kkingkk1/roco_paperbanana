package method.cases.examples;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.random.RandomGenerator;
import method.runtime.Budget;
import method.runtime.ProblemAccess;
import method.runtime.SolverContracts.InitialEvaluator;
import method.runtime.SolverContracts.InitialTrial;
import method.runtime.SolverContracts.SatelliteView;
import model.ProblemInstance;

/** A single-parent random-key evolution strategy for task order. */
public final class B000018 {
  private B000018() { }

  public static InitialTrial search(ProblemInstance problem, ProblemAccess api, SatelliteView view,
      InitialEvaluator evaluator, Budget budget, RandomGenerator rng, int maxEvaluations) {
    if (maxEvaluations < 1) throw new IllegalArgumentException("maxEvaluations must be positive");
    List<Integer> tasks = view.assignedTaskIds().stream().sorted().toList();
    budget.check();
    InitialTrial best = evaluator.evaluate(tasks);
    InitialTrial current = best;
    if (tasks.size() < 2) return best;
    double[] keys = new double[tasks.size()];
    for (int i = 0; i < keys.length; i++) keys[i] = i;
    int evaluations = 1;
    while (evaluations < maxEvaluations) {
      budget.check();
      double[] candidate = keys.clone();
      for (int i = 0; i < candidate.length; i++) candidate[i] += rng.nextGaussian();
      List<Integer> indices = new ArrayList<>();
      for (int i = 0; i < candidate.length; i++) indices.add(i);
      indices.sort(Comparator.comparingDouble((Integer i) -> candidate[i]).thenComparingInt(i -> tasks.get(i)));
      List<Integer> order = indices.stream().map(tasks::get).toList();
      InitialTrial trial = evaluator.evaluate(order); evaluations++;
      if (trial.totalProfit() >= current.totalProfit()) { current = trial; keys = candidate; }
      if (trial.totalProfit() > best.totalProfit()) best = trial;
    }
    return best;
  }
}
