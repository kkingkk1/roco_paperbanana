package method.rkhma.legacy.algorithm.sa;
import method.rkhma.legacy.common.AlgorithmConstants;
import method.rkhma.legacy.common.AlgorithmParameterValidator;
import method.rkhma.legacy.common.BoundaryHandler;
import method.rkhma.legacy.common.FastGaussian;
import method.rkhma.legacy.core.config.ConfigValidatable;
import method.rkhma.legacy.algorithm.workspace.SAWorkspace;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.Function;

import method.rkhma.legacy.model.Satellite;
import method.rkhma.legacy.model.Task;
import method.rkhma.legacy.operator.insertion.InsertionMode;
import method.rkhma.legacy.utils.PlanUtils;

public class SACont {

  // =====================================================
  // SA Algorithm Constants
  // =====================================================

  /** SA Default Generations */
  public static final int SA_DEFAULT_GENERATIONS = 5;

  /** Opt-in fast SA inner-generation override; absent means the default generation count. */
  public static final String SA_FAST_GENERATIONS_PROPERTY = "hybcec.sa.genSAFast";

  /** Opt-in SA cooling-rate override; absent means the default freeze rate. */
  public static final String SA_FREEZE_RATE_PROPERTY = "sa.freezeRate";

  /** Opt-in SA temperature override; absent means the default temperature. */
  public static final String SA_TEMPERATURE_PROPERTY = "sa.temperature";

  /** Opt-in SA temperature schedule override; accepts 0/1/2 or CONSTANT/GEOMETRIC/LOGARITHMIC. */
  public static final String SA_TEMPERATURE_SCHEDULE_PROPERTY = "sa.temperatureSchedule";

  /** Opt-in SA geometric temperature alpha override; absent means the default alpha. */
  public static final String SA_TEMPERATURE_ALPHA_PROPERTY = "sa.temperatureAlpha";

  /** Opt-in SA logarithmic temperature offset override; absent means the default offset. */
  public static final String SA_TEMPERATURE_LOG_OFFSET_PROPERTY = "sa.temperatureLogOffset";

  /** Opt-in SA adaptive sigma override; absent means false. */
  public static final String SA_ADAPTIVE_SIGMA_PROPERTY = "sa.adaptiveSigma";

  /** SA Default Freeze Rate */
  public static final double SA_DEFAULT_FREEZE_RATE = 0.99;

  /** SA Default Temperature */
  public static final double SA_DEFAULT_TEMPERATURE = 0.5;

  /** SA Default Temperature Alpha */
  public static final double SA_DEFAULT_TEMPERATURE_ALPHA = 0.95;

  /** SA Default Logarithmic Temperature Offset */
  public static final double SA_DEFAULT_TEMPERATURE_LOG_OFFSET = 3.0;

  public static final int SA_TEMPERATURE_SCHEDULE_CONSTANT = 0;

  public static final int SA_TEMPERATURE_SCHEDULE_GEOMETRIC = 1;

  public static final int SA_TEMPERATURE_SCHEDULE_LOGARITHMIC = 2;

  public static final int SA_DEFAULT_TEMPERATURE_SCHEDULE = SA_TEMPERATURE_SCHEDULE_LOGARITHMIC;

  /** SA Initial Sigma Ratio (relative to the search space range) */
  public static final double SA_INITIAL_SIGMA_RATIO = 0.2;

  private static final double MIN_TEMPERATURE = 1.0e-9;

  /**
   * Simulated Annealing Result Wrapper Class.
   */
  public static class SAResult {
    /** Updated historical best solution */
    public List<double[]> sBest;

    /** Updated historical best value */
    public List<Double> fBest;

    /** Updated step size factor */
    public List<double[]> sigma;

    /**
     * Construct a simulated annealing result object.
     */
    public SAResult(List<double[]> sBest, List<Double> fBest, List<double[]> sigma) {
      this.sBest = sBest;
      this.fBest = fBest;
      this.sigma = sigma;
    }
  }

