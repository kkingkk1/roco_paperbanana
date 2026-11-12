package method.cases.examples;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.random.RandomGenerator;
import method.runtime.Budget;
import method.runtime.ProblemAccess;
import method.runtime.SolverContracts.InitialEvaluator;
import method.runtime.SolverContracts.InitialTrial;
import method.runtime.SolverContracts.SatelliteView;
import model.ProblemInstance;

/** Elitist permutation GA with tournament selection and order crossover. */
public final class B000017 {
  private B000017() { }

  public static InitialTrial search(ProblemInstance problem, ProblemAccess api, SatelliteView view,
      InitialEvaluator evaluator, Budget budget, RandomGenerator rng, int maxEvaluations) {
    if (maxEvaluations < 1) throw new IllegalArgumentException("maxEvaluations must be positive");
    List<Integer> tasks = view.assignedTaskIds().stream().sorted().toList();
    budget.check();
    InitialTrial best = evaluator.evaluate(tasks);
    if (tasks.size() < 2) return best;
    List<InitialTrial> population = new ArrayList<>();
    population.add(best);
    int evaluations = 1;
    while (population.size() < 8 && evaluations < maxEvaluations) {
      List<Integer> order = new ArrayList<>(tasks);
      for (int i = order.size() - 1; i > 0; i--) Collections.swap(order, i, rng.nextInt(i + 1));
      budget.check();
      InitialTrial trial = evaluator.evaluate(order); evaluations++;
      population.add(trial);
      if (trial.totalProfit() > best.totalProfit()) best = trial;
    }
    while (evaluations < maxEvaluations) {
      population.sort(Comparator.comparingLong(InitialTrial::totalProfit).reversed());
      List<InitialTrial> nextPopulation = new ArrayList<>();
      nextPopulation.add(population.getFirst());
      while (nextPopulation.size() < population.size() && evaluations < maxEvaluations) {
        InitialTrial parent1 = tournament(population, rng);
        InitialTrial parent2 = tournament(population, rng);
        int left = rng.nextInt(tasks.size()), right = rng.nextInt(tasks.size());
        if (left > right) { int temp = left; left = right; right = temp; }
        List<Integer> child = new ArrayList<>(Collections.nCopies(tasks.size(), null));
        for (int i = left; i <= right; i++) child.set(i, parent1.priorities().get(i));
        int slot = 0;
        for (int task : parent2.priorities()) {
          if (child.contains(task)) continue;
          while (child.get(slot) != null) slot++;
          child.set(slot, task);
        }
        if (rng.nextDouble() < .25) Collections.swap(child, rng.nextInt(child.size()), rng.nextInt(child.size()));
        budget.check();
        InitialTrial trial = evaluator.evaluate(child); evaluations++;
        nextPopulation.add(trial);
        if (trial.totalProfit() > best.totalProfit()) best = trial;
      }
      population = nextPopulation;
    }
    return best;
  }

  private static InitialTrial tournament(List<InitialTrial> population, RandomGenerator rng) {
    InitialTrial first = population.get(rng.nextInt(population.size()));
    InitialTrial second = population.get(rng.nextInt(population.size()));
    return first.totalProfit() >= second.totalProfit() ? first : second;
  }
}
