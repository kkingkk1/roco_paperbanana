package method.rkhma.legacy.algorithm.eda;
import method.rkhma.legacy.common.AlgorithmConstants;
import method.rkhma.legacy.common.AlgorithmParameterValidator;
import method.rkhma.legacy.common.BoundaryHandler;
import method.rkhma.legacy.core.config.ConfigValidatable;
import method.rkhma.legacy.algorithm.workspace.EDAWorkspace;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import method.rkhma.legacy.utils.FitnessUtils;

/**
 * Gaussian Sampling-based Estimation of Distribution Algorithm (EDA).
 *
 * This class implements the EDA algorithm using Gaussian sampling with index-based selection
 * for improved performance and consistency with DE series algorithms.
 */
public class EDAGaussSam {

  // =====================================================
  // EDA Algorithm Constants
  // =====================================================

  /** EDA default elite ratio promoted after ID1/ID3/ID5 checks on 2026-05-27. */
  public static final double EDA_DEFAULT_ELITE_RATIO = 0.12;

  /** EDA Default Sigma Scale */
  public static final double EDA_DEFAULT_SIGMA_SCALE = 1.0;

  /** EDA default minimum sigma ratio promoted after ID1/ID3/ID5 checks on 2026-05-27. */
  public static final double EDA_DEFAULT_MIN_SIGMA_RATIO = 0.002;

  /** EDA Default Boundary Repair Ratio */
  public static final double EDA_BOUNDARY_REPAIR_RATIO = 0.25;

  /**
   * EDA parameter configuration class, centralizing the management of algorithm hyperparameters.
   */
  public static class EDAConfig implements ConfigValidatable {
    /** Elite Ratio*/
    public final double kElite;
    /** Variance scaling factor (controls exploration intensity) */
    public final double sigmaScale;
    /** Upper bound of the decision variable */
    public final double upperBound;
    /** Lower bound of the decision variable */
    public final double lowerBound;
    /** Minimum sigma ratio (relative to search range) to prevent premature convergence */
    public final double minSigmaRatio;
    /** Opt-in multiplier for min sigma after stagnation; 1.0 keeps the previous behavior. */
    public final double stagnationBoostRatio;
    /** Opt-in stagnation threshold; 0 disables stagnation-aware sigma boosting. */
    public final int stagnationGenerations;

    public EDAConfig(double kElite, double sigmaScale, double upperBound, double lowerBound, double minSigmaRatio) {
      this(kElite, sigmaScale, upperBound, lowerBound, minSigmaRatio, 1.0, 0);
    }

    public EDAConfig(double kElite,
      double sigmaScale,
      double upperBound,
      double lowerBound,
      double minSigmaRatio,
      double stagnationBoostRatio,
      int stagnationGenerations) {
      this.kElite = kElite;
      this.sigmaScale = sigmaScale;
      this.upperBound = upperBound;
      this.lowerBound = lowerBound;
      this.minSigmaRatio = minSigmaRatio;
      this.stagnationBoostRatio = stagnationBoostRatio;
      this.stagnationGenerations = stagnationGenerations;
    }

    /**
     * Provide a set of default parameters for convenient direct use.
     */
    public static EDAConfig defaultConfig() {
      return new EDAConfig(
        EDA_DEFAULT_ELITE_RATIO,
        EDA_DEFAULT_SIGMA_SCALE,
        AlgorithmConstants.DEFAULT_BOUND,
        -AlgorithmConstants.DEFAULT_BOUND,
        EDA_DEFAULT_MIN_SIGMA_RATIO,
        1.0,
        0
      );
    }