  /**
   * SA parameter configuration class, centralizing the management of algorithm hyperparameters.
   */
  public static class SAConfig implements ConfigValidatable {
    /** Number of iterations in simulated annealing */
    public final int genSA;
    /** Cooling rate (step factor decay rate) */
    public final double freezeRate;
    /** Temperature parameter for Metropolis acceptance criterion */
    public final double temperature;
    /** Temperature annealing method (constant / geometric / logarithmic) */
    public final int temperatureSchedule;
    /** Temperature decay factor (only valid when schedule=GEOMETRIC) */
    public final double temperatureAlpha;
    /** Logarithmic temperature offset (only valid when schedule=LOGARITHMIC) */
    public final double temperatureLogOffset;
    /** Upper bound of decision variables */
    public final double upperBound;
    /** Lower bound of decision variables */
    public final double lowerBound;
    /** Opt-in acceptance-rate sigma adaptation; default false keeps geometric cooling. */
    public final boolean adaptiveSigma;

    public SAConfig(
      int genSA,
      double freezeRate,
      double temperature,
      int temperatureSchedule,
      double temperatureAlpha,
      double upperBound,
      double lowerBound) {
      this(genSA, freezeRate, temperature, temperatureSchedule, temperatureAlpha,
        SA_DEFAULT_TEMPERATURE_LOG_OFFSET, upperBound, lowerBound, false);
    }

    public SAConfig(
      int genSA,
      double freezeRate,
      double temperature,
      int temperatureSchedule,
      double temperatureAlpha,
      double upperBound,
      double lowerBound,
      boolean adaptiveSigma) {
      this(genSA, freezeRate, temperature, temperatureSchedule, temperatureAlpha,
        SA_DEFAULT_TEMPERATURE_LOG_OFFSET, upperBound, lowerBound, adaptiveSigma);
    }

    public SAConfig(
      int genSA,
      double freezeRate,
      double temperature,
      int temperatureSchedule,
      double temperatureAlpha,
      double temperatureLogOffset,
      double upperBound,
      double lowerBound,
      boolean adaptiveSigma) {
      this.genSA = genSA;
      this.freezeRate = freezeRate;
      this.temperature = temperature;
      this.temperatureSchedule = temperatureSchedule;
      this.temperatureAlpha = temperatureAlpha;
      this.temperatureLogOffset = temperatureLogOffset;
      this.upperBound = upperBound;
      this.lowerBound = lowerBound;
      this.adaptiveSigma = adaptiveSigma;
    }

    /**
     * Provide a set of default parameters for convenient direct use.
     */
    public static SAConfig defaultConfig() {
      return new SAConfig(
        resolveGenerationCount(),
        resolveDoubleProperty(SA_FREEZE_RATE_PROPERTY, SA_DEFAULT_FREEZE_RATE),
        resolveDoubleProperty(SA_TEMPERATURE_PROPERTY, SA_DEFAULT_TEMPERATURE),
        resolveTemperatureSchedule(),
        resolveDoubleProperty(SA_TEMPERATURE_ALPHA_PROPERTY, SA_DEFAULT_TEMPERATURE_ALPHA),
        resolveDoubleProperty(SA_TEMPERATURE_LOG_OFFSET_PROPERTY, SA_DEFAULT_TEMPERATURE_LOG_OFFSET),
        AlgorithmConstants.DEFAULT_BOUND,
        -AlgorithmConstants.DEFAULT_BOUND,
        resolveBooleanProperty(SA_ADAPTIVE_SIGMA_PROPERTY, false)
      );
    }

    private static int resolveGenerationCount() {
      String configured = System.getProperty(SA_FAST_GENERATIONS_PROPERTY);
      if (configured == null || configured.isBlank()) {
        return SA_DEFAULT_GENERATIONS;
      }
      int parsed;
      try {
        parsed = Integer.parseInt(configured.trim());
      } catch (NumberFormatException ex) {
        throw new IllegalArgumentException(
          SA_FAST_GENERATIONS_PROPERTY + " must be a positive integer, got: " + configured, ex);
      }
      if (parsed <= 0) {
        throw new IllegalArgumentException(
          SA_FAST_GENERATIONS_PROPERTY + " must be a positive integer, got: " + configured);
      }
      return parsed;
    }

