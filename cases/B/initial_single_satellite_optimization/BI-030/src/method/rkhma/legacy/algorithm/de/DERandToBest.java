package method.rkhma.legacy.algorithm.de;
import method.rkhma.legacy.operator.mutation.MutationDERandToBest;
import method.rkhma.legacy.operator.crossover.CrossoverDE;
import method.rkhma.legacy.operator.mutation.DEUtils;
import method.rkhma.legacy.algorithm.workspace.DEWorkspace;
import method.rkhma.legacy.common.AlgorithmParameterValidator;
import method.rkhma.legacy.common.BoundaryHandler;
import java.util.List;
import java.util.Random;

/**
 * DE/rand-to-best/1 differential evolution strategy.
 *
 * <p>This class implements the DE/rand-to-best/1 mutation strategy, which balances exploration
 * and exploitation by incorporating both a random base vector and guidance toward the best individual.
 *
 * <p>This is a utility class and cannot be instantiated.
 */
public final class DERandToBest {

  /**
   * Private constructor to prevent instantiation of this utility class.
   *
   * @throws AssertionError always
   */
  private DERandToBest() {
    throw new AssertionError("Utility class should not be instantiated");
  }

  /**
   * Execute the DE/rand-to-best/1 strategy using index-based selection (high-performance version).
   *
   * <p>This is the <strong>recommended method</strong> for all use cases. It provides
   * significantly better performance by avoiding expensive array comparisons and unnecessary
   * intermediate list allocations.
   *
   * <p>DE/rand-to-best/1 implements the mutation formula:
   * <pre>
   * v_i = x_r1 + F1 × (x_best - x_r1) + F2 × (x_r2 - x_r3)
   * </pre>
   * where:
   * <ul>
   *   <li>x_r1, x_r2, x_r3 are randomly selected distinct individuals</li>
   *   <li>x_best is the globally best individual in the population</li>
   *   <li>F1 controls the strength of movement toward the best solution</li>
   *   <li>F2 controls the strength of random differential exploration</li>
   * </ul>
   *
   * <p>Followed by binomial crossover to generate trial vector:
   * <pre>
   * u_i[j] = v_i[j]  if (rand() <= CR) or (j == jrand)
   *          x_i[j]  otherwise
   * </pre>
   *
   * <p><strong>Performance characteristics:</strong>
   * <ul>
   *   <li>Index lookup: O(1) constant time</li>
   *   <li>Best individual search: O(N) single pass over fitness values</li>
   *   <li>Zero intermediate list allocations for index operations</li>
 *   <li>Memory efficient compared to List-based versions</li>
   * </ul>
   *
   * <p><strong>Recommended parameter ranges:</strong>
   * <ul>
   *   <li>F1 (best-seeking factor): 0.3 - 0.9 (typical: 0.5)</li>
   *   <li>F2 (differential weight): 0.3 - 0.9 (typical: 0.5)</li>
   *   <li>CR (crossover rate): 0.7 - 0.9 (typical: 0.9)</li>
   * </ul>
   *
   * <p><strong>Usage example:</strong>
   * <pre>
   * // Evolve entire population
   * int[] allIndices = DEUtils.allIndices(pop.size());
   * List&lt;double[]&gt; trials = DERandToBest.run(pop, allIndices, fitness, config, gen, rand);
   *
   * // Evolve subset [10, 20)
   * int[] subset = DEUtils.rangeIndices(10, 20);
   * List&lt;double[]&gt; trials = DERandToBest.run(pop, subset, fitness, config, gen, rand);
   * </pre>
   *
   * @param pop Full parent population (must not be null or empty, each element is a double[] of dimension D)
   * @param selectIndices Indices of individuals to evolve (must be valid indices into pop, not null or empty)
   * @param fitness Fitness values for each individual in the full parent population (must have same size as pop)
   * @param config Configuration object containing algorithm parameters (must not be null)
   * @param gen Current generation index (must be non-negative, used to select F1 and F2 values)
   * @param customRand Custom random number generator (must not be null)
   * @return Generated trial population with same size as selectIndices
   * @throws IllegalArgumentException If input parameters are invalid
   */
  public static List<double[]> run(List<double[]> pop,
    int[] selectIndices,
    List<Double> fitness,
    DEConfig config,
    int gen,
    Random customRand) {

    // Validate input parameters
    validateParameters(pop, selectIndices, fitness, config, gen, customRand);

    int bestIndex = DEUtils.findBestIndexMax(fitness);
    return runUnsafe(pop, selectIndices, bestIndex, config, gen, customRand);
  }

