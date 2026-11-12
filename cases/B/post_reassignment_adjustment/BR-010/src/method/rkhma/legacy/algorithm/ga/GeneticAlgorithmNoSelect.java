package method.rkhma.legacy.algorithm.ga;
import method.rkhma.legacy.core.config.ConfigValidatable;
import method.rkhma.legacy.algorithm.workspace.GAWorkspace;
import method.rkhma.legacy.common.AlgorithmConstants;
import method.rkhma.legacy.common.AlgorithmParameterValidator;
import method.rkhma.legacy.common.BoundaryHandler;
import method.rkhma.legacy.common.FastGaussian;
import method.rkhma.legacy.utils.FitnessUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Genetic Algorithm with Tournament Selection.
 *
 * This class implements a standard genetic algorithm that uses tournament selection
 * for parent selection, arithmetic crossover, and Gaussian mutation.
 *
 * Key features:
 * - Index-based population handling for high performance
 * - Tournament selection for parent mating
 * - Arithmetic crossover (convex combination)
 * - Gaussian mutation with adaptive dimension selection
 * - Optional elitism
 */
public class GeneticAlgorithmNoSelect {

  // =====================================================
  // GA Algorithm Constants
  // =====================================================

  /** GA Default Crossover Probability */
  public static final double GA_DEFAULT_CROSSOVER_PROB = 0.9;

  /** GA Default Mutation Probability */
  public static final double GA_DEFAULT_MUTATION_PROB = 0.15;

  /** GA Default Sigma Scale */
  public static final double GA_DEFAULT_SIGMA_SCALE = 0.2;

  /** GA Default Tournament Size */
  public static final int GA_DEFAULT_TOURNAMENT_SIZE = 2;

  /** GA default elite preservation count tuned on ID1. */
  public static final int GA_DEFAULT_N_ELITE = 1;

  /** GA default mutation dimension cap tuned on ID1. */
  public static final double GA_DEFAULT_MAX_MUTATION_DIM_RATIO = 0.1;

  /** GA adaptive mutation is opt-in to preserve the fixed default path. */
  public static final boolean GA_DEFAULT_ADAPTIVE_MUTATION = false;

  /** Default late mutation probability; fixed path ignores this value. */
  public static final double GA_DEFAULT_MUTATION_PROB_LATE = GA_DEFAULT_MUTATION_PROB;

  /** Default late sigma scale; fixed path ignores this value. */
  public static final double GA_DEFAULT_SIGMA_SCALE_LATE = GA_DEFAULT_SIGMA_SCALE;

  /** Default late mutation dimension cap; fixed path ignores this value. */
  public static final double GA_DEFAULT_MAX_MUTATION_DIM_RATIO_LATE = GA_DEFAULT_MAX_MUTATION_DIM_RATIO;

  /** Default progress ratio where adaptive mutation starts decaying. */
  public static final double GA_DEFAULT_MUTATION_DECAY_START_RATIO = 0.5;

  /**
   * GA parameter configuration class, centralizing the management of the algorithm's hyperparameters.
   */
  public static class GAConfig implements ConfigValidatable {
    public enum ElitismScope {
      GLOBAL,
      SELECT_LOCAL
    }

    /** Crossover Probability */
    public final double probCrossover;
    /** Mutation Probability */
    public final double probMutation;
    /** Tournament Selection Size */
    public final int kTournament;
    /** Gaussian mutation strength as a fraction of (upper-lower) */
    public final double sigmaScale;
    /** Upper bound of the decision variable */
    public final double upperBounds;
    /** Lower bound of the decision variable */
    public final double lowerBounds;
    /** Number of elite individuals to preserve (0 = no elitism) */
    public final int nElite;
    /** Opt-in elitism scope; GLOBAL preserves the previous whole-population elite behavior. */
    public final ElitismScope elitismScope;
    /** Opt-in cap for mutated dimensions; 1.0 preserves the previous 1..D range. */
    public final double maxMutationDimRatio;
    /** Opt-in switch for late-stage mutation decay. */
    public final boolean adaptiveMutation;
    /** Late-stage mutation probability when adaptive mutation is enabled. */
    public final double probMutationLate;
    /** Late-stage Gaussian mutation strength when adaptive mutation is enabled. */
    public final double sigmaScaleLate;
    /** Late-stage mutation dimension cap when adaptive mutation is enabled. */
    public final double maxMutationDimRatioLate;
    /** Search progress ratio where mutation decay begins. */
    public final double mutationDecayStartRatio;
    /** Maximum generation count used to map current generation to progress. */
    public final int maxGeneration;