    private static double resolveDoubleProperty(String propertyName, double defaultValue) {
      String configured = System.getProperty(propertyName);
      if (configured == null || configured.isBlank()) {
        return defaultValue;
      }
      try {
        return Double.parseDouble(configured.trim());
      } catch (NumberFormatException ex) {
        throw new IllegalArgumentException(
          propertyName + " must be a double, got: " + configured, ex);
      }
    }

    private static boolean resolveBooleanProperty(String propertyName, boolean defaultValue) {
      String configured = System.getProperty(propertyName);
      if (configured == null || configured.isBlank()) {
        return defaultValue;
      }
      String normalized = configured.trim().toLowerCase();
      if ("true".equals(normalized)) {
        return true;
      }
      if ("false".equals(normalized)) {
        return false;
      }
      throw new IllegalArgumentException(
        propertyName + " must be true or false, got: " + configured);
    }

    private static int resolveTemperatureSchedule() {
      String configured = System.getProperty(SA_TEMPERATURE_SCHEDULE_PROPERTY);
      if (configured == null || configured.isBlank()) {
        return SA_DEFAULT_TEMPERATURE_SCHEDULE;
      }
      String normalized = configured.trim().toUpperCase();
      switch (normalized) {
        case "0":
        case "CONSTANT":
          return SA_TEMPERATURE_SCHEDULE_CONSTANT;
        case "1":
        case "GEOMETRIC":
          return SA_TEMPERATURE_SCHEDULE_GEOMETRIC;
        case "2":
        case "LOGARITHMIC":
          return SA_TEMPERATURE_SCHEDULE_LOGARITHMIC;
        default:
          throw new IllegalArgumentException(
            SA_TEMPERATURE_SCHEDULE_PROPERTY
              + " must be 0/1/2 or CONSTANT/GEOMETRIC/LOGARITHMIC, got: "
              + configured);
      }
    }

    @Override
    public ValidationResult validate() {
      ValidationResultBuilder builder = new ValidationResultBuilder();

      builder.checkNonNegative("genSA", genSA);
      builder.checkRange("freezeRate", freezeRate, 0.0, 1.0);
      builder.checkPositive("temperature", temperature);

      if (temperatureSchedule != SA_TEMPERATURE_SCHEDULE_CONSTANT &&
        temperatureSchedule != SA_TEMPERATURE_SCHEDULE_GEOMETRIC &&
        temperatureSchedule != SA_TEMPERATURE_SCHEDULE_LOGARITHMIC) {
        builder.addError("Invalid temperatureSchedule: " + temperatureSchedule);
      }

      builder.checkRange("temperatureAlpha", temperatureAlpha, 0.0, 1.0);
      builder.checkCondition(temperatureLogOffset > 1.0,
        "temperatureLogOffset must be greater than 1.0");
      builder.checkCondition(!adaptiveSigma || freezeRate > 0.0,
        "adaptiveSigma requires freezeRate > 0.0");

      if (upperBound <= lowerBound) {
        builder.addError("upperBound must be greater than lowerBound, got: [" +
          lowerBound + ", " + upperBound + "]");
      }

      return builder.build();
    }
  }

  /**
   * Execute simulated annealing using index-based selection (high-performance version).
   *
   * <p>This is the <strong>recommended method</strong> for all use cases. It provides
   * significantly better performance by avoiding expensive list creation for subsets.
   *
   * <p><strong>Performance characteristics:</strong>
   * <ul>
   *   <li>Index lookup: O(1) constant time</li>
   *   <li>Memory efficient: no intermediate List allocations for subsets</li>
   * </ul>
   *
   * @param pop Full parent population (must not be null or empty)
   * @param popFit Fitness values for the full population (must match pop size)
   * @param sigma Step size factors for the full population (must match pop size and dimensions)
   * @param selectIndices Indices of individuals to evolve (must be valid indices into pop)
   * @param config Configuration object containing algorithm parameters (must not be null)
   * @param satellite Satellite model for evaluation
   * @param allTasks List of all tasks
   * @param random Custom random number generator (must not be null)
   * @param evaluator Optional custom evaluator function (can be null)
   * @return SAResult containing updated best solutions, fitness values, and sigma
   * @throws IllegalArgumentException If input parameters are invalid
   * @throws Exception If evaluation fails
   */
  public static SAResult run(
    List<double[]> pop,
    List<Double> popFit,
    List<double[]> sigma,
    int[] selectIndices,
    SAConfig config,
    Satellite satellite,
    List<Task> allTasks,
    Random random,
    Function<List<double[]>, List<Double>> evaluator) throws Exception {
    return run(pop, popFit, sigma, selectIndices, config, satellite, allTasks, random, evaluator, null);
  }