  /**
   * Execute the DE/rand-to-best/1 strategy with a pre-computed best individual (optimization for repeated calls).
   *
   * <p>This overload is useful when the best individual is already known (e.g., maintained by
   * the calling algorithm) and can be reused across multiple calls, avoiding the O(N) fitness scan.
   *
   * <p><strong>Performance advantage:</strong> Eliminates O(N) best-individual search overhead.
   *
   * <p><strong>Usage example:</strong>
   * <pre>
   * // Track best individual externally
   * int bestIndex = DEUtils.findBestIndexMax(fitness);
   *
   * // Evolve multiple subsets without re-finding best
   * int[] subset1 = DEUtils.rangeIndices(0, 50);
   * List&lt;double[]&gt; trials1 = DERandToBest.run(pop, subset1, bestIndex, config, gen, rand);
   *
   * int[] subset2 = DEUtils.rangeIndices(50, 100);
   * List&lt;double[]&gt; trials2 = DERandToBest.run(pop, subset2, bestIndex, config, gen, rand);
   * </pre>
   *
   * @param pop Full parent population (must not be null or empty, each element is a double[] of dimension D)
   * @param selectIndices Indices of individuals to evolve (must be valid indices into pop, not null or empty)
   * @param bestIndex Pre-computed index of the best individual (must be valid index into pop)
   * @param config Configuration object containing algorithm parameters (must not be null)
   * @param gen Current generation index (must be non-negative, used to select F1 and F2 values)
   * @param customRand Custom random number generator (must not be null)
   * @return Generated trial population with same size as selectIndices
   * @throws IllegalArgumentException If input parameters are invalid
   */
  public static List<double[]> run(List<double[]> pop,
    int[] selectIndices,
    int bestIndex,
    DEConfig config,
    int gen,
    Random customRand) {

    validateParametersWithBest(pop, selectIndices, bestIndex, config, gen, customRand);
    return runUnsafe(pop, selectIndices, bestIndex, config, gen, customRand);
  }

  public static List<double[]> runUnsafe(List<double[]> pop,
    int[] selectIndices,
    int bestIndex,
    DEConfig config,
    int gen,
    Random customRand) {

    double F1 = config.getF1(gen);
    double F2 = config.getF2(gen);

    // ========================================
    // Step 1: Perform DE/rand-to-best/1 mutation (best already provided)
    // ========================================

    List<double[]> mutantPopulation = MutationDERandToBest.mutate(
      pop,
      selectIndices,
      bestIndex,
      F1,
      F2,
      config.upperBound,
      config.lowerBound,
      customRand,
      config.boundaryMode()
    );

    // ========================================
    // Step 2: Perform binomial crossover (index-based, no intermediate copy)
    // ========================================

    List<double[]> trialPopulation = CrossoverDE.crossoverIndexedUnsafe(
      mutantPopulation,
      pop,
      selectIndices,
      config.crossoverRate,
      customRand
    );

    return trialPopulation;
  }

  public static List<double[]> runInto(List<double[]> pop,
    int[] selectIndices,
    int bestIndex,
    DEConfig config,
    int gen,
    Random customRand,
    DEWorkspace workspace) {

    validateParametersWithBest(pop, selectIndices, bestIndex, config, gen, customRand);
    return runIntoUnsafe(pop, selectIndices, bestIndex, config, gen, customRand, workspace);
  }

