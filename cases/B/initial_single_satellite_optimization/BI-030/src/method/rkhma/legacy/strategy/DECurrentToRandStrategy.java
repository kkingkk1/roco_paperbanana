package method.rkhma.legacy.strategy;

import method.rkhma.legacy.algorithm.de.DEConfig;
import method.rkhma.legacy.algorithm.de.DECurrentToRand;
import java.util.List;

/**
 * Differential Evolution DE/current-to-rand/1 strategy implementation.
 *
 * <p>This strategy uses a mutation formula that combines movement towards a random
 * vector with a scaled differential vector:
 * <pre>
 * v_i = x_i + F1 * (x_r1 - x_i) + F2 * (x_r2 - x_r3)
 * </pre>
 * where x_i is the current individual, x_r1, x_r2, x_r3 are randomly selected
 * individuals, F1 controls movement towards the random vector, and F2 scales
 * the differential vector.</p>
 *
 * <h2>Required Input Fields</h2>
 * <ul>
 *   <li>{@code population} - Complete population (for mutation and crossover)</li>
 *   <li>{@code selectedIndices} - Indices of individuals to evolve</li>
 * </ul>
 *
 * <h2>Required Context Fields</h2>
 * <ul>
 *   <li>{@code context.rand} - Random number generator</li>
 *   <li>{@code context.genSupplier} - Current generation (via getGen())</li>
 * </ul>
 */
public class DECurrentToRandStrategy implements ParentStrategy {

  private final DEConfig deConfig;

  /**
   * Constructs a DE/current-to-rand/1 strategy with the specified configuration.
   *
   * @param deConfig Configuration object containing DE parameters (F1, F2, CR, bounds)
   */
  public DECurrentToRandStrategy(DEConfig deConfig) {
    this.deConfig = deConfig;
  }

  @Override
  public List<double[]> apply(ParentContext context, StrategyInput input) throws Exception {
    List<double[]> population = input.getPopulation();
    int[] selectedIndices = input.getSelectedIndices();

    // Run the DE/current-to-rand/1 algorithm using optimized indexed method
    List<double[]> trialPopulation = DECurrentToRand.runIntoUnsafe(
      population,
      selectedIndices,
      deConfig,
      context.getGen(),
      context.getRand(),
      context.getDEWorkspace()
    );

    return trialPopulation;
  }
}

