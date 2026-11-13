package method.rkhma.legacy.algorithm.pso;
import method.rkhma.legacy.common.AlgorithmConstants;
import method.rkhma.legacy.common.AlgorithmParameterValidator;
import method.rkhma.legacy.common.BoundaryHandler;
import method.rkhma.legacy.core.config.ConfigValidatable;
import method.rkhma.legacy.algorithm.workspace.PSOWorkspace;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Particle Swarm Optimization (PSO) Algorithm Implementation.
 *
 * <p>Uses index-based selection for high performance, similar to DE/rand/1 strategy.
 * This avoids expensive data copying and allows flexible population subset evolution.
 *
 * <p><strong>Performance characteristics:</strong>
 * <ul>
 *   <li>Index lookup: O(1) constant time</li>
 *   <li>In-place velocity/position updates: no intermediate object allocation</li>
 *   <li>Bounded reflection with iteration limit: prevents infinite loops</li>
 * </ul>
 *
 * <p><strong>PSO velocity update formula:</strong>
 * <pre>
 * v[i][j] = w * v[i][j] + c1 * r1 * (pbest[i][j] - x[i][j]) + c2 * r2 * (gbest[j] - x[i][j])
 * x[i][j] = x[i][j] + v[i][j]
 * </pre>
 *
 * @see <a href="https://ieeexplore.ieee.org/document/494215">Kennedy & Eberhart, 1995</a>
 */
public final class PSO {

  // =====================================================
  // PSO Algorithm Constants
  // =====================================================

  /** PSO Initial Inertia Weight */
  public static final double PSO_INITIAL_INERTIA = 0.9;

  /** PSO Inertia Weight Decay Factor */
  public static final double PSO_INERTIA_DECAY_FACTOR = 0.5;

  /** PSO Individual Learning Coefficient (c1) */
  public static final double PSO_C1 = 2.0;

  /** PSO Social Learning Coefficient (c2) */
  public static final double PSO_C2 = 0.5;

  /** PSO Cognitive and Social Learning Coefficient (c1 = c2) */
  public static final double PSO_COGNITIVE_COEFFICIENT = PSO_C1;

  /** PSO Maximum Velocity */
  public static final double PSO_MAX_VELOCITY = 80.0;

  /** PSO boundary mode promoted after ID1/ID3/ID5 checks on 2026-05-19. */
  public static final BoundaryHandler.BoundaryMode PSO_BOUNDARY_MODE =
    BoundaryHandler.BoundaryMode.RANDOM_REBOUND;

  /** PSO boundary repair ratio promoted after ID1/ID3/ID5 checks on 2026-05-19. */
  public static final double PSO_BOUNDARY_REPAIR_RATIO = 0.05;

  /** Default TVAC switch. Empirically promoted after ID1 100% budget check on 2026-05-19. */
  public static final boolean PSO_ENABLE_TVAC_DEFAULT = true;

  /** Default TVAC cognitive coefficient start. */
  public static final double PSO_TVAC_C1_START = 2.5;

  /** Default TVAC cognitive coefficient end. */
  public static final double PSO_TVAC_C1_END = 0.5;

  /** Default TVAC social coefficient start. */
  public static final double PSO_TVAC_C2_START = 0.5;

  /** Default TVAC social coefficient end. */
  public static final double PSO_TVAC_C2_END = 2.5;

  /** Initial velocity scaling factor (to prevent over-exploration in early generations) */
  private static final double INITIAL_VELOCITY_SCALE = 0.5;

  /** Minimum population size required for PSO */
  private static final int MIN_POPULATION_SIZE = 2;

  /** Enable expensive per-row state dimension checks when debugging PSO state ownership. */
  private static final boolean PSO_DEEP_VALIDATION = false;

  /**
   * Private constructor to prevent instantiation of this utility class.
   *
   * @throws AssertionError always
   */
  private PSO() {
    throw new AssertionError("Utility class should not be instantiated");
  }

  /**
   * PSO Parameter Configuration Class with validation support.
   */
  public static class PSOConfig implements ConfigValidatable {
    public enum Topology {
      GLOBAL_BEST,
      RING
    }