  public static List<double[]> runIntoUnsafe(List<double[]> pop,
    int[] selectIndices,
    int bestIndex,
    DEConfig config,
    int gen,
    Random customRand,
    DEWorkspace workspace) {

    int nSelect = selectIndices.length;
    int d = pop.get(0).length;
    workspace.ensureCapacity(nSelect, d);
    double f1 = config.getF1(gen);
    double f2 = config.getF2(gen);
    double[] best = pop.get(bestIndex);

    for (int i = 0; i < nSelect; i++) {
      int targetIndex = selectIndices[i];
      int r1 = DEUtils.selectRandomIndex(pop.size(), targetIndex, -1, -1, -1, -1, -1, customRand);
      int r2 = DEUtils.selectRandomIndex(pop.size(), targetIndex, r1, -1, -1, -1, -1, customRand);
      int r3 = DEUtils.selectRandomIndex(pop.size(), targetIndex, r1, r2, -1, -1, -1, customRand);
      double[] mutant = workspace.mutantBuffer[i];
      double[] xr1 = pop.get(r1);
      double[] xr2 = pop.get(r2);
      double[] xr3 = pop.get(r3);
      double[] target = pop.get(targetIndex);

      for (int j = 0; j < d; j++) {
        double mutatedValue = xr1[j] + f1 * (best[j] - xr1[j]) + f2 * (xr2[j] - xr3[j]);
        mutant[j] = BoundaryHandler.repairDE(
          mutatedValue, target[j], config.lowerBound, config.upperBound,
          customRand, config.boundaryMode());
      }
    }

    crossoverInto(workspace, pop, selectIndices, config.crossoverRate, customRand, nSelect, d);
    return workspace.trialView(nSelect);
  }

  private static void crossoverInto(DEWorkspace workspace,
    List<double[]> pop,
    int[] selectIndices,
    double crossoverRate,
    Random customRand,
    int nSelect,
    int d) {
    for (int i = 0; i < nSelect; i++) {
      double[] trial = workspace.trialBuffer[i];
      double[] mutant = workspace.mutantBuffer[i];
      double[] target = pop.get(selectIndices[i]);
      if (crossoverRate == 1.0) {
        System.arraycopy(mutant, 0, trial, 0, d);
      } else if (crossoverRate == 0.0) {
        int jrand = customRand.nextInt(d);
        System.arraycopy(target, 0, trial, 0, d);
        trial[jrand] = mutant[jrand];
      } else {
        int jrand = customRand.nextInt(d);
        for (int j = 0; j < d; j++) {
          double randomValue = customRand.nextDouble();
          trial[j] = (randomValue <= crossoverRate || j == jrand) ? mutant[j] : target[j];
        }
      }
    }
  }

  /**
   * Validates input parameters for the fitness-based run method.
   *
   * @param pop Full parent population
   * @param selectIndices Indices of individuals to evolve
   * @param fitness Fitness values
   * @param config Configuration object
   * @param gen Current generation index
   * @param customRand Random number generator
   * @throws IllegalArgumentException If any parameter is invalid
   */
  private static void validateParameters(List<double[]> pop,
    int[] selectIndices,
    List<Double> fitness,
    DEConfig config,
    int gen,
    Random customRand) {
    // Use common validator for standard checks
    AlgorithmParameterValidator.validateCommonParameters(pop, selectIndices, fitness, config, customRand);

    // Validate generation
    AlgorithmParameterValidator.validateGeneration(gen);

    // DE/rand-to-best/1 specific validation: minimum population size
    AlgorithmParameterValidator.validateMinPopulationSize(pop.size(), 4, "DE/rand-to-best/1");
  }

  /**
   * Validates input parameters for the pre-computed best individual run method.
   *
   * @param pop Full parent population
   * @param selectIndices Indices of individuals to evolve
   * @param bestIndex Pre-computed index of the best individual
   * @param config Configuration object
   * @param gen Current generation index
   * @param customRand Random number generator
   * @throws IllegalArgumentException If any parameter is invalid
   */
  private static void validateParametersWithBest(List<double[]> pop,
    int[] selectIndices,
    int bestIndex,
    DEConfig config,
    int gen,
    Random customRand) {
    // Use common validator for standard checks
    AlgorithmParameterValidator.validateCommonParameters(pop, selectIndices, config, customRand);

    // Validate generation
    AlgorithmParameterValidator.validateGeneration(gen);

    // Validate bestIndex
    if (bestIndex < 0 || bestIndex >= pop.size()) {
      throw new IllegalArgumentException(
        String.format("Best index out of bounds: must be in [0, %d), got %d",
        pop.size(), bestIndex));
    }

    // DE/rand-to-best/1 specific validation: minimum population size
    AlgorithmParameterValidator.validateMinPopulationSize(pop.size(), 4, "DE/rand-to-best/1");
  }
}
