package method.rkhma.legacy.algorithm.de;
import method.rkhma.legacy.algorithm.workspace.DEWorkspace;
import method.rkhma.legacy.operator.mutation.MutationDECurrentToBest;
import method.rkhma.legacy.operator.crossover.CrossoverDE;
import method.rkhma.legacy.operator.mutation.DEUtils;
import method.rkhma.legacy.common.AlgorithmParameterValidator;
import method.rkhma.legacy.common.BoundaryHandler;
import java.util.List;
import java.util.Random;

/**
 * @see MutationDECurrentToBest Mutation operator
 * @see CrossoverDE Crossover operator
 */
public final class DECurrentToBest {

  /**
   * Private constructor to prevent instantiation of utility class.
   */
  private DECurrentToBest() {
    throw new AssertionError("Utility class should not be instantiated");
  }

  /**
   * Execute the DE/current-to-best/1 strategy using index-based selection (high-performance version).
   *
   * @param pop Full parent population
   * @param selectIndices Indices of individuals to evolve
   * @param fitness Fitness list
   * @param config Configuration object containing algorithm parameters
   * @param gen Current generation index (used to select F1 and F2 values)
   * @param customRand Custom random number generator
   * @return Generated trial population
   * @throws IllegalArgumentException If input parameters are invalid
   */
  public static List<double[]> run(List<double[]> pop,
    int[] selectIndices,
    List<Double> fitness,
    DEConfig config,
    int gen,
    Random customRand) {

    // Validate input parameters
    validateInputs(pop, selectIndices, fitness, config, gen, customRand);

    int bestIndex = DEUtils.findBestIndexMax(fitness);
    return runWithBestUnsafe(pop, selectIndices, bestIndex, config, gen, customRand);
  }

  public static List<double[]> runInto(List<double[]> pop,
    int[] selectIndices,
    int bestIndex,
    DEConfig config,
    int gen,
    Random customRand,
    DEWorkspace workspace) {

    validateInputsWithBest(pop, selectIndices, bestIndex, config, gen, customRand);
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
      double[] mutant = workspace.mutantBuffer[i];
      double[] current = pop.get(targetIndex);
      double[] xr1 = pop.get(r1);
      double[] xr2 = pop.get(r2);
      for (int j = 0; j < d; j++) {
        double currentX = current[j];
        double mutatedValue = currentX
        + f1 * (best[j] - currentX)
        + f2 * (xr1[j] - xr2[j]);
        mutant[j] = BoundaryHandler.repairDE(
          mutatedValue, currentX, config.lowerBound, config.upperBound,
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
   * Execute DE/current-to-best/1 strategy with pre-computed best individual.
   * This method is more efficient when the best individual is already known,
   * avoiding the O(n) search in the fitness list.
   *
   * @param pop Full parent population
   * @param selectIndices Indices of individuals to evolve
   * @param bestIndex Pre-computed index of the best individual
   * @param config Configuration object containing algorithm parameters
   * @param gen Current generation index (used to select F1 and F2 values)
   * @param customRand Custom random number generator
   * @return Generated trial population
   * @throws IllegalArgumentException If input parameters are invalid
   */
  public static List<double[]> runWithBest(List<double[]> pop,
    int[] selectIndices,
    int bestIndex,
    DEConfig config,
    int gen,
    Random customRand) {

    validateInputsWithBest(pop, selectIndices, bestIndex, config, gen, customRand);
    return runWithBestUnsafe(pop, selectIndices, bestIndex, config, gen, customRand);
  }

  public static List<double[]> runWithBestUnsafe(List<double[]> pop,
    int[] selectIndices,
    int bestIndex,
    DEConfig config,
    int gen,
    Random customRand) {

    double F1 = config.getF1(gen);
    double F2 = config.getF2(gen);

    // Perform DE/current-to-best/1 mutation (skip best individual search)
    List<double[]> mutantPopulation = MutationDECurrentToBest.mutate(
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

    // Perform binomial crossover (index-based, no intermediate copy)
    List<double[]> trialPopulation = CrossoverDE.crossoverIndexedUnsafe(
      mutantPopulation,
      pop,
      selectIndices,
      config.crossoverRate,
      customRand
    );

    return trialPopulation;
  }

  /**
   * Validates all input parameters for the DE/current-to-best/1 algorithm.
   *
   * @param pop Full parent population
   * @param selectIndices Indices of individuals to evolve
   * @param fitness Fitness list
   * @param config Configuration object
   * @param gen Current generation index
   * @param customRand Random number generator
   * @throws IllegalArgumentException If any parameter is invalid
   */
  private static void validateInputs(List<double[]> pop,
    int[] selectIndices,
    List<Double> fitness,
    DEConfig config,
    int gen,
    Random customRand) {
    // Use common validator for standard checks
    AlgorithmParameterValidator.validateCommonParameters(pop, selectIndices, fitness, config, customRand);

    // Validate generation
    AlgorithmParameterValidator.validateGeneration(gen);

    // DE/current-to-best/1 specific validation: minimum population size
    AlgorithmParameterValidator.validateMinPopulationSize(pop.size(), 3, "DE/current-to-best/1");
  }

  /**
   * Validates input parameters for runWithBest method.
   *
   * @param pop Full parent population
   * @param selectIndices Indices of individuals to evolve
   * @param bestIndex Pre-computed index of the best individual
   * @param config Configuration object
   * @param gen Current generation index
   * @param customRand Random number generator
   * @throws IllegalArgumentException If any parameter is invalid
   */
  private static void validateInputsWithBest(List<double[]> pop,
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

    // DE/current-to-best/1 specific validation: minimum population size
    AlgorithmParameterValidator.validateMinPopulationSize(pop.size(), 3, "DE/current-to-best/1");
  }
}
