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

/** Project adaptation of RKHMA's random-key DE/PSO initial single-satellite search. */
public final class B000040 {
  private B000040() { }

  private static final class Particle {
    double[] keys;
    double[] velocity;
    double[] personalBest;
    InitialTrial current;
    InitialTrial best;

    Particle(double[] keys, InitialTrial trial) {
      this.keys = keys;
      velocity = new double[keys.length];
      personalBest = keys.clone();
      current = trial;
      best = trial;
    }
  }

  public static InitialTrial search(ProblemInstance problem, ProblemAccess api, SatelliteView view,
      InitialEvaluator evaluator, Budget budget, RandomGenerator rng, int maxEvaluations) {
    if (maxEvaluations < 1) throw new IllegalArgumentException("maxEvaluations must be positive");
    List<Integer> tasks = view.assignedTaskIds().stream().sorted().toList();
    budget.check();
    InitialTrial first = evaluator.evaluate(tasks);
    if (tasks.size() < 2 || maxEvaluations == 1) return first;

    int dimension = tasks.size();
    int populationSize = (int) Math.min(maxEvaluations, Math.max(6L, Math.round(2.5 * dimension)));
    double[] initialKeys = new double[dimension];
    for (int i = 0; i < dimension; i++) initialKeys[i] = 1.0 - (double) i / dimension;
    List<Particle> population = new ArrayList<>();
    population.add(new Particle(initialKeys, first));
    InitialTrial globalBest = first;
    double[] globalKeys = initialKeys.clone();
    int evaluations = 1;
    while (population.size() < populationSize) {
      budget.check();
      double[] keys = new double[dimension];
      for (int i = 0; i < dimension; i++) keys[i] = rng.nextDouble();
      InitialTrial trial = evaluator.evaluate(order(tasks, keys));
      evaluations++;
      population.add(new Particle(keys, trial));
      if (trial.totalProfit() > globalBest.totalProfit()) {
        globalBest = trial;
        globalKeys = keys.clone();
      }
    }

    int generation = 0;
    while (evaluations < maxEvaluations) {
      for (int index = 0; index < population.size() && evaluations < maxEvaluations; index++) {
        budget.check();
        Particle particle = population.get(index);
        double[] candidate;
        double[] velocity = particle.velocity;
        if (generation % 2 == 0 && population.size() >= 4) {
          int a = distinctIndex(rng, population.size(), index, -1, -1);
          int b = distinctIndex(rng, population.size(), index, a, -1);
          int c = distinctIndex(rng, population.size(), index, a, b);
          candidate = particle.keys.clone();
          int forced = rng.nextInt(dimension);
          for (int i = 0; i < dimension; i++) {
            if (i == forced || rng.nextDouble() < 0.9) {
              candidate[i] = clamp(population.get(a).keys[i]
                  + 0.5 * (population.get(b).keys[i] - population.get(c).keys[i]));
            }
          }
        } else {
          candidate = new double[dimension];
          velocity = new double[dimension];
          for (int i = 0; i < dimension; i++) {
            velocity[i] = 0.7 * particle.velocity[i]
                + 1.4 * rng.nextDouble() * (particle.personalBest[i] - particle.keys[i])
                + 1.4 * rng.nextDouble() * (globalKeys[i] - particle.keys[i]);
            candidate[i] = clamp(particle.keys[i] + velocity[i]);
          }
        }
        InitialTrial trial = evaluator.evaluate(order(tasks, candidate));
        evaluations++;
        if (trial.totalProfit() >= particle.current.totalProfit()) {
          particle.keys = candidate;
          particle.velocity = velocity;
          particle.current = trial;
        }
        if (trial.totalProfit() > particle.best.totalProfit()) {
          particle.personalBest = candidate.clone();
          particle.best = trial;
        }
        if (trial.totalProfit() > globalBest.totalProfit()) {
          globalBest = trial;
          globalKeys = candidate.clone();
        }
      }
      generation++;
    }
    return globalBest;
  }

  private static int distinctIndex(RandomGenerator rng, int size, int first, int second, int third) {
    int result;
    do result = rng.nextInt(size); while (result == first || result == second || result == third);
    return result;
  }

  private static double clamp(double value) { return Math.max(0.0, Math.min(1.0, value)); }

  private static List<Integer> order(List<Integer> tasks, double[] keys) {
    List<Integer> indices = new ArrayList<>(tasks.size());
    for (int i = 0; i < tasks.size(); i++) indices.add(i);
    indices.sort(Comparator.comparingDouble((Integer i) -> keys[i]).reversed()
        .thenComparingInt(tasks::get));
    return indices.stream().map(tasks::get).toList();
  }
}