  public static SAResult run(
    List<double[]> pop,
    List<Double> popFit,
    List<double[]> sigma,
    int[] selectIndices,
    SAConfig config,
    Satellite satellite,
    List<Task> allTasks,
    Random random,
    Function<List<double[]>, List<Double>> evaluator,
    SAWorkspace workspace) throws Exception {

    // Validate input parameters
    validateParameters(pop, popFit, sigma, selectIndices, config, random);

    // ═══════════════════════════════════════════════════════
    // Initialize data structure
    // ═══════════════════════════════════════════════════════

    int N = selectIndices.length;
    int D = pop.get(0).length;

    List<double[]> currentSolutions;
    List<double[]> currentSigmas;
    List<double[]> bestSolutions;
    List<Double> currentObjectives;
    List<Double> bestFitness;
    if (workspace != null) {
      workspace.ensureCapacity(N, D);
      currentSolutions = workspace.bestView(N);
      currentSigmas = workspace.sigmaView(N);
      bestSolutions = workspace.bestView(N);
      currentObjectives = workspace.bestFitnessView(N);
      bestFitness = workspace.bestFitnessView(N);
      for (int i = 0; i < N; i++) {
        int idx = selectIndices[i];
        System.arraycopy(pop.get(idx), 0, workspace.currentBuffer[i], 0, D);
        System.arraycopy(pop.get(idx), 0, workspace.bestBuffer[i], 0, D);
        System.arraycopy(sigma.get(idx), 0, workspace.sigmaBuffer[i], 0, D);
        workspace.currentFitness[i] = popFit.get(idx);
        workspace.bestFitness[i] = popFit.get(idx);
      }
      currentSolutions = fixedArrayView(workspace.currentBuffer, N);
      currentObjectives = fixedDoubleView(workspace.currentFitness, N);
    } else {
      currentSolutions = new ArrayList<>(N);
      currentObjectives = new ArrayList<>(N);
      currentSigmas = new ArrayList<>(N);
      for (int idx : selectIndices) {
        currentSolutions.add(pop.get(idx));
        currentObjectives.add(popFit.get(idx));
        currentSigmas.add(sigma.get(idx).clone());
      }
      bestSolutions = new ArrayList<>(N);
      for (double[] sol : currentSolutions) {
        bestSolutions.add(sol);
      }
      bestFitness = new ArrayList<>(currentObjectives);
    }

    // ═══════════════════════════════════════════════════════
    // Main iteration loop (genSA generations)
    // ═══════════════════════════════════════════════════════

    int[] acceptedCounts = config.adaptiveSigma
      ? (workspace != null ? workspace.acceptedCounts : new int[N])
      : null;
    if (acceptedCounts != null) {
      Arrays.fill(acceptedCounts, 0, N, 0);
    }
    for (int gen = 0; gen < config.genSA; gen++) {
      double temperature = computeTemperature(config, gen);

      // ┌───────────────────────────────────────────────┐
      // │ Step 1: Generate candidate solutions (Gaussian perturbation) │
      // └───────────────────────────────────────────────┘

      List<double[]> candidateSolutions = generateCandidateSolutions(
        currentSolutions, currentSigmas, config, N, D, random, workspace);

      // ┌───────────────────────────────────────────────┐
      // │ Step 2: Evaluate the objective value of candidates │
      // └───────────────────────────────────────────────┘

      List<Double> candidateObjectives;
      if (workspace != null && evaluator == null) {
        List<Task> referenceTasks = resolveReferenceTasks(satellite, allTasks);
        PlanUtils.evaluatePopInto(
          candidateSolutions, satellite, referenceTasks, InsertionMode.GREEDY, workspace.candidateFitness);
        candidateObjectives = workspace.candidateFitnessView(N);
      } else if (evaluator != null) {
        candidateObjectives = evaluator.apply(candidateSolutions);
      } else {
        List<Task> referenceTasks = resolveReferenceTasks(satellite, allTasks);
        candidateObjectives = PlanUtils.evaluatePop(
          candidateSolutions, satellite, referenceTasks, InsertionMode.GREEDY);
      }

      // ┌───────────────────────────────────────────────┐
      // │ Step 3: Metropolis acceptance criterion │
      // └───────────────────────────────────────────────┘

      boolean[] acceptedFlags = config.adaptiveSigma
        ? (workspace != null ? workspace.acceptedFlags : new boolean[N])
        : null;
      applyMetropolisCriterion(
        candidateSolutions, candidateObjectives,
        currentSolutions, currentObjectives,
        temperature, N, random, acceptedFlags, workspace != null);

      // ┌───────────────────────────────────────────────┐
      // │ Step 4: Cool step size factor (geometric cooling) │
      // └───────────────────────────────────────────────┘

      if (acceptedCounts != null) {
        for (int i = 0; i < N; i++) {
          if (acceptedFlags[i]) {
            acceptedCounts[i]++;
          }
        }
      }
      coolSigmas(currentSigmas, config, N, D, acceptedCounts, gen + 1);

      // ┌───────────────────────────────────────────────┐
      // │ Step 5: Update historical best solution │
      // └───────────────────────────────────────────────┘

      for (int i = 0; i < N; i++) {
        if (currentObjectives.get(i) > bestFitness.get(i)) {
          bestFitness.set(i, currentObjectives.get(i));
          if (workspace != null) {
            System.arraycopy(currentSolutions.get(i), 0, bestSolutions.get(i), 0, D);
          } else {
            bestSolutions.set(i, currentSolutions.get(i));
          }
        }
      }
    }

    // ═══════════════════════════════════════════════════════
    // Clone best solutions before returning to isolate output from input
    // ═══════════════════════════════════════════════════════
    List<double[]> clonedBestSolutions = new ArrayList<>(N);
    for (double[] sol : bestSolutions) {
      clonedBestSolutions.add(sol.clone());
    }

    return new SAResult(clonedBestSolutions, bestFitness, currentSigmas);
  }