    public GAConfig(double probCrossover,
      double probMutation,
      int kTournament,
      double sigmaScale,
      double upperBounds,
      double lowerBounds,
      int nElite) {
      this(probCrossover, probMutation, kTournament, sigmaScale, upperBounds, lowerBounds,
        nElite, ElitismScope.GLOBAL, 1.0);
    }

    public GAConfig(double probCrossover,
      double probMutation,
      int kTournament,
      double sigmaScale,
      double upperBounds,
      double lowerBounds,
      int nElite,
      ElitismScope elitismScope,
      double maxMutationDimRatio) {
      this(probCrossover, probMutation, kTournament, sigmaScale, upperBounds, lowerBounds,
        nElite, elitismScope, maxMutationDimRatio, GA_DEFAULT_ADAPTIVE_MUTATION,
        probMutation, sigmaScale, maxMutationDimRatio, GA_DEFAULT_MUTATION_DECAY_START_RATIO, 1);
    }

    public GAConfig(double probCrossover,
      double probMutation,
      int kTournament,
      double sigmaScale,
      double upperBounds,
      double lowerBounds,
      int nElite,
      ElitismScope elitismScope,
      double maxMutationDimRatio,
      boolean adaptiveMutation,
      double probMutationLate,
      double sigmaScaleLate,
      double maxMutationDimRatioLate,
      double mutationDecayStartRatio,
      int maxGeneration) {
      this.probCrossover = probCrossover;
      this.probMutation = probMutation;
      this.kTournament = kTournament;
      this.sigmaScale = sigmaScale;
      this.upperBounds = upperBounds;
      this.lowerBounds = lowerBounds;
      this.nElite = nElite;
      this.elitismScope = (elitismScope == null) ? ElitismScope.GLOBAL : elitismScope;
      this.maxMutationDimRatio = maxMutationDimRatio;
      this.adaptiveMutation = adaptiveMutation;
      this.probMutationLate = probMutationLate;
      this.sigmaScaleLate = sigmaScaleLate;
      this.maxMutationDimRatioLate = maxMutationDimRatioLate;
      this.mutationDecayStartRatio = mutationDecayStartRatio;
      this.maxGeneration = Math.max(1, maxGeneration);

      // Validate configuration and throw exception if invalid
      ValidationResult result = validate();
      if (!result.isValid()) {
        throw new IllegalArgumentException(result.getErrorMessage());
      }
    }

    /**
     * Provide a set of default parameters for convenient direct use.
     */
    public static GAConfig defaultConfig() {
      return new GAConfig(
        GA_DEFAULT_CROSSOVER_PROB,
        GA_DEFAULT_MUTATION_PROB,
        GA_DEFAULT_TOURNAMENT_SIZE,
        GA_DEFAULT_SIGMA_SCALE,
        AlgorithmConstants.DEFAULT_BOUND,
        -AlgorithmConstants.DEFAULT_BOUND,
        GA_DEFAULT_N_ELITE,
        ElitismScope.GLOBAL,
        GA_DEFAULT_MAX_MUTATION_DIM_RATIO,
        GA_DEFAULT_ADAPTIVE_MUTATION,
        GA_DEFAULT_MUTATION_PROB_LATE,
        GA_DEFAULT_SIGMA_SCALE_LATE,
        GA_DEFAULT_MAX_MUTATION_DIM_RATIO_LATE,
        GA_DEFAULT_MUTATION_DECAY_START_RATIO,
        1
      );
    }

    public double effectiveProbMutation(int gen) {
      return interpolateAdaptiveValue(probMutation, probMutationLate, gen);
    }

    public double effectiveSigmaScale(int gen) {
      return interpolateAdaptiveValue(sigmaScale, sigmaScaleLate, gen);
    }

    public double effectiveMaxMutationDimRatio(int gen) {
      return interpolateAdaptiveValue(maxMutationDimRatio, maxMutationDimRatioLate, gen);
    }