    /** Cognitive and social learning factors [c1, c2] */
    public final double[] cognitiveCoefficient;
    /** Array of inertia weights corresponding to each generation */
    public final double[] inertiaWeight;
    /** Maximum velocity limit */
    public final double vMaxLimit;
    /** Minimum velocity limit */
    public final double vMinLimit;
    /** Decision variable upper bound */
    public final double upperBound;
    /** Decision variable lower bound */
    public final double lowerBound;
    /** Boundary handling mode */
    public final BoundaryHandler.BoundaryMode boundaryMode;
    /** Boundary repair ratio (used in RANDOM_REBOUND mode) */
    public final double boundaryRepairRatio;
    /** Enable time-varying acceleration coefficients (TVAC) */
    public final boolean enableTVAC;
    /** TVAC cognitive coefficient at the first generation. */
    public final double tvacC1Start;
    /** TVAC cognitive coefficient at the last generation. */
    public final double tvacC1End;
    /** TVAC social coefficient at the first generation. */
    public final double tvacC2Start;
    /** TVAC social coefficient at the last generation. */
    public final double tvacC2End;
    /** Optional proportional velocity cap; NaN preserves absolute vMin/vMax behavior. */
    public final double vMaxRatio;
    /** Opt-in topology selector; RING is scaffolded and rejected until swarm state is wired. */
    public final Topology topology;

    public PSOConfig(double[] cognitiveCoefficient,
      double[] inertiaWeight,
      double vMaxLimit,
      double vMinLimit,
      double upperBound,
      double lowerBound) {
      this(cognitiveCoefficient, inertiaWeight, vMaxLimit, vMinLimit,
        upperBound, lowerBound, PSO_BOUNDARY_MODE,
        PSO_BOUNDARY_REPAIR_RATIO, PSO_ENABLE_TVAC_DEFAULT, Double.NaN, Topology.GLOBAL_BEST);
    }

    public PSOConfig(double[] cognitiveCoefficient,
      double[] inertiaWeight,
      double vMaxLimit,
      double vMinLimit,
      double upperBound,
      double lowerBound,
      BoundaryHandler.BoundaryMode boundaryMode,
      double boundaryRepairRatio,
      boolean enableTVAC) {
      this(cognitiveCoefficient, inertiaWeight, vMaxLimit, vMinLimit, upperBound, lowerBound,
        boundaryMode, boundaryRepairRatio, enableTVAC, Double.NaN, Topology.GLOBAL_BEST);
    }

    public PSOConfig(double[] cognitiveCoefficient,
      double[] inertiaWeight,
      double vMaxLimit,
      double vMinLimit,
      double upperBound,
      double lowerBound,
      BoundaryHandler.BoundaryMode boundaryMode,
      double boundaryRepairRatio,
      boolean enableTVAC,
      double vMaxRatio,
      Topology topology) {
      this(cognitiveCoefficient, inertiaWeight, vMaxLimit, vMinLimit, upperBound, lowerBound,
        boundaryMode, boundaryRepairRatio, enableTVAC, vMaxRatio, topology,
        PSO_TVAC_C1_START, PSO_TVAC_C1_END, PSO_TVAC_C2_START, PSO_TVAC_C2_END);
    }

    public PSOConfig(double[] cognitiveCoefficient,
      double[] inertiaWeight,
      double vMaxLimit,
      double vMinLimit,
      double upperBound,
      double lowerBound,
      BoundaryHandler.BoundaryMode boundaryMode,
      double boundaryRepairRatio,
      boolean enableTVAC,
      double vMaxRatio,
      Topology topology,
      double tvacC1Start,
      double tvacC1End,
      double tvacC2Start,
      double tvacC2End) {
      this.cognitiveCoefficient = cognitiveCoefficient;
      this.inertiaWeight = inertiaWeight;
      this.vMaxLimit = vMaxLimit;
      this.vMinLimit = vMinLimit;
      this.upperBound = upperBound;
      this.lowerBound = lowerBound;
      this.boundaryMode = (boundaryMode == null) ? PSO_BOUNDARY_MODE : boundaryMode;
      this.boundaryRepairRatio = boundaryRepairRatio;
      this.enableTVAC = enableTVAC;
      this.vMaxRatio = vMaxRatio;
      this.topology = (topology == null) ? Topology.GLOBAL_BEST : topology;
      this.tvacC1Start = tvacC1Start;
      this.tvacC1End = tvacC1End;
      this.tvacC2Start = tvacC2Start;
      this.tvacC2End = tvacC2End;

      // Validate configuration on construction
      ConfigValidatable.ValidationResult result = validate();
      if (!result.isValid()) {
        throw new IllegalArgumentException(result.getErrorMessage());
      }
    }