    /**
     * Validates the configuration parameters.
     *
     * @return ValidationResult containing validation errors or success
     */
    @Override
    public ConfigValidatable.ValidationResult validate() {
      ConfigValidatable.ValidationResultBuilder builder = new ConfigValidatable.ValidationResultBuilder();

      builder.checkRange("kElite", kElite, 0.0, 1.0);
      builder.checkPositive("sigmaScale", sigmaScale);
      builder.checkRange("minSigmaRatio", minSigmaRatio, 0.0, 1.0);
      builder.checkPositive("stagnationBoostRatio", stagnationBoostRatio);
      builder.checkNonNegative("stagnationGenerations", stagnationGenerations);

      if (lowerBound >= upperBound) {
        builder.addError(String.format(
          "lowerBound must be < upperBound, got: [%.4f, %.4f]",
          lowerBound, upperBound));
      }

      return builder.build();
    }
  }

  /**
   * Execute EDA using index-based selection (high-performance version).
   *
   * <p>This is the <strong>recommended method</strong> for all use cases. It provides
   * significantly better performance compared to List-based versions by avoiding
   * expensive array comparisons during individual lookup.
   *
   * <p>EDA algorithm workflow:
   * <pre>
   * 1. Select elite individuals based on fitness (top kElite%)
   * 2. Estimate Gaussian distribution (mean μ and std deviation σ)
   * 3. Sample new individuals from N(μ, σ²)
   * 4. Repair boundary violations
   * </pre>
   *
   * <p><strong>Performance characteristics:</strong>
   * <ul>
   *   <li>Index lookup: O(1) constant time</li>
   *   <li>Elite selection: O(N log N) for sorting</li>
   *   <li>Model estimation: O(nElite × D)</li>
   *   <li>Sampling: O(M × D)</li>
   * </ul>
   *
   * <p><strong>Recommended parameter ranges:</strong>
   * <ul>
   *   <li>kElite (elite ratio): 0.1 - 0.3 (typical: 0.12)</li>
   *   <li>sigmaScale (exploration factor): 0.5 - 2.0 (typical: 1.0)</li>
   *   <li>minSigmaRatio (minimum variance): 0.001 - 0.01 (typical: 0.002)</li>
   * </ul>
   *
   * <p><strong>Usage example:</strong>
   * <pre>
   * // Sample entire population
   * int[] allIndices = IntStream.range(0, pop.size()).toArray();
   * List&lt;double[]&gt; samples = EDAGaussSam.run(pop, popFit, allIndices, config, rand);
   *
   * // Sample subset [10, 20)
   * int[] subset = IntStream.range(10, 20).toArray();
   * List&lt;double[]&gt; samples = EDAGaussSam.run(pop, popFit, subset, config, rand);
   * </pre>
   *
   * @param pop Full parent population (must not be null or empty, each element is a double[] of dimension D)
   * @param popFit Fitness array (must not be null, length must match pop.size())
   * @param selectIndices Indices of individuals to be replaced by new samples (must be valid indices into pop, not null or empty)
   * @param config Configuration object containing algorithm parameters (must not be null)
   * @param rand Custom random number generator (must not be null)
   * @return Newly sampled population with same size as selectIndices
   * @throws IllegalArgumentException If input parameters are invalid
   */
  public static List<double[]> run(List<double[]> pop,
    List<Double> popFit,
    int[] selectIndices,
    EDAConfig config,
    Random rand) {
    return run(pop, popFit, selectIndices, config, rand, 0);
  }

  public static List<double[]> run(List<double[]> pop,
    List<Double> popFit,
    int[] selectIndices,
    EDAConfig config,
    Random rand,
    int stagnationCount) {
    return run(pop, popFit, selectIndices, config, rand, stagnationCount, null);
  }

