package method.rkhma.legacy.strategy;

import method.rkhma.legacy.algorithm.aco.ACOGaussGenerate;
import java.util.List;

/**
 * Ant Colony Optimization (ACO) strategy for parent algorithm selection.
 *
 * <p>ACO uses Gaussian sampling based on elite individuals to generate new solutions.
 * It constructs a probability distribution from high-fitness individuals and samples
 * new solutions using Gaussian perturbation.</p>
 *
 * <h2>Algorithm Characteristics</h2>
 * <ul>
 *   <li>Does NOT require personal best (pbest) or global best (gbest) solutions</li>
 *   <li>Uses only current population, selected indices, and fitness values</li>
 *   <li>Employs elite-based Gaussian distribution sampling</li>
 *   <li>Suitable for exploration-oriented search</li>
 * </ul>
 *
 * <h2>Required Input Fields</h2>
 * <ul>
 *   <li>{@code population} - Complete population of solutions</li>
 *   <li>{@code selectedIndices} - Indices of individuals to operate on</li>
 *   <li>{@code populationFitness} - Fitness values for the population</li>
 * </ul>
 *
 * @see ACOGaussGenerate
 * @see ParentStrategy
 */
public class ACOStrategy implements ParentStrategy {
  private final ACOGaussGenerate.ACOConfig acoConfig;

  /**
   * Creates a new ACO strategy with the specified configuration.
   *
   * @param acoConfig ACO algorithm configuration parameters
   * @throws IllegalArgumentException if acoConfig is null or invalid
   */
  public ACOStrategy(ACOGaussGenerate.ACOConfig acoConfig) {
    if (acoConfig == null) {
      throw new IllegalArgumentException("acoConfig cannot be null");
    }
    this.acoConfig = acoConfig;
  }

  /**
   * Applies ACO algorithm to generate new solutions.
   *
   * <p>This method extracts the population, selected indices, and fitness values
   * from the input, then delegates to the ACO implementation. Unlike PSO or some
   * DE variants, ACO does not use pbest/gbest solutions.</p>
   *
   * @param context Execution context containing generation number and random generator
   * @param input   Input data containing population, indices, and fitness values
   * @return Newly generated population via ACO Gaussian sampling
   * @throws IllegalArgumentException if required input fields are missing
   * @throws Exception                if ACO execution fails
   */
  @Override
  public List<double[]> apply(ParentContext context, StrategyInput input) throws Exception {
    // Extract required fields from input
    List<double[]> population = input.getPopulation();
    int[] selectedIndices = input.getSelectedIndices();
    List<Double> populationFitness = input.getPopulationFitness();

    // Validate inputs
    if (population == null) {
      throw new IllegalArgumentException("Population cannot be null");
    }
    if (selectedIndices == null) {
      throw new IllegalArgumentException("Selected indices cannot be null");
    }
    if (populationFitness == null) {
      throw new IllegalArgumentException("Population fitness cannot be null");
    }

    // Run ACO algorithm
    // Note: ACO only requires population, indices, fitness, and random generator
    // It does NOT use pbest/gbest like PSO, or indToBeUpdated mask
    return ACOGaussGenerate.runUnsafe(
      population,
      selectedIndices,
      populationFitness,
      acoConfig,
      context.getRand(),
      context.getACOWorkspace(),
      input.getFitnessRankDesc()
    );
  }
}