    /**
     * Construct a default PSO configuration for given number of generations.
     *
     * @param maxG Maximum number of generations
     * @return Default PSO configuration
     */
    public static PSOConfig defaultForGenerations(int maxG) {
      return defaultForGenerations(maxG, PSO_ENABLE_TVAC_DEFAULT);
    }

    /**
     * Construct a PSO configuration with optional time-varying acceleration coefficients.
     *
     * @param maxG Maximum number of generations
     * @param enableTVAC Enable time-varying acceleration coefficients
     * @return PSO configuration
     */
    public static PSOConfig defaultForGenerations(int maxG, boolean enableTVAC) {
      int safeMaxG = Math.max(1, maxG);
      double constantFactor = (safeMaxG == 1)
      ? 0.0
      : PSO_INERTIA_DECAY_FACTOR / (safeMaxG - 1);
      double[] inertia = new double[safeMaxG];
      for (int i = 0; i < safeMaxG; i++) {
        inertia[i] = PSO_INITIAL_INERTIA - i * constantFactor;
      }

      double[] cog = new double[]{
        PSO_C1,
        PSO_C2
      };

      double vMax = PSO_MAX_VELOCITY;
      return new PSOConfig(
        cog, inertia, vMax, -vMax,
        AlgorithmConstants.DEFAULT_BOUND,
        -AlgorithmConstants.DEFAULT_BOUND,
        PSO_BOUNDARY_MODE,
        PSO_BOUNDARY_REPAIR_RATIO,
        enableTVAC
      );
    }

    @Override
    public ConfigValidatable.ValidationResult validate() {
      ConfigValidatable.ValidationResultBuilder builder = new ConfigValidatable.ValidationResultBuilder();

      // Validate cognitiveCoefficient
      builder.checkNotNull("cognitiveCoefficient", cognitiveCoefficient);
      if (cognitiveCoefficient != null) {
        builder.checkSize("cognitiveCoefficient", cognitiveCoefficient.length, 2);
        for (int i = 0; i < cognitiveCoefficient.length && i < 2; i++) {
          builder.checkNonNegative("cognitiveCoefficient[" + i + "]", cognitiveCoefficient[i]);
        }
      }

      // Validate inertiaWeight
      builder.checkNotNull("inertiaWeight", inertiaWeight);
      if (inertiaWeight != null) {
        builder.addErrorIf(inertiaWeight.length == 0, "inertiaWeight cannot be empty");
      }

      // Validate velocity limits
      builder.addErrorIf(vMaxLimit <= vMinLimit,
        String.format("vMaxLimit must be greater than vMinLimit, got vMax=%.4f, vMin=%.4f",
        vMaxLimit, vMinLimit));

      // Validate position bounds
      builder.addErrorIf(upperBound <= lowerBound,
        String.format("upperBound must be greater than lowerBound, got upper=%.4f, lower=%.4f",
        upperBound, lowerBound));

      // Validate boundaryRepairRatio
      builder.checkRange("boundaryRepairRatio", boundaryRepairRatio, 0.0, 1.0);
      builder.addErrorIf(!Double.isNaN(vMaxRatio) && vMaxRatio <= 0.0,
        "vMaxRatio must be positive when configured");
      builder.checkNonNegative("tvacC1Start", tvacC1Start);
      builder.checkNonNegative("tvacC1End", tvacC1End);
      builder.checkNonNegative("tvacC2Start", tvacC2Start);
      builder.checkNonNegative("tvacC2End", tvacC2End);

      return builder.build();
    }
  }

  /**
   * PSO run result wrapper containing updated positions and velocities.
   */
  public static class PsoResult {
    /** Updated particle position matrix [N arrays of double[D]] */
    public final List<double[]> popSelect;

    public PsoResult(List<double[]> popSelect) {
      this.popSelect = popSelect;
    }
  }

  /**
   * Execute PSO evolution using index-based selection (high-performance version).
   *
   * <p>This is the <strong>recommended method</strong> for all use cases. It allows
   * evolving a subset of the population while accessing personal/global best from
   * the full population.
   *
   * <p><strong>Usage example:</strong>
   * <pre>
   * // Evolve entire population
   * int[] allIndices = IntStream.range(0, pop.size()).toArray();
   * PsoResult result = PSO.run(pop, pbest, gbest, velocity, allIndices, config, gen, rand);
   *
   * // Evolve subset [10, 20)
   * int[] subset = IntStream.range(10, 20).toArray();
   * PsoResult result = PSO.run(pop, pbest, gbest, velocity, subset, config, gen, rand);
   * </pre>
   *
   * @param pop Full population (must not be null or empty)
   * @param pbestSolution Personal best positions for all particles
   * @param gbestSolution Global best position
   * @param v Velocity matrix for all particles
   * @param selectIndices Indices of particles to evolve (must be valid indices into pop)
   * @param config PSO configuration
   * @param gen Current generation index (must be within inertiaWeight array bounds)
   * @param random Random number generator
   * @return PSO result containing updated positions and velocities
   * @throws IllegalArgumentException If input parameters are invalid
   */
  public static PsoResult run(
    List<double[]> pop,
    List<double[]> pbestSolution,
    double[] gbestSolution,
    List<double[]> v,
    int[] selectIndices,
    PSOConfig config,
    int gen,
    Random random) {
    return run(pop, pbestSolution, gbestSolution, v, selectIndices, config, gen, random, null, null);
  }

