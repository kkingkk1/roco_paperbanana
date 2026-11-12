package method.rkhma.legacy.algorithm.de;
import method.rkhma.legacy.operator.crossover.CrossoverDE;
import method.rkhma.legacy.operator.mutation.MutationDERand2;
import method.rkhma.legacy.operator.mutation.DEUtils;
import method.rkhma.legacy.algorithm.workspace.DEWorkspace;
import method.rkhma.legacy.common.AlgorithmParameterValidator;
import method.rkhma.legacy.common.BoundaryHandler;
import java.util.List;
import java.util.Random;

/**
 * Differential Evolution algorithm using the DE/rand/2 mutation strategy with index-based selection.
 *
 * <p>This class implements the DE/rand/2 variant, which uses the following mutation formula:
 * <pre>
 * v_i = x_r1 + F1 * (x_r2 - x_r3) + F2 * (x_r4 - x_r5)
 * </pre>
 * where:
 * <ul>
 *   <li>v_i is the mutant vector for the i-th target individual</li>
 *   <li>x_r1, x_r2, x_r3, x_r4, x_r5 are five randomly selected distinct individuals from the population</li>
 *   <li>F1 and F2 are differential weights (scaling factors), typically in [0, 2]</li>
 * </ul>
 *
 * <p>The algorithm follows these steps:
 * <ol>
 *   <li><b>Mutation:</b> Generate mutant vectors using DE/rand/2 formula</li>
 *   <li><b>Crossover:</b> Perform binomial crossover between mutant and target vectors</li>
 *   <li><b>Selection:</b> Not performed in this class (handled by caller)</li>
 * </ol>
 *
 * <p><b>Performance characteristics (Index-based version):</b>
 * <ul>
 *   <li>Index lookup: O(1) constant time</li>
 *   <li>Speedup vs List version: 50-500x for typical population sizes</li>
 *   <li>Memory efficient: no intermediate List allocations or array comparisons</li>
 *   <li>Overall complexity: O(N * D) where N = number of selected individuals, D = dimensions</li>
 * </ul>
 *
 * <p><b>Requirements:</b>
 * <ul>
 *   <li>Population size must be at least 6 to select 5 distinct individuals plus 1 target</li>
 *   <li>All indices in selectIndices must be valid (0 to pop.size()-1)</li>
 * </ul>
 *
 * <p><b>Usage Example:</b>
 * <pre>{@code
 * // Create configuration
 * DEConfig config = DEConfig.defaultConfig();
 *
 * // Example 1: Evolve entire population
 * int[] allIndices = DEUtils.allIndices(pop.size());
 * List<double[]> trials = DERand2.run(pop, allIndices, config, gen, new Random(42));
 *
 * // Example 2: Evolve subset [10, 20)
 * int[] subset = DEUtils.rangeIndices(10, 20);
 * List<double[]> trials = DERand2.run(pop, subset, config, gen, new Random(42));
 *
 * // Perform selection (not included in this class)
 * for (int i = 0; i < trials.size(); i++) {
 *     if (fitness(trials.get(i)) < fitness(pop.get(subset[i]))) {
 *         pop.set(subset[i], trials.get(i));
 *     }
 * }
 * }</pre>
 *
 * <p>This is a utility class and cannot be instantiated.
 *
 * @see DEConfig
 * @see MutationDERand2
 * @see CrossoverDE
 */
public final class DERand2 {

  /**
   * Private constructor to prevent instantiation of this utility class.
   *
   * @throws AssertionError always
   */
  private DERand2() {
    throw new AssertionError("Utility class should not be instantiated");
  }

