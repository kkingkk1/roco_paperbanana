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

/** Beam search over task prefixes; C evaluates each completed order. */
public final class B000015 {
  private B000015() { }
  private record Node(List<Integer> prefix, InitialTrial trial) { }

  public static InitialTrial search(ProblemInstance problem, ProblemAccess api, SatelliteView view,
      InitialEvaluator evaluator, Budget budget, RandomGenerator rng, int maxEvaluations) {
    if (maxEvaluations < 1) throw new IllegalArgumentException("maxEvaluations must be positive");
    List<Integer> tasks = view.assignedTaskIds().stream().sorted().toList();
    budget.check();
    InitialTrial best = evaluator.evaluate(tasks);
    int evaluations = 1;
    List<Node> beam = List.of(new Node(List.of(), best));
    for (int depth = 0; depth < tasks.size() && evaluations < maxEvaluations; depth++) {
      List<Node> expanded = new ArrayList<>();
      for (Node node : beam) {
        for (int task : tasks) {
          if (node.prefix().contains(task) || evaluations >= maxEvaluations) continue;
          budget.check();
          List<Integer> prefix = new ArrayList<>(node.prefix());
          prefix.add(task);
          List<Integer> order = new ArrayList<>(prefix);
          for (int other : tasks) if (!prefix.contains(other)) order.add(other);
          InitialTrial trial = evaluator.evaluate(order); evaluations++;
          if (trial.totalProfit() > best.totalProfit()) best = trial;
          expanded.add(new Node(List.copyOf(prefix), trial));
        }
      }
      expanded.sort(Comparator.comparingLong((Node node) -> node.trial().totalProfit()).reversed()
          .thenComparing(node -> node.prefix().toString()));
      beam = List.copyOf(expanded.subList(0, Math.min(3, expanded.size())));
      if (beam.isEmpty()) break;
    }
    return best;
  }
}