  public static PsoResult run(
    List<double[]> pop,
    List<double[]> pbestSolution,
    double[] gbestSolution,
    List<double[]> v,
    int[] selectIndices,
    PSOConfig config,
    int gen,
    Random random,
    PSOWorkspace workspace) {
    return run(pop, pbestSolution, gbestSolution, v, selectIndices, config, gen, random, workspace, null);
  }

  public static PsoResult run(
    List<double[]> pop,
    List<double[]> pbestSolution,
    double[] gbestSolution,
    List<double[]> v,
    int[] selectIndices,
    PSOConfig config,
    int gen,
    Random random,
    PSOWorkspace workspace,
    List<double[]> velocityOut) {

    validateParameters(pop, pbestSolution, gbestSolution, v, selectIndices, config, gen, random);
    return runUnsafe(
      pop,
      pbestSolution,
      gbestSolution,
      v,
      selectIndices,
      config,
      gen,
      random,
      workspace,
      velocityOut
    );
  }

  public static PsoResult runUnsafe(
    List<double[]> pop,
    List<double[]> pbestSolution,
    double[] gbestSolution,
    List<double[]> v,
    int[] selectIndices,
    PSOConfig config,
    int gen,
    Random random,
    PSOWorkspace workspace,
    List<double[]> velocityOut) {

    // ======================================================
    // Step 1: Get dimensional information
    // ======================================================

    int N = selectIndices.length;     // Number of particles to evolve
    int D = pop.get(0).length;        // Problem dimension

    // ======================================================
    // Step 2: Initialize result matrices
    // ======================================================

    List<double[]> newPosition;
    if (workspace != null) {
      workspace.ensureCapacity(N, D);
      newPosition = workspace.positionView(N);
    } else {
      newPosition = new ArrayList<>(N);
      for (int i = 0; i < N; i++) {
        newPosition.add(new double[D]);
      }
    }
    List<double[]> newV = resolveVelocityOutput(v, selectIndices, workspace, velocityOut, N, D);

    // ======================================================
    // Step 3: Extract learning factors
    // ======================================================

    double c1 = config.cognitiveCoefficient[0];  // Cognitive learning factor
    double c2 = config.cognitiveCoefficient[1];  // Social learning factor

    // Apply time-varying acceleration coefficients (TVAC) if enabled
    if (config.enableTVAC && config.inertiaWeight.length > 1) {
      double progress = (double) gen / (config.inertiaWeight.length - 1);
      c1 = config.tvacC1Start + (config.tvacC1End - config.tvacC1Start) * progress;
      c2 = config.tvacC2Start + (config.tvacC2End - config.tvacC2Start) * progress;
    }

    double inertiaWeight = config.inertiaWeight[gen];
    if (config.topology == PSOConfig.Topology.RING) {
      throw new UnsupportedOperationException(
        "PSO RING topology is scaffold-only; HybCec does not yet provide local-best state.");
    }
    double effectiveVMax = getEffectiveVMax(config);
    double effectiveVMin = getEffectiveVMin(config);

    // ======================================================
    // Step 4: Iterate each particle and dimension to update
    // ======================================================

    double range = config.upperBound - config.lowerBound;
    for (int idx = 0; idx < N; idx++) {  // Iterate selected particles

      // Get index in full population
      int particleIdx = selectIndices[idx];

      // Get references for current particle data
      double[] currentPos = pop.get(particleIdx);
      double[] currentVel = v.get(particleIdx);
      double[] personalBest = pbestSolution.get(particleIdx);
      double[] newPos = newPosition.get(idx);
      double[] newVel = newV.get(idx);

      for (int j = 0; j < D; j++) {  // Iterate dimensions

        // ┌───────────────────────────────────────────────┐
        // │  Sub-step 4.1: Generate random numbers        │
        // └───────────────────────────────────────────────┘

        double r1 = random.nextDouble();  // [0, 1)
        double r2 = random.nextDouble();  // [0, 1)

        // ┌───────────────────────────────────────────────┐
        // │  Sub-step 4.2: Compute velocity components    │
        // └───────────────────────────────────────────────┘

        double inertiaComponent = inertiaWeight * currentVel[j];
        double cognitiveComponent = c1 * r1 * (personalBest[j] - currentPos[j]);
        double socialComponent = c2 * r2 * (gbestSolution[j] - currentPos[j]);

        // ┌───────────────────────────────────────────────┐
        // │  Sub-step 4.3: Update velocity with clamping  │
        // └───────────────────────────────────────────────┘

        double newVelocity = inertiaComponent + cognitiveComponent + socialComponent;
        newVelocity = BoundaryHandler.clipVelocity(newVelocity, effectiveVMin, effectiveVMax);

        // ┌───────────────────────────────────────────────┐
        // │  Sub-step 4.4: Update position                │
        // └───────────────────────────────────────────────┘

        double newPosition_ij = currentPos[j] + newVelocity;

        // ┌───────────────────────────────────────────────┐
        // │  Sub-step 4.5: Boundary handling (in-place)   │
        // └───────────────────────────────────────────────┘

        BoundaryHandler.repairPositionInPlace(
          newPos, newVel, j,
          newPosition_ij, newVelocity,
          config.lowerBound, config.upperBound, range,
          random, config.boundaryMode, config.boundaryRepairRatio);
      }
    }

    // ======================================================
    // Step 5: Return updates
    // ======================================================

    return new PsoResult(newPosition);
  }

