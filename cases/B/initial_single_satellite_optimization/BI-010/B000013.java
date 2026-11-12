package method.cases.examples;

import java.util.ArrayList;
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

/** Single-satellite initialization with tabu pairwise exchanges. */
public final class B000013 {
  private B000013() { }

  public static InitialTrial search(ProblemInstance problem, ProblemAccess api, SatelliteView view,
      InitialEvaluator evaluator, Budget budget, RandomGenerator rng, int maxEvaluations) {
    if (maxEvaluations < 1) throw new IllegalArgumentException("maxEvaluations must be positive");
    List<Integer> order = view.assignedTaskIds().stream().sorted().toList();
    budget.check();
    InitialTrial best = evaluator.evaluate(order);
    int evaluations = 1;
    int iteration = 0;
    Map<String, Integer> tabuUntil = new HashMap<>();
    while (evaluations < maxEvaluations) {
      InitialTrial chosen = null;
      List<Integer> chosenOrder = null;
      String chosenPair = null;
      for (int i = 0; i < order.size(); i++) {
        for (int j = i + 1; j < order.size() && evaluations < maxEvaluations; j++) {
          budget.check();
          int first = order.get(i), second = order.get(j);
          String pair = Math.min(first, second) + ":" + Math.max(first, second);
          List<Integer> next = new ArrayList<>(order);
          next.set(i, second); next.set(j, first);
          InitialTrial trial = evaluator.evaluate(next); evaluations++;
          boolean forbidden = tabuUntil.getOrDefault(pair, -1) > iteration;
          if (forbidden && trial.totalProfit() <= best.totalProfit()) continue;
          if (chosen == null || trial.totalProfit() > chosen.totalProfit()) {
            chosen = trial; chosenOrder = next; chosenPair = pair;
          }
        }
      }
      if (chosen == null) break;
      order = chosenOrder;
      tabuUntil.put(chosenPair, iteration + 4);
      if (chosen.totalProfit() > best.totalProfit()) best = chosen;
      iteration++;
    }
    return best;
  }
}