    private double interpolateAdaptiveValue(double earlyValue, double lateValue, int gen) {
      if (!adaptiveMutation) {
        return earlyValue;
      }
      double progress = maxGeneration <= 1
        ? 1.0
        : Math.min(1.0, Math.max(0.0, gen / (double) (maxGeneration - 1)));
      if (progress <= mutationDecayStartRatio) {
        return earlyValue;
      }
      double denominator = Math.max(1.0e-12, 1.0 - mutationDecayStartRatio);
      double decayRatio = Math.min(1.0, (progress - mutationDecayStartRatio) / denominator);
      return earlyValue + (lateValue - earlyValue) * decayRatio;
    }

    @Override
    public ValidationResult validate() {
      ValidationResultBuilder builder = new ValidationResultBuilder();

      builder.checkRange("probCrossover", probCrossover, 0.0, 1.0);
      builder.checkRange("probMutation", probMutation, 0.0, 1.0);
      builder.checkPositiveInt("kTournament", kTournament);
      builder.checkNonNegative("sigmaScale", sigmaScale);
      builder.checkRange("probMutationLate", probMutationLate, 0.0, 1.0);
      builder.checkNonNegative("sigmaScaleLate", sigmaScaleLate);

      if (upperBounds <= lowerBounds) {
        builder.addError(String.format(
          "Upper bound (%.4f) must be greater than lower bound (%.4f)",
          upperBounds, lowerBounds));
      }

      if (nElite < 0) {
        builder.addError(String.format(
          "Number of elite individuals must be non-negative, got: %d", nElite));
      }
      builder.checkRange("maxMutationDimRatio", maxMutationDimRatio, 0.0, 1.0);
      builder.checkRange("maxMutationDimRatioLate", maxMutationDimRatioLate, 0.0, 1.0);
      builder.checkRange("mutationDecayStartRatio", mutationDecayStartRatio, 0.0, 1.0);
      builder.checkPositiveInt("maxGeneration", maxGeneration);

      return builder.build();
    }
  }

  /**
   * Execute GA using index-based selection (high-performance version).
   *
   * This method generates offspring for the specified indices using tournament selection,
   * arithmetic crossover, and Gaussian mutation.
   *
   * @param pop Full parent population (must not be null or empty)
   * @param selectIndices Indices of individuals to evolve (must be valid indices into pop)
   * @param popFit Fitness values for the entire population (must match pop size)
   * @param config Configuration object containing algorithm parameters
   * @param gen Current generation number (for potential adaptive behavior)
   * @param rand Random number generator
   * @return Generated offspring population with same size as selectIndices
   * @throws IllegalArgumentException If input parameters are invalid
   */
  public static List<double[]> run(List<double[]> pop,
    int[] selectIndices,
    List<Double> popFit,
    GAConfig config,
    int gen,
    Random rand) {
    double[] popFitArray = new double[popFit.size()];
    for (int i = 0; i < popFit.size(); i++) {
      popFitArray[i] = popFit.get(i);
    }
    return run(pop, selectIndices, popFitArray, config, gen, rand);
  }

  public static List<double[]> runInto(List<double[]> pop,
    int[] selectIndices,
    List<Double> popFit,
    GAConfig config,
    int gen,
    Random rand,
    GAWorkspace workspace) {
    if (popFit == null) {
      throw new IllegalArgumentException("popFit cannot be null");
    }
    int nPop = popFit.size();
    int d = pop != null && !pop.isEmpty() ? pop.get(0).length : 0;
    workspace.ensureCapacity(selectIndices != null ? selectIndices.length : 0, d, nPop);
    for (int i = 0; i < nPop; i++) {
      workspace.fitnessBuffer[i] = popFit.get(i);
    }
    return runInto(pop, selectIndices, workspace.fitnessBuffer, config, gen, rand, workspace);
  }

  /**
   * Execute GA using index-based selection (high-performance version).
   *
   * This method generates offspring for the specified indices using tournament selection,
   * arithmetic crossover, and Gaussian mutation.
   *
   * @param pop Full parent population (must not be null or empty)
   * @param selectIndices Indices of individuals to evolve (must be valid indices into pop)
   * @param popFit Fitness values for the entire population (must match pop size)
   * @param config Configuration object containing algorithm parameters
   * @param rand Random number generator
   * @return Generated offspring population with same size as selectIndices
   * @throws IllegalArgumentException If input parameters are invalid
   */
  public static List<double[]> run(List<double[]> pop,
    int[] selectIndices,
    double[] popFit,
    GAConfig config,
    Random rand) {
    return run(pop, selectIndices, popFit, config, 0, rand);
  }

