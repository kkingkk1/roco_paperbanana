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

public final class B000025 {
  private B000025() { }

  public static InitialTrial search(ProblemInstance problem, ProblemAccess api, SatelliteView view,
      InitialEvaluator evaluator, Budget budget, RandomGenerator rng, int maxEvaluations) {
    if (maxEvaluations < 1) throw new IllegalArgumentException("maxEvaluations must be positive");
    List<Integer> order = view.assignedTaskIds().stream().sorted().toList();
    budget.check();
    InitialTrial best = evaluator.evaluate(order);
    int evaluations = 1;
    List<Integer> prefix = new ArrayList<>();
    List<Integer> remaining = new ArrayList<>(order);
    while (!remaining.isEmpty() && evaluations < maxEvaluations) {
      List<Integer> chosen = null;
      long bestRegret = Long.MIN_VALUE, bestScore = Long.MIN_VALUE;
      int chosenTask = Integer.MAX_VALUE;
      for (int task : remaining) {
        long first = Long.MIN_VALUE, second = Long.MIN_VALUE;
        List<Integer> taskPrefix = null;
        for (int position = 0; position <= prefix.size() && evaluations < maxEvaluations; position++) {
          budget.check();
          List<Integer> candidatePrefix = new ArrayList<>(prefix);
          candidatePrefix.add(position, task);
          List<Integer> full = new ArrayList<>(candidatePrefix);
          for (int other : remaining) if (other != task) full.add(other);
          InitialTrial trial = evaluator.evaluate(full); evaluations++;
          if (trial.totalProfit() > best.totalProfit()) best = trial;
          long score = trial.totalProfit();
          if (score > first) { second = first; first = score; taskPrefix = candidatePrefix; }
          else if (score > second) second = score;
        }
        if (taskPrefix == null) break;
        long regret = second == Long.MIN_VALUE ? 0 : first - second;
        if (regret > bestRegret || (regret == bestRegret && first > bestScore)
            || (regret == bestRegret && first == bestScore && task < chosenTask)) {
          bestRegret = regret; bestScore = first; chosenTask = task; chosen = taskPrefix;
        }
      }
      if (chosen == null) break;
      prefix = chosen; remaining.remove(Integer.valueOf(chosenTask));
    }
    return best;
  }
}
