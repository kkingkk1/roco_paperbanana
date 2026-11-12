package method.rkhma.legacy.strategy;

import method.rkhma.legacy.algorithm.ga.GeneticAlgorithmNoSelect;
import java.util.List;

/**
 * Genetic Algorithm (GA) strategy for parent algorithm selection.
 *
 * <p>Applies crossover and mutation operators to evolve the selected population.
 * Uses tournament selection for parent mating, followed by arithmetic crossover
 * and Gaussian mutation.</p>
 *
 * <h2>Required Input Fields</h2>
 * <ul>
 *   <li>{@code population} - Complete population for tournament selection</li>
 *   <li>{@code selectedIndices} - Indices of individuals to evolve</li>
 *   <li>{@code populationFitness} - Fitness values for the complete population</li>
 * </ul>
 *
 * @see GeneticAlgorithmNoSelect
 * @see ParentStrategy
 */
public class GAStrategy implements ParentStrategy {
  private final GeneticAlgorithmNoSelect.GAConfig gaConfig;

  public GAStrategy(GeneticAlgorithmNoSelect.GAConfig gaConfig) {
    this.gaConfig = gaConfig;
  }

  @Override
  public List<double[]> apply(ParentContext context, StrategyInput input) throws Exception {
    if (context.getCount() < input.getPopulation().size()) {
      return GeneticAlgorithmNoSelect.run(
        input.getPopulation(),
        input.getSelectedIndices(),
        input.getPopulationFitness(),
        gaConfig,
        context.getGen(),
        context.getRand()
      );
    }
    return GeneticAlgorithmNoSelect.runInto(
      input.getPopulation(),
      input.getSelectedIndices(),
      input.getPopulationFitness(),
      gaConfig,
      context.getGen(),
      context.getRand(),
      context.getGAWorkspace()
    );
  }
}