  /**
   * Initialize the particle swarm velocity matrix with scaled initial velocities.
   *
   * <p>Initial velocities are scaled by {@link #INITIAL_VELOCITY_SCALE} to prevent
   * over-exploration in early generations. This improves convergence stability.
   *
   * @param nPop Number of particles
   * @param D Problem dimension
   * @param config PSO configuration
   * @param random Random number generator
   * @return Initialized velocity matrix
   */
  public static List<double[]> initVelocity(int nPop,
    int D,
    PSOConfig config,
    Random random) {
    List<double[]> v = new ArrayList<>(nPop);
    double vMax = getEffectiveVMax(config) * INITIAL_VELOCITY_SCALE;
    double vMin = getEffectiveVMin(config) * INITIAL_VELOCITY_SCALE;

    for (int i = 0; i < nPop; i++) {
      double[] vi = new double[D];
      for (int j = 0; j < D; j++) {
        vi[j] = vMin + (vMax - vMin) * random.nextDouble();
      }
      v.add(vi);
    }
    return v;
  }

  /**
   * Validates input parameters for PSO execution.
   *
   * @param pop Full population
   * @param pbestSolution Personal best positions
   * @param gbestSolution Global best position
   * @param v Velocity matrix
   * @param selectIndices Indices of particles to evolve
   * @param config PSO configuration
   * @param gen Current generation index
   * @param random Random number generator
   * @throws IllegalArgumentException If any parameter is invalid
   */
  private static void validateParameters(
    List<double[]> pop,
    List<double[]> pbestSolution,
    double[] gbestSolution,
    List<double[]> v,
    int[] selectIndices,
    PSOConfig config,
    int gen,
    Random random) {

    // Use common validator for standard checks
    AlgorithmParameterValidator.validateCommonParameters(pop, selectIndices, config, random);

    // PSO-specific validation: minimum population size
    AlgorithmParameterValidator.validateMinPopulationSize(pop.size(), MIN_POPULATION_SIZE, "PSO");

    int dimension = pop.get(0).length;

    // Validate pbestSolution
    if (pbestSolution == null) {
      throw new IllegalArgumentException("Personal best solution (pbestSolution) cannot be null");
    }
    if (pbestSolution.size() != pop.size()) {
      throw new IllegalArgumentException(
        String.format("Personal best size mismatch: expected %d, got %d",
        pop.size(), pbestSolution.size()));
    }

    // Validate gbestSolution
    if (gbestSolution == null) {
      throw new IllegalArgumentException("Global best solution (gbestSolution) cannot be null");
    }
    if (gbestSolution.length != dimension) {
      throw new IllegalArgumentException(
        String.format("Global best dimension mismatch: expected %d, got %d",
        dimension, gbestSolution.length));
    }

    // Validate velocity matrix
    if (v == null) {
      throw new IllegalArgumentException("Velocity matrix (v) cannot be null");
    }
    if (v.size() != pop.size()) {
      throw new IllegalArgumentException(
        String.format("Velocity matrix size mismatch: expected %d, got %d",
        pop.size(), v.size()));
    }
    if (PSO_DEEP_VALIDATION) {
      validateStateDimensions(pbestSolution, v, dimension);
    }

    // Validate generation with inertiaWeight array bounds
    AlgorithmParameterValidator.validateGeneration(gen, config.inertiaWeight.length);
  }