  public static List<double[]> run(List<double[]> pop,
    int[] selectIndices,
    double[] popFit,
    GAConfig config,
    int gen,
    Random rand) {

    // Step 1: Validate input parameters
    validateParameters(pop, selectIndices, popFit, config, rand);

    int nSelect = selectIndices.length;
    int D = pop.get(0).length;

    // Step 2: Apply elitism if configured
    List<double[]> offspring = new ArrayList<>(nSelect);
    int startIdx = 0;

    if (config.nElite > 0 && nSelect > config.nElite) {
      addEliteIndividuals(pop, selectIndices, popFit, config, offspring);
      startIdx = config.nElite;
    }

    // Step 3: Crossover phase - generate offspring pairs
    int[] tournamentScratch = new int[pop.size()];
    for (int i = startIdx; i < nSelect; i += 2) {
      if (i + 1 < nSelect) {
        // Process a pair
        double[] child1, child2;

        if (rand.nextDouble() < config.probCrossover) {
          // Perform crossover: select two parents via tournament
          double[] parent1 = pop.get(selectTournamentParent(popFit, config.kTournament, rand, tournamentScratch));
          double[] parent2 = pop.get(selectTournamentParent(popFit, config.kTournament, rand, tournamentScratch));

          // Arithmetic crossover
          double alpha = rand.nextDouble();
          child1 = new double[D];
          child2 = new double[D];

          for (int d = 0; d < D; d++) {
            child1[d] = alpha * parent1[d] + (1.0 - alpha) * parent2[d];
            child2[d] = alpha * parent2[d] + (1.0 - alpha) * parent1[d];
          }
        } else {
          // No crossover: copy parents directly (lazy copy optimization)
          int idx1 = selectIndices[i];
          int idx2 = selectIndices[i + 1];
          child1 = pop.get(idx1).clone();
          child2 = pop.get(idx2).clone();
        }

        offspring.add(child1);
        offspring.add(child2);
      } else {
        // Handle odd case: last individual
        // Apply mutation with higher probability or use best individual
        int idx = selectIndices[i];
        offspring.add(pop.get(idx).clone());
      }
    }

    // Step 4: Mutation phase
    double searchRange = config.upperBounds - config.lowerBounds;
    double probMutation = config.effectiveProbMutation(gen);
    double sigma = config.effectiveSigmaScale(gen) * searchRange;
    double maxMutationDimRatio = config.effectiveMaxMutationDimRatio(gen);
    int[] dimensionScratch = new int[D];

    for (int i = startIdx; i < offspring.size(); i++) {
      if (rand.nextDouble() < probMutation) {
        double[] individual = offspring.get(i);

        // Randomly choose 1 to D dimensions to mutate
        int maxMutationDims = Math.max(1, (int) (D * maxMutationDimRatio));
        int nMutateDims = rand.nextInt(maxMutationDims) + 1;

        sampleDimensions(D, nMutateDims, rand, dimensionScratch);

        // Gaussian perturbation on selected dimensions
        for (int j = 0; j < nMutateDims; j++) {
          int dim = dimensionScratch[j];
          double gaussian = FastGaussian.isEnabled()
            ? FastGaussian.nextGaussian(rand)
            : rand.nextGaussian();
          individual[dim] = BoundaryHandler.clip(
            individual[dim] + sigma * gaussian,
            config.lowerBounds,
            config.upperBounds
          );
        }
      }
    }

    return offspring;
  }

  public static List<double[]> runInto(List<double[]> pop,
    int[] selectIndices,
    double[] popFit,
    GAConfig config,
    Random rand,
    GAWorkspace workspace) {
    return runInto(pop, selectIndices, popFit, config, 0, rand, workspace);
  }