  /**
   * Validates input parameters for the index-based run method.
   * Delegates to AlgorithmParameterValidator for common validations.
   */
  private static void validateParameters(
    List<double[]> pop,
    List<Double> popFit,
    List<double[]> sigma,
    int[] selectIndices,
    SAConfig config,
    Random random) {

    // Use centralized validation utility for all common parameters
    AlgorithmParameterValidator.validateSAParameters(
      pop, popFit, sigma, selectIndices, config, random);
  }

  private static List<Task> resolveReferenceTasks(Satellite satellite, List<Task> allTasks) {
    if (allTasks != null && !allTasks.isEmpty()) {
      return allTasks;
    }
    return satellite.getAssignedTasks();
  }

  /**
   * Compute temperature for current generation based on schedule.
   */
  private static double computeTemperature(SAConfig config, int gen) {
    double computed;
    if (config.temperatureSchedule == SA_TEMPERATURE_SCHEDULE_GEOMETRIC) {
      computed = config.temperature * Math.pow(config.temperatureAlpha, gen);
    } else if (config.temperatureSchedule == SA_TEMPERATURE_SCHEDULE_LOGARITHMIC) {
      double offset = config.temperatureLogOffset;
      computed = config.temperature * Math.log(offset) / Math.log(gen + offset);
    } else {
      computed = config.temperature;
    }
    return Math.max(MIN_TEMPERATURE, computed);
  }

