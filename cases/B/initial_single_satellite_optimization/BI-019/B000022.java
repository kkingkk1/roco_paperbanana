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

public final class B000022 {
  private B000022() { }

  public static InitialTrial search(ProblemInstance problem, ProblemAccess api, SatelliteView view,
      InitialEvaluator evaluator, Budget budget, RandomGenerator rng, int maxEvaluations) {
    if (maxEvaluations < 1) throw new IllegalArgumentException("maxEvaluations must be positive");
    List<Integer> order = view.assignedTaskIds().stream().sorted().toList();
    budget.check();
    InitialTrial best = evaluator.evaluate(order);
    int evaluations = 1;
    if (order.size() < 2) return best;
    InitialTrial current = best;
    while (evaluations < maxEvaluations) {
      boolean improved = false;
      outer: for (int i = 0; i < order.size(); i++) {
        for (int j = i + 1; j < order.size() && evaluations < maxEvaluations; j++) {
          budget.check();
          List<Integer> next = new ArrayList<>(order);
          Collections.swap(next, i, j);
          InitialTrial trial = evaluator.evaluate(next); evaluations++;
          if (trial.totalProfit() > current.totalProfit()) {
            current = trial; order = next; improved = true;
            if (trial.totalProfit() > best.totalProfit()) best = trial;
            break outer;
          }
        }
      }
      if (!improved && evaluations < maxEvaluations) {
        budget.check();
        int left = rng.nextInt(order.size());
        int right = rng.nextInt(order.size() - 1);
        if (right >= left) right++;
        if (left > right) { int temp = left; left = right; right = temp; }
        List<Integer> next = new ArrayList<>(order);
        Collections.reverse(next.subList(left, right + 1));
        current = evaluator.evaluate(next); evaluations++; order = next;
        if (current.totalProfit() > best.totalProfit()) best = current;
      }
    }
    return best;
  }
}
