package method.rkhma.legacy.strategy;

import java.util.List;

/**
 * Parent algorithm strategy interface.
 *
 * All strategy algorithms implement this interface. By using {@link ParentContext}
 * and {@link StrategyInput}, strategies can access required state and dependencies
 * without directly accessing the HybCec object, improving decoupling and testability.
 *
 * <h2>Design Philosophy</h2>
 * <p>This interface uses a simplified two-parameter design:</p>
 * <ul>
 *   <li>{@link ParentContext} - Provides execution context (generation, random generator, etc.)</li>
 *   <li>{@link StrategyInput} - Encapsulates all input data; strategies extract only what they need</li>
 * </ul>
 *
 * <h2>Benefits</h2>
 * <ul>
 *   <li>Cleaner interface with fewer parameters</li>
 *   <li>Each strategy only accesses the data it actually uses</li>
 *   <li>Easy to add new input fields without changing the interface signature</li>
 *   <li>Improved code readability and maintainability</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * public class ACOStrategy implements ParentStrategy {
 *     @Override
 *     public List<double[]> apply(ParentContext context, StrategyInput input) {
 *         // Extract only what ACO needs
 *         List<double[]> population = input.getPopulation();
 *         int[] indices = input.getSelectedIndices();
 *         List<Double> fitness = input.getPopulationFitness();
 *
 *         // Run ACO algorithm
 *         return ACOGaussGenerate.run(population, indices, fitness, config, context.getRand());
 *     }
 * }
 * }</pre>
 *
 * @see ParentContext
 * @see StrategyInput
 */
public interface ParentStrategy {
  /**
   * Applies the strategy algorithm to generate an updated population.
   *
   * <p>Implementations should:</p>
   * <ol>
   *   <li>Extract required data from {@code input} using appropriate getters</li>
   *   <li>Access execution context (generation, random generator) from {@code context}</li>
   *   <li>Execute the algorithm logic</li>
   *   <li>Return the newly generated population</li>
   * </ol>
   *
   * @param context Execution context containing generation number, random generator, etc.
   * @param input   Input data container with population, fitness, and algorithm-specific parameters
   * @return Newly generated population (typically same size as selected individuals)
   * @throws IllegalArgumentException if required input fields are missing or invalid
   * @throws Exception                if algorithm execution fails
   */
  List<double[]> apply(ParentContext context, StrategyInput input) throws Exception;

  /**
   * Applies the strategy and optionally returns already evaluated candidate fitness.
   *
   * <p>Most parents only generate chromosomes, so the default wraps {@link #apply}.
   * Local-search parents such as ALNS can override this to avoid duplicate
   * evaluation in the population manager.</p>
   */
  default ParentUpdateResult applyWithResult(
    ParentContext context,
    StrategyInput input
  ) throws Exception {
    return ParentUpdateResult.unevaluated(apply(context, input));
  }
}

