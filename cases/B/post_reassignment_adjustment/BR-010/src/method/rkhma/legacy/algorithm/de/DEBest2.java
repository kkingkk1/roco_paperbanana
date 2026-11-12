package method.rkhma.legacy.algorithm.de;
import method.rkhma.legacy.operator.mutation.MutationDEBest2;
import method.rkhma.legacy.operator.crossover.CrossoverDE;
import method.rkhma.legacy.operator.mutation.DEUtils;
import method.rkhma.legacy.algorithm.workspace.DEWorkspace;
import method.rkhma.legacy.common.AlgorithmParameterValidator;
import method.rkhma.legacy.common.BoundaryHandler;
import java.util.List;
import java.util.Random;

/**

 * @see MutationDEBest2
 * @see CrossoverDE
 * @see DEBest1
 */
public final class DEBest2 {

  /**
   * Execute the DE/best/2 strategy using index-based selection (high-performance version).
   *
   * @param pop Full parent population
   * @param selectIndices Indices of individuals to operate on
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

    // ========================================
    // Step 1: Parameter validation
    // ========================================

    // Validate input parameters
    validateParameters(pop, selectIndices, fitness, config, gen, customRand);

    int bestIndex = DEUtils.findBestIndexMax(fitness);
    return runWithBestUnsafe(pop, selectIndices, bestIndex, config, gen, customRand);
  }

  /**
   * Execute DE/best/2 with a caller-provided best index.
   */
  public static List<double[]> runWithBest(List<double[]> pop,
    int[] selectIndices,
    int bestIndex,
    DEConfig config,
    int gen,
    Random customRand) {

    validateParameters(pop, selectIndices, null, config, gen, customRand);
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

    // ========================================
    // Step 3: Perform DE/best/2 mutation
    // ========================================

    List<double[]> mutantPopulation = MutationDEBest2.mutate(
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
    // Step 4: Perform binomial crossover (index-based, no intermediate copy)
    // ========================================

    List<double[]> trialPopulation = CrossoverDE.crossoverIndexedUnsafe(
      mutantPopulation,
      pop,
      selectIndices,
      config.crossoverRate,
      customRand
    );

    // ========================================
    // Step 5: Return trial population
    // ========================================

    return trialPopulation;
  }

  public static List<double[]> runInto(List<double[]> pop,
    int[] selectIndices,
    int bestIndex,
    DEConfig config,
    int gen,
    Random customRand,
    DEWorkspace workspace) {

    validateParameters(pop, selectIndices, null, config, gen, customRand);
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
      int r4 = DEUtils.selectRandomIndex(pop.size(), targetIndex, r1, r2, r3, -1, -1, customRand);
      double[] mutant = workspace.mutantBuffer[i];
      double[] xr1 = pop.get(r1);
      double[] xr2 = pop.get(r2);
      double[] xr3 = pop.get(r3);
      double[] xr4 = pop.get(r4);
      double[] target = pop.get(targetIndex);

      for (int j = 0; j < d; j++) {
        double mutatedValue = best[j] + f1 * (xr1[j] - xr2[j]) + f2 * (xr3[j] - xr4[j]);
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
   * @param fitness Fitness list
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
    if (fitness == null) {
      AlgorithmParameterValidator.validateCommonParameters(pop, selectIndices, config, customRand);
    } else {
      AlgorithmParameterValidator.validateCommonParameters(pop, selectIndices, fitness, config, customRand);
    }

    // Validate generation
    AlgorithmParameterValidator.validateGeneration(gen);

    // DE/best/2 specific validation: minimum population size
    AlgorithmParameterValidator.validateMinPopulationSize(pop.size(), 5, "DE/best/2");
  }
}
