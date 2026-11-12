package method.cases.examples;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;
import method.runtime.Budget;
import method.runtime.ProblemAccess;
import method.runtime.SolverContracts.InitialEvaluator;
import method.runtime.SolverContracts.InitialTrial;
import method.runtime.SolverContracts.SatelliteView;
import model.ProblemInstance;

/** Enumerates task orders up to a caller-specified evaluation budget. */
public final class B000016 {
  private B000016() { }

  public static InitialTrial search(ProblemInstance problem, ProblemAccess api, SatelliteView view,
      InitialEvaluator evaluator, Budget budget, RandomGenerator rng, int maxEvaluations) {
    if (maxEvaluations < 1) throw new IllegalArgumentException("maxEvaluations must be positive");
    List<Integer> tasks = view.assignedTaskIds().stream().sorted().toList();
    budget.check();
    InitialTrial first = evaluator.evaluate(tasks);
    Search search = new Search(evaluator, budget, maxEvaluations, first, tasks);
    search.visit(new ArrayList<>(), new ArrayList<>(tasks));
    return search.best;
  }

  private static final class Search {
    private final InitialEvaluator evaluator;
    private final Budget budget;
    private final int limit;
    private final List<Integer> firstOrder;
    private int evaluations = 1;
    private InitialTrial best;

    private Search(InitialEvaluator evaluator, Budget budget, int limit, InitialTrial first,
        List<Integer> firstOrder) {
      this.evaluator = evaluator; this.budget = budget; this.limit = limit;
      this.best = first; this.firstOrder = firstOrder;
    }

    private void visit(List<Integer> prefix, List<Integer> remaining) {
      budget.check();
      if (evaluations >= limit) return;
      if (remaining.isEmpty()) {
        if (prefix.equals(firstOrder)) return;
        InitialTrial trial = evaluator.evaluate(List.copyOf(prefix)); evaluations++;
        if (trial.totalProfit() > best.totalProfit()) best = trial;
        return;
      }
      for (int i = 0; i < remaining.size() && evaluations < limit; i++) {
        int task = remaining.remove(i);
        prefix.add(task);
        visit(prefix, remaining);
        prefix.remove(prefix.size() - 1);
        remaining.add(i, task);
      }
    }
  }
}