  /**
   * Generate candidate solutions using Gaussian perturbation.
   */
  private static List<double[]> generateCandidateSolutions(
    List<double[]> currentSolutions,
    List<double[]> currentSigmas,
    SAConfig config,
    int N,
    int D,
    Random random,
    SAWorkspace workspace) {
    List<double[]> candidateSolutions;
    if (workspace != null) {
      candidateSolutions = workspace.candidateView(N);
    } else {
      candidateSolutions = new ArrayList<>(N);
    }
    for (int i = 0; i < N; i++) {
      double[] newIndividual = (workspace != null) ? workspace.candidateBuffer[i] : new double[D];
      double[] currentSolution = currentSolutions.get(i);
      double[] currentSigma = currentSigmas.get(i);

      for (int j = 0; j < D; j++) {
        double gaussian = FastGaussian.isEnabled()
          ? FastGaussian.nextGaussian(random)
          : random.nextGaussian();
        double noise = currentSigma[j] * gaussian;
        double unconstrained = currentSolution[j] + noise;
        newIndividual[j] = BoundaryHandler.clip(unconstrained, config.lowerBound, config.upperBound);
      }
      if (workspace == null) {
        candidateSolutions.add(newIndividual);
      }
    }
    return candidateSolutions;
  }

  /**
   * Apply Metropolis acceptance criterion to determine acceptance of candidate solutions.
   */
  private static void applyMetropolisCriterion(
    List<double[]> candidateSolutions,
    List<Double> candidateObjectives,
    List<double[]> currentSolutions,
    List<Double> currentObjectives,
    double temperature,
    int N,
    Random random,
    boolean[] acceptedFlags,
    boolean copyAcceptedInPlace) {
    for (int i = 0; i < N; i++) {
      // Calculate the difference in the objective function value (for maximization problems)
      double delta = candidateObjectives.get(i) - currentObjectives.get(i);

      boolean accept;
      if (delta >= 0) {
        accept = true;
      } else {
        double acceptanceProbability = Math.exp(delta / temperature);
        accept = random.nextDouble() < acceptanceProbability;
      }

      if (accept) {
        double[] candidate = candidateSolutions.get(i);
        double[] current = currentSolutions.get(i);
        if (copyAcceptedInPlace) {
          System.arraycopy(candidate, 0, current, 0, candidate.length);
        } else {
          currentSolutions.set(i, candidate);
        }
        currentObjectives.set(i, candidateObjectives.get(i));
      }
      if (acceptedFlags != null) {
        acceptedFlags[i] = accept;
      }
    }
  }

  private static void coolSigmas(
    List<double[]> currentSigmas,
    SAConfig config,
    int N,
    int D,
    int[] acceptedCounts,
    int attempts) {
    for (int i = 0; i < N; i++) {
      double factor = config.freezeRate;
      if (config.adaptiveSigma && acceptedCounts != null && attempts > 0) {
        double acceptanceRate = acceptedCounts[i] / (double) attempts;
        if (acceptanceRate < 0.2) {
          factor = 1.0 / config.freezeRate;
        } else if (acceptanceRate > 0.6) {
          factor = config.freezeRate * config.freezeRate;
        }
      }
      for (int j = 0; j < D; j++) {
        currentSigmas.get(i)[j] *= factor;
      }
    }
  }

  private static List<double[]> fixedArrayView(double[][] rows, int size) {
    return new java.util.AbstractList<double[]>() {
      @Override
      public double[] get(int index) {
        if (index < 0 || index >= size) {
          throw new IndexOutOfBoundsException("index=" + index + ", size=" + size);
        }
        return rows[index];
      }

      @Override
      public int size() {
        return size;
      }
    };
  }

  private static List<Double> fixedDoubleView(double[] values, int size) {
    return new java.util.AbstractList<Double>() {
      @Override
      public Double get(int index) {
        if (index < 0 || index >= size) {
          throw new IndexOutOfBoundsException("index=" + index + ", size=" + size);
        }
        return values[index];
      }

      @Override
      public Double set(int index, Double element) {
        if (index < 0 || index >= size) {
          throw new IndexOutOfBoundsException("index=" + index + ", size=" + size);
        }
        double previous = values[index];
        values[index] = element;
        return previous;
      }

      @Override
      public int size() {
        return size;
      }
    };
  }
}