  public static List<double[]> runInto(List<double[]> pop,
    int[] selectIndices,
    double[] popFit,
    GAConfig config,
    int gen,
    Random rand,
    GAWorkspace workspace) {

    validateParameters(pop, selectIndices, popFit, config, rand);
    if (workspace == null) {
      throw new IllegalArgumentException("workspace cannot be null");
    }

    int nSelect = selectIndices.length;
    int nPop = pop.size();
    int D = pop.get(0).length;
    workspace.ensureCapacity(nSelect, D, nPop);

    int startIdx = 0;
    if (config.nElite > 0 && nSelect > config.nElite) {
      addEliteIndividualsInto(pop, selectIndices, popFit, config, workspace, D);
      startIdx = config.nElite;
    }

    for (int i = startIdx; i < nSelect; i += 2) {
      if (i + 1 < nSelect) {
        double[] child1 = workspace.offspringBuffer[i];
        double[] child2 = workspace.offspringBuffer[i + 1];

        if (rand.nextDouble() < config.probCrossover) {
          double[] parent1 = pop.get(selectTournamentParent(popFit, config.kTournament, rand, workspace.tournamentScratch));
          double[] parent2 = pop.get(selectTournamentParent(popFit, config.kTournament, rand, workspace.tournamentScratch));

          double alpha = rand.nextDouble();
          for (int d = 0; d < D; d++) {
            child1[d] = alpha * parent1[d] + (1.0 - alpha) * parent2[d];
            child2[d] = alpha * parent2[d] + (1.0 - alpha) * parent1[d];
          }
        } else {
          System.arraycopy(pop.get(selectIndices[i]), 0, child1, 0, D);
          System.arraycopy(pop.get(selectIndices[i + 1]), 0, child2, 0, D);
        }
      } else {
        System.arraycopy(pop.get(selectIndices[i]), 0, workspace.offspringBuffer[i], 0, D);
      }
    }

    double searchRange = config.upperBounds - config.lowerBounds;
    double probMutation = config.effectiveProbMutation(gen);
    double sigma = config.effectiveSigmaScale(gen) * searchRange;
    double maxMutationDimRatio = config.effectiveMaxMutationDimRatio(gen);

    for (int i = startIdx; i < nSelect; i++) {
      if (rand.nextDouble() < probMutation) {
        double[] individual = workspace.offspringBuffer[i];

        int maxMutationDims = Math.max(1, (int) (D * maxMutationDimRatio));
        int nMutateDims = rand.nextInt(maxMutationDims) + 1;

        sampleDimensions(D, nMutateDims, rand, workspace.dimensionScratch);

        for (int j = 0; j < nMutateDims; j++) {
          int dim = workspace.dimensionScratch[j];
          double gaussian = FastGaussian.isEnabled()
            ? FastGaussian.nextGaussian(rand)
            : rand.nextGaussian();
          individual[dim] = BoundaryHandler.clip(
            individual[dim] + sigma * gaussian,
            config.lowerBounds,
            config.upperBounds
          );
        }
      }
    }

    return workspace.offspringView(nSelect);
  }

  private static void addEliteIndividualsInto(
    List<double[]> pop,
    int[] selectIndices,
    double[] popFit,
    GAConfig config,
    GAWorkspace workspace,
    int dimension) {
    int nElite = config.nElite;
    if (config.elitismScope == GAConfig.ElitismScope.SELECT_LOCAL) {
      selectStableTopK(selectIndices, popFit, nElite, workspace.eliteScratch);
    } else {
      selectStableTopK(popFit.length, popFit, nElite, workspace.eliteScratch);
    }
    for (int i = 0; i < nElite; i++) {
      System.arraycopy(pop.get(workspace.eliteScratch[i]), 0, workspace.offspringBuffer[i], 0, dimension);
    }
  }

  private static void selectStableTopK(int[] candidateIndices, double[] popFit, int k, int[] out) {
    for (int rank = 0; rank < k; rank++) {
      int bestIndex = -1;
      double bestFitness = Double.NEGATIVE_INFINITY;
      for (int candidate : candidateIndices) {
        if (contains(out, rank, candidate)) {
          continue;
        }
        double fitness = popFit[candidate];
        if (bestIndex < 0 || fitness > bestFitness) {
          bestFitness = fitness;
          bestIndex = candidate;
        }
      }
      out[rank] = bestIndex;
    }
  }

  private static void selectStableTopK(int nPop, double[] popFit, int k, int[] out) {
    for (int rank = 0; rank < k; rank++) {
      int bestIndex = -1;
      double bestFitness = Double.NEGATIVE_INFINITY;
      for (int candidate = 0; candidate < nPop; candidate++) {
        if (contains(out, rank, candidate)) {
          continue;
        }
        double fitness = popFit[candidate];
        if (bestIndex < 0 || fitness > bestFitness) {
          bestFitness = fitness;
          bestIndex = candidate;
        }
      }
      out[rank] = bestIndex;
    }
  }