  private static void validateStateDimensions(
    List<double[]> pbestSolution,
    List<double[]> v,
    int dimension) {
    for (int i = 0; i < pbestSolution.size(); i++) {
      if (pbestSolution.get(i) == null) {
        throw new IllegalArgumentException("Personal best contains null at index: " + i);
      }
      if (pbestSolution.get(i).length != dimension) {
        throw new IllegalArgumentException(
          String.format("Personal best has inconsistent dimensions at index %d: expected %d, got %d",
          i, dimension, pbestSolution.get(i).length));
      }
    }
    for (int i = 0; i < v.size(); i++) {
      if (v.get(i) == null) {
        throw new IllegalArgumentException("Velocity matrix contains null at index: " + i);
      }
      if (v.get(i).length != dimension) {
        throw new IllegalArgumentException(
          String.format("Velocity matrix has inconsistent dimensions at index %d: expected %d, got %d",
          i, dimension, v.get(i).length));
      }
    }
  }

  public static void validateStateDimensionsForHybCec(
    List<double[]> pbestSolution,
    double[] gbestSolution,
    List<double[]> v,
    int dimension) {
    if (gbestSolution == null) {
      throw new IllegalArgumentException("Global best solution (gbestSolution) cannot be null");
    }
    if (gbestSolution.length != dimension) {
      throw new IllegalArgumentException(
        String.format("Global best dimension mismatch: expected %d, got %d",
          dimension, gbestSolution.length));
    }
    if (pbestSolution == null) {
      throw new IllegalArgumentException("Personal best solution (pbestSolution) cannot be null");
    }
    if (v == null) {
      throw new IllegalArgumentException("Velocity matrix (v) cannot be null");
    }
    if (pbestSolution.size() != v.size()) {
      throw new IllegalArgumentException(
        String.format("PSO state size mismatch: pbest=%d, velocity=%d",
          pbestSolution.size(), v.size()));
    }
    validateStateDimensions(pbestSolution, v, dimension);
  }

  private static void validateSelectedStateRow(
    double[] row,
    String stateName,
    int index,
    int expectedDimension) {
    if (row == null) {
      throw new IllegalArgumentException(stateName + " contains null at index: " + index);
    }
    if (row.length != expectedDimension) {
      throw new IllegalArgumentException(
        String.format("%s has inconsistent dimensions at index %d: expected %d, got %d",
          stateName, index, expectedDimension, row.length));
    }
  }

  private static double getEffectiveVMax(PSOConfig config) {
    if (Double.isNaN(config.vMaxRatio)) {
      return config.vMaxLimit;
    }
    return (config.upperBound - config.lowerBound) * config.vMaxRatio;
  }

  private static double getEffectiveVMin(PSOConfig config) {
    if (Double.isNaN(config.vMaxRatio)) {
      return config.vMinLimit;
    }
    return -getEffectiveVMax(config);
  }

  private static List<double[]> resolveVelocityOutput(
    List<double[]> velocityState,
    int[] selectIndices,
    PSOWorkspace workspace,
    List<double[]> velocityOut,
    int n,
    int d) {
    if (velocityOut != null) {
      if (velocityOut.size() < n) {
        throw new IllegalArgumentException(
          String.format("velocityOut size mismatch: expected at least %d, got %d", n, velocityOut.size()));
      }
      for (int i = 0; i < n; i++) {
        double[] row = velocityOut.get(i);
        if (row == null || row.length != d) {
          throw new IllegalArgumentException(
            String.format("velocityOut row %d must have dimension %d", i, d));
        }
      }
      return velocityOut;
    }
    if (workspace != null) {
      return workspace.velocityView(n);
    }
    List<double[]> copy = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      double[] currentVelocity = velocityState.get(selectIndices[i]);
      validateSelectedStateRow(currentVelocity, "Velocity matrix", selectIndices[i], d);
      copy.add(new double[d]);
    }
    return copy;
  }
}