  public static List<double[]> run(List<double[]> pop,
    List<Double> popFit,
    int[] selectIndices,
    EDAConfig config,
    Random rand,
    int stagnationCount,
    EDAWorkspace workspace) {

    // Validate input parameters
    validateParameters(pop, popFit, selectIndices, config, rand);

    int N = pop.size();
    int D = pop.get(0).length;
    int M = selectIndices.length;

    // ========================================
    // Step 1: Elite selection
    // ========================================

    int nElite = (int) Math.max(1, Math.round(N * config.kElite));
    if (workspace != null) {
      workspace.ensureCapacity(nElite, D);
    }

    List<FitnessUtils.IndexFitnessPair> fitnessList = FitnessUtils.sortIndexFitnessDesc(popFit);

    for (int i = 0; i < nElite; i++) {
      int originalIndex = fitnessList.get(i).getIndex();
      if (workspace != null) {
        workspace.eliteIndices[i] = originalIndex;
      }
    }

    // ========================================
    // Step 2: Model estimation (compute mean and std dev)
    // ========================================

    double[] mu = workspace != null ? workspace.mu : new double[D];     // Mean vector
    double[] sigma = workspace != null ? workspace.sigma : new double[D];  // Standard deviation vector
    double[] sumSq = workspace != null ? workspace.sumSq : new double[D];
    Arrays.fill(mu, 0, D, 0.0);
    Arrays.fill(sumSq, 0, D, 0.0);

    for (int i = 0; i < nElite; i++) {
      int eliteIndex = workspace != null ? workspace.eliteIndices[i] : fitnessList.get(i).getIndex();
      double[] elite = pop.get(eliteIndex);
      for (int d = 0; d < D; d++) {
        mu[d] += elite[d];
      }
    }
    for (int d = 0; d < D; d++) {
      mu[d] /= nElite;
    }
    for (int i = 0; i < nElite; i++) {
      int eliteIndex = workspace != null ? workspace.eliteIndices[i] : fitnessList.get(i).getIndex();
      double[] elite = pop.get(eliteIndex);
      for (int d = 0; d < D; d++) {
        double diff = elite[d] - mu[d];
        sumSq[d] += diff * diff;
      }
    }
    for (int d = 0; d < D; d++) {
      sigma[d] = (nElite > 1) ? Math.sqrt(sumSq[d] / (nElite - 1)) : 0.0;
    }

    // Scale standard deviation and apply minimum threshold
    double range = config.upperBound - config.lowerBound;
    double minSigma = range * config.minSigmaRatio;
    // Opt-in stagnation boost; defaults keep this multiplier disabled.
    if (config.stagnationGenerations > 0 && stagnationCount >= config.stagnationGenerations) {
      minSigma *= config.stagnationBoostRatio;
    }

    for (int d = 0; d < D; d++) {
      sigma[d] = Math.max(sigma[d] * config.sigmaScale, minSigma);
    }

    // ========================================
    // Step 3: Sample new individuals from the Gaussian model
    // ========================================

    List<double[]> popSample = new ArrayList<>(M);
    for (int s = 0; s < M; s++) {
      double[] newIndividual = new double[D];

      // Independently sample each dimension
      for (int d = 0; d < D; d++) {
        newIndividual[d] = mu[d] + sigma[d] * rand.nextGaussian();
      }

      popSample.add(newIndividual);
    }

    // ========================================
    // Step 4: Boundary repair (random repair strategy)
    // ========================================

    BoundaryHandler.randomRepairPopulation(popSample, config.lowerBound, config.upperBound,
      EDA_BOUNDARY_REPAIR_RATIO, rand);

    return popSample;
  }

  /**
   * Validates input parameters for the index-based run method.
   *
   * @param pop Full parent population
   * @param popFit Fitness array
   * @param selectIndices Indices of individuals to evolve
   * @param config Configuration object
   * @param rand Random number generator
   * @throws IllegalArgumentException If any parameter is invalid
   */
  private static void validateParameters(List<double[]> pop,
    List<Double> popFit,
    int[] selectIndices,
    EDAConfig config,
    Random rand) {
    // Use common validation utilities
    AlgorithmParameterValidator.validateCommonParameters(pop, selectIndices, popFit, config, rand);

    // Validate config parameters using ConfigValidatable interface
    ConfigValidatable.ValidationResult validationResult = config.validate();
    if (!validationResult.isValid()) {
      throw new IllegalArgumentException("Invalid configuration: " + validationResult.getErrorMessage());
    }

    // Validate that population is large enough for EDA (needs at least 2 individuals for elite selection)
    AlgorithmParameterValidator.validateMinPopulationSize(pop.size(), 2, "EDA");
  }
}