  /**
   * Execute the DE/rand/2 strategy using index-based selection (high-performance version).
   *
   * <p>This is the <strong>recommended method</strong> for all use cases. It provides
   * significantly better performance compared to the List-based version by avoiding
   * expensive array comparisons.
   *
   * <p>This method performs mutation and crossover operations to generate a trial population.
   * Selection is NOT performed here and should be handled by the caller.
   *
   * <p><b>Algorithm Steps:</b>
   * <ol>
   *   <li>For each index in selectIndices, select 5 distinct individuals from pop</li>
   *   <li>Apply mutation: v = x_r1 + F1*(x_r2 - x_r3) + F2*(x_r4 - x_r5)</li>
   *   <li>Apply boundary handling to keep mutants within [lowerBound, upperBound]</li>
   *   <li>Apply binomial crossover to create trial vectors</li>
   * </ol>
   *
   * <p><b>Performance:</b>
   * <ul>
   *   <li>Index lookup: O(1) constant time vs O(N*D) array comparison</li>
   *   <li>Speedup: 50-500x faster for typical population sizes (nPop=100-200)</li>
   * </ul>
   *
   * <p><b>Recommended parameter ranges:</b>
   * <ul>
   *   <li>F1, F2 (differential weights): 0.5 - 1.0 (typical: 0.8)</li>
   *   <li>CR (crossover rate): 0.7 - 0.9 (typical: 0.9)</li>
   * </ul>
   *
   * @param pop Full parent population (must not be null or empty, min size 6 for DE/rand/2)
   * @param selectIndices Indices of individuals to evolve (must be valid indices into pop, not null or empty)
   * @param config Configuration object containing algorithm parameters (F1, F2, CR, bounds)
   * @param gen Current generation index (used to select F1 and F2 values, must be non-negative)
   * @param customRand Custom random number generator (for reproducibility and testing)
   * @return Generated trial population with same size as selectIndices
   * @throws IllegalArgumentException If input parameters are invalid (null, empty, negative gen, invalid indices, etc.)
   * @throws IllegalStateException If config's F values are not properly initialized
   * @see MutationDERand2#mutate
   * @see CrossoverDE#crossover
   */
  public static List<double[]> run(List<double[]> pop,
    int[] selectIndices,
    DEConfig config,
    int gen,
    Random customRand) {

    // Validate input parameters
    validateParameters(pop, selectIndices, config, gen, customRand);

    return runUnsafe(pop, selectIndices, config, gen, customRand);
  }

  public static List<double[]> runUnsafe(List<double[]> pop,
    int[] selectIndices,
    DEConfig config,
    int gen,
    Random customRand) {

    double F1 = config.getF1(gen);
    double F2 = config.getF2(gen);

    // Step 1: Perform DE/rand/2 mutation using index-based method
    List<double[]> mutantPopulation = MutationDERand2.mutate(
      pop, selectIndices, F1, F2, config.upperBound, config.lowerBound, customRand,
      config.boundaryMode()
    );

    // Step 2: Perform binomial crossover (index-based, no intermediate copy)
    List<double[]> trialPopulation = CrossoverDE.crossoverIndexedUnsafe(
      mutantPopulation, pop, selectIndices, config.crossoverRate, customRand
    );

    return trialPopulation;
  }

  public static List<double[]> runInto(List<double[]> pop,
    int[] selectIndices,
    DEConfig config,
    int gen,
    Random customRand,
    DEWorkspace workspace) {

    validateParameters(pop, selectIndices, config, gen, customRand);
    return runIntoUnsafe(pop, selectIndices, config, gen, customRand, workspace);
  }

  public static List<double[]> runIntoUnsafe(List<double[]> pop,
    int[] selectIndices,
    DEConfig config,
    int gen,
    Random customRand,
    DEWorkspace workspace) {

    int nSelect = selectIndices.length;
    int d = pop.get(0).length;
    workspace.ensureCapacity(nSelect, d);
    double f1 = config.getF1(gen);
    double f2 = config.getF2(gen);

    for (int i = 0; i < nSelect; i++) {
      int targetIndex = selectIndices[i];
      int[] selected = DEUtils.selectKDistinct(pop.size(), targetIndex, 5, customRand);
      int r1 = selected[0];
      int r2 = selected[1];
      int r3 = selected[2];
      int r4 = selected[3];
      int r5 = selected[4];
      double[] mutant = workspace.mutantBuffer[i];
      double[] xr1 = pop.get(r1);
      double[] xr2 = pop.get(r2);
      double[] xr3 = pop.get(r3);
      double[] xr4 = pop.get(r4);
      double[] xr5 = pop.get(r5);
      double[] target = pop.get(targetIndex);

      for (int j = 0; j < d; j++) {
        double mutatedValue = xr1[j] + f1 * (xr2[j] - xr3[j]) + f2 * (xr4[j] - xr5[j]);
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
   * Validates input parameters for the index-based run method.
   *
   * @param pop Full parent population
   * @param selectIndices Indices of individuals to evolve
   * @param config Configuration object
   * @param gen Current generation index
   * @param customRand Random number generator
   * @throws IllegalArgumentException If any parameter is invalid
   */
  private static void validateParameters(List<double[]> pop,
    int[] selectIndices,
    DEConfig config,
    int gen,
    Random customRand) {
    // Use common validator for standard checks
    AlgorithmParameterValidator.validateCommonParameters(pop, selectIndices, config, customRand);

    // Validate generation
    AlgorithmParameterValidator.validateGeneration(gen);

    // DE/rand/2 specific validation: minimum population size
    AlgorithmParameterValidator.validateMinPopulationSize(pop.size(), 6, "DE/rand/2");
  }
}
