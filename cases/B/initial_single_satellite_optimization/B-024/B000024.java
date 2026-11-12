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

public final class B000024 {
  private B000024() { }

  public static InitialTrial search(ProblemInstance problem, ProblemAccess api, SatelliteView view,
      InitialEvaluator evaluator, Budget budget, RandomGenerator rng, int maxEvaluations) {
    if (maxEvaluations < 1) throw new IllegalArgumentException("maxEvaluations must be positive");
    List<Integer> order = view.assignedTaskIds().stream().sorted().toList();
    budget.check();
    InitialTrial best = evaluator.evaluate(order);
    int evaluations = 1;
    List<Integer> prefix = new ArrayList<>();
    for (int task : order) {
      List<Integer> chosen = null;
      InitialTrial chosenTrial = null;
      for (int position = 0; position <= prefix.size() && evaluations < maxEvaluations; position++) {
        budget.check();
        List<Integer> candidatePrefix = new ArrayList<>(prefix);
        candidatePrefix.add(position, task);
        List<Integer> full = new ArrayList<>(candidatePrefix);
        for (int other : order) if (!candidatePrefix.contains(other)) full.add(other);
        InitialTrial trial = evaluator.evaluate(full); evaluations++;
        if (trial.totalProfit() > best.totalProfit()) best = trial;
        if (chosenTrial == null || trial.totalProfit() > chosenTrial.totalProfit()) {
          chosen = candidatePrefix; chosenTrial = trial;
        }
      }
      if (chosen == null) break;
      prefix = chosen;
    }
    return best;
  }
}