  private static boolean contains(int[] values, int length, int target) {
    for (int i = 0; i < length; i++) {
      if (values[i] == target) {
        return true;
      }
    }
    return false;
  }

  private static void addEliteIndividuals(
    List<double[]> pop,
    int[] selectIndices,
    double[] popFit,
    GAConfig config,
    List<double[]> offspring) {
    if (config.elitismScope == GAConfig.ElitismScope.SELECT_LOCAL) {
      List<FitnessUtils.IndexFitnessPair> selectedPairs = new ArrayList<>(selectIndices.length);
      for (int idx : selectIndices) {
        selectedPairs.add(new FitnessUtils.IndexFitnessPair(idx, popFit[idx]));
      }
      selectedPairs.sort((a, b) -> Double.compare(b.getFitness(), a.getFitness()));
      for (int i = 0; i < config.nElite; i++) {
        offspring.add(pop.get(selectedPairs.get(i).getIndex()).clone());
      }
      return;
    }

    List<Double> popFitList = new ArrayList<>(popFit.length);
    for (double fit : popFit) {
      popFitList.add(fit);
    }
    List<FitnessUtils.IndexFitnessPair> elitePairs = FitnessUtils.getTopK(popFitList, config.nElite);
    for (FitnessUtils.IndexFitnessPair pair : elitePairs) {
      offspring.add(pop.get(pair.getIndex()).clone());
    }
  }

  /**
   * Validates input parameters for the run method.
   */
  private static void validateParameters(List<double[]> pop,
    int[] selectIndices,
    double[] popFit,
    GAConfig config,
    Random rand) {
    // Use common validator for standard checks
    AlgorithmParameterValidator.validateCommonParameters(pop, selectIndices, popFit, config, rand);

    // GA-specific validation: tournament size
    if (config.kTournament > pop.size()) {
      throw new IllegalArgumentException(
        String.format("Tournament size (%d) cannot exceed population size (%d)",
        config.kTournament, pop.size()));
    }

    // GA-specific validation: elitism
    if (config.nElite > pop.size()) {
      throw new IllegalArgumentException(
        String.format("Number of elite individuals (%d) cannot exceed population size (%d)",
        config.nElite, pop.size()));
    }
  }

  /**
   * Select one parent by tournament without List/Set allocation on the GA hot path.
   */
  private static int selectTournamentParent(double[] popFit, int kTournament, Random rand, int[] sampled) {
    int nPop = popFit.length;
    int effectiveK = Math.min(kTournament, nPop);
    int bestIndex = -1;
    double bestFitness = Double.NEGATIVE_INFINITY;

    if (effectiveK >= nPop / 2) {
      for (int i = 0; i < nPop; i++) {
        sampled[i] = i;
      }
      for (int i = 0; i < effectiveK; i++) {
        int j = i + rand.nextInt(nPop - i);
        int tmp = sampled[i];
        sampled[i] = sampled[j];
        sampled[j] = tmp;
        int candidate = sampled[i];
        double fitness = popFit[candidate];
        if (bestIndex < 0 || fitness > bestFitness || (fitness == bestFitness && candidate < bestIndex)) {
          bestFitness = fitness;
          bestIndex = candidate;
        }
      }
      return bestIndex;
    }

    for (int i = 0; i < effectiveK; i++) {
      int candidate;
      boolean duplicate;
      do {
        candidate = rand.nextInt(nPop);
        duplicate = false;
        for (int j = 0; j < i; j++) {
          if (sampled[j] == candidate) {
            duplicate = true;
            break;
          }
        }
      } while (duplicate);

      sampled[i] = candidate;
      double fitness = popFit[candidate];
      if (bestIndex < 0 || fitness > bestFitness || (fitness == bestFitness && candidate < bestIndex)) {
        bestFitness = fitness;
        bestIndex = candidate;
      }
    }

    return bestIndex;
  }

  /**
   * Sample k distinct dimensions into the first k slots of scratch.
   */
  private static void sampleDimensions(int n, int k, Random rand, int[] scratch) {
    for (int i = 0; i < n; i++) {
      scratch[i] = i;
    }

    for (int i = 0; i < k; i++) {
      int j = i + rand.nextInt(n - i);
      int tmp = scratch[i];
      scratch[i] = scratch[j];
      scratch[j] = tmp;
    }
  }

}
