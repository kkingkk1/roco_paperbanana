package method.rkhma.legacy.core.config;

import method.rkhma.legacy.algorithm.alns.ALNS;
import method.rkhma.legacy.algorithm.eda.EDAGaussSam;
import method.rkhma.legacy.algorithm.de.AdaptiveFSchedule;
import method.rkhma.legacy.algorithm.de.DEConfig;
import method.rkhma.legacy.algorithm.pso.PSO;
import method.rkhma.legacy.algorithm.abc.ABC;
import method.rkhma.legacy.algorithm.aco.ACOGaussGenerate;
import method.rkhma.legacy.algorithm.sa.SACont;
import method.rkhma.legacy.algorithm.ga.GeneticAlgorithmNoSelect;
import method.rkhma.legacy.algorithm.shade.SHADELoopBrief;
import method.rkhma.legacy.common.AlgorithmConstants;
import method.rkhma.legacy.common.BoundaryHandler;
import method.rkhma.legacy.common.ConfigStringUtils;
import method.rkhma.legacy.common.TuningConfig;
import method.rkhma.legacy.operator.insertion.InsertionMode;

/**
 * HybCec Configuration Manager
 *
 * Centralized management of all algorithm configurations, including:
 * - Various algorithm configuration objects (PSO, GA, EDA, ACO, DE, ABC, SA, SHADE)
 * - Configuration initialization and validation
 * - Configuration access interface
 *
 * Advantages:
 * - Centralized configuration management for easier maintenance
 * - Centralized configuration validation logic
 * - Easy configuration serialization and loading
 * - Reduced HybCec class complexity
 *
 * Thread Safety: This class is not thread-safe. External synchronization required for concurrent access.
 */
public class HybCecConfigManager {

  // ==========================================================
  // Algorithm configuration objects
  // ==========================================================

  private PSO.PSOConfig psoConfig;
  private GeneticAlgorithmNoSelect.GAConfig gaConfig;
  private EDAGaussSam.EDAConfig edaConfig;
  private ACOGaussGenerate.ACOConfig acoConfig;
  private DEConfig deConfig;
  private ABC.ABCConfig abcConfig;
  private SACont.SAConfig saConfig;
  private SHADELoopBrief.SHADEConfig shadeConfig;
  private ALNS.ALNSConfig alnsConfig;

  // ==========================================================
  // Performance optimization cache
  // ==========================================================

  /** Whether SHADE is activated (cache) */
  private boolean isShadeActive;

  /** Whether the DE algorithm is activated (cached) */
  private boolean isDEActive;

  // ==========================================================
  // Initialization methods
  // ==========================================================

  /**
   * Initialize all algorithm configurations based on flagPar
   *
   * @param flagPar Algorithm activation flags (must have one slot per pure parent algorithm)
   * @param maxG Maximum number of generations
   * @param nPop Population size
   * @throws IllegalArgumentException if flagPar is invalid or maxG/nPop are non-positive
   */
  public void initialize(int[] flagPar, int maxG, int nPop) {
    // Validate input parameters
    validateInitializationParameters(flagPar, maxG, nPop);

    // Reset all configurations
    resetAllConfigs();

    // Update performance cache: Check if DE variants are activated
    this.isDEActive = isAnyDEVariantActive(flagPar);

    // Update performance cache: Check if SHADE variants are activated
    this.isShadeActive = isAnySHADEVariantActive(flagPar);

    // Initialize each algorithm configuration based on flags
    if (isFlagActive(flagPar, AlgorithmIndex.GA)) {
      this.gaConfig = buildGAConfig(maxG, nPop);
    }

    if (isFlagActive(flagPar, AlgorithmIndex.PSO)) {
      this.psoConfig = buildPSOConfig(maxG);
    }

    if (isFlagActive(flagPar, AlgorithmIndex.EDA)) {
      this.edaConfig = buildEDAConfig();
    }

    if (isFlagActive(flagPar, AlgorithmIndex.ACO)) {
      this.acoConfig = buildACOConfig();
    }

    // DE series (active front area + moved-to-tail deprecated variants share the same DEConfig)
    if (isDEActive) {
      this.deConfig = buildDEConfig(maxG);
    }

    if (isFlagActive(flagPar, AlgorithmIndex.ABC)) {
      this.abcConfig = buildABCConfig();
    }

    if (isFlagActive(flagPar, AlgorithmIndex.SA)) {
      this.saConfig = SACont.SAConfig.defaultConfig();
    }

    if (isFlagActive(flagPar, AlgorithmIndex.ALNS)) {
      this.alnsConfig = buildALNSConfig();
    }

    // SHADE / L-SHADE
    if (isShadeActive) {
      // Use forPopulationSize to create configuration, automatically set all parameters related to population size
      this.shadeConfig = SHADELoopBrief.SHADEConfig.forPopulationSize(nPop);
    }
  }

  /**
   * Reset all algorithm configurations to null
   */
  private void resetAllConfigs() {
    this.psoConfig = null;
    this.gaConfig = null;
    this.edaConfig = null;
    this.acoConfig = null;
    this.deConfig = null;
    this.abcConfig = null;
    this.saConfig = null;
    this.shadeConfig = null;
    this.alnsConfig = null;
  }

  private GeneticAlgorithmNoSelect.GAConfig buildGAConfig(int maxG, int nPop) {
    double probCrossover = TuningConfig.getDouble(
      "ga.probCrossover",
      GeneticAlgorithmNoSelect.GA_DEFAULT_CROSSOVER_PROB);
    double probMutation = TuningConfig.getDouble(
      "ga.probMutation",
      GeneticAlgorithmNoSelect.GA_DEFAULT_MUTATION_PROB);
    int kTournament = TuningConfig.getInt(
      "ga.kTournament",
      GeneticAlgorithmNoSelect.GA_DEFAULT_TOURNAMENT_SIZE);
    double sigmaScale = TuningConfig.getDouble(
      "ga.sigmaScale",
      GeneticAlgorithmNoSelect.GA_DEFAULT_SIGMA_SCALE);
    int nElite = TuningConfig.getInt(
      "ga.nElite",
      GeneticAlgorithmNoSelect.GA_DEFAULT_N_ELITE);
    GeneticAlgorithmNoSelect.GAConfig.ElitismScope elitismScope = parseGAElitismScope(
      System.getProperty("ga.elitismScope", "GLOBAL"));
    double maxMutationDimRatio = TuningConfig.getDouble(
      "ga.maxMutationDimRatio",
      GeneticAlgorithmNoSelect.GA_DEFAULT_MAX_MUTATION_DIM_RATIO);
    boolean adaptiveMutation = TuningConfig.getBoolean(
      "ga.adaptiveMutation",
      GeneticAlgorithmNoSelect.GA_DEFAULT_ADAPTIVE_MUTATION);
    double probMutationLate = TuningConfig.getDouble(
      "ga.probMutationLate",
      probMutation);
    double sigmaScaleLate = TuningConfig.getDouble(
      "ga.sigmaScaleLate",
      sigmaScale);
    double maxMutationDimRatioLate = TuningConfig.getDouble(
      "ga.maxMutationDimRatioLate",
      maxMutationDimRatio);
    double mutationDecayStartRatio = TuningConfig.getDouble(
      "ga.mutationDecayStartRatio",
      GeneticAlgorithmNoSelect.GA_DEFAULT_MUTATION_DECAY_START_RATIO);
    return new GeneticAlgorithmNoSelect.GAConfig(
      probCrossover,
      probMutation,
      kTournament,
      sigmaScale,
      AlgorithmConstants.DEFAULT_BOUND,
      -AlgorithmConstants.DEFAULT_BOUND,
      nElite,
      elitismScope,
      maxMutationDimRatio,
      adaptiveMutation,
      probMutationLate,
      sigmaScaleLate,
      maxMutationDimRatioLate,
      mutationDecayStartRatio,
      estimateGenerationCount(maxG, nPop)
    );
  }

  private PSO.PSOConfig buildPSOConfig(int maxG) {
    int safeMaxG = Math.max(1, maxG);
    double[] cognitiveCoefficient = readDoubleArray(
      "pso.cognitiveCoefficient",
      new double[] {PSO.PSO_C1, PSO.PSO_C2});
    double[] inertiaWeight = buildPSOInertiaWeight(safeMaxG);
    double vMaxLimit = TuningConfig.getDouble("pso.vMaxLimit", PSO.PSO_MAX_VELOCITY);
    double vMinLimit = TuningConfig.getDouble("pso.vMinLimit", -vMaxLimit);
    double upperBound = TuningConfig.getDouble(
      "pso.upperBound",
      AlgorithmConstants.DEFAULT_BOUND);
    double lowerBound = TuningConfig.getDouble(
      "pso.lowerBound",
      -AlgorithmConstants.DEFAULT_BOUND);
    BoundaryHandler.BoundaryMode boundaryMode = readEnum(
      "pso.boundaryMode",
      BoundaryHandler.BoundaryMode.class,
      PSO.PSO_BOUNDARY_MODE);
    double boundaryRepairRatio = TuningConfig.getDouble(
      "pso.boundaryRepairRatio",
      PSO.PSO_BOUNDARY_REPAIR_RATIO);
    boolean enableTVAC = TuningConfig.getBoolean(
      "pso.enableTVAC",
      PSO.PSO_ENABLE_TVAC_DEFAULT);
    double tvacC1Start = TuningConfig.getDouble("pso.tvacC1Start", PSO.PSO_TVAC_C1_START);
    double tvacC1End = TuningConfig.getDouble("pso.tvacC1End", PSO.PSO_TVAC_C1_END);
    double tvacC2Start = TuningConfig.getDouble("pso.tvacC2Start", PSO.PSO_TVAC_C2_START);
    double tvacC2End = TuningConfig.getDouble("pso.tvacC2End", PSO.PSO_TVAC_C2_END);
    double vMaxRatio = TuningConfig.getDouble("pso.vMaxRatio", Double.NaN);
    PSO.PSOConfig.Topology topology = readEnum(
      "pso.topology",
      PSO.PSOConfig.Topology.class,
      PSO.PSOConfig.Topology.GLOBAL_BEST);

    return new PSO.PSOConfig(
      cognitiveCoefficient,
      inertiaWeight,
      vMaxLimit,
      vMinLimit,
      upperBound,
      lowerBound,
      boundaryMode,
      boundaryRepairRatio,
      enableTVAC,
      vMaxRatio,
      topology,
      tvacC1Start,
      tvacC1End,
      tvacC2Start,
      tvacC2End);
  }

  private double[] buildPSOInertiaWeight(int safeMaxG) {
    double[] explicitWeights = readDoubleArray("pso.inertiaWeight", null);
    if (explicitWeights != null) {
      if (explicitWeights.length == 1) {
        double[] expanded = new double[safeMaxG];
        java.util.Arrays.fill(expanded, explicitWeights[0]);
        return expanded;
      }
      if (explicitWeights.length != safeMaxG) {
        throw new IllegalArgumentException(
          "pso.inertiaWeight length must be 1 or " + safeMaxG
            + ", got " + explicitWeights.length);
      }
      return explicitWeights;
    }

    double start = TuningConfig.getDouble("pso.inertiaStart", PSO.PSO_INITIAL_INERTIA);
    double end = TuningConfig.getDouble(
      "pso.inertiaEnd",
      PSO.PSO_INITIAL_INERTIA - PSO.PSO_INERTIA_DECAY_FACTOR);
    double[] inertia = new double[safeMaxG];
    double step = safeMaxG == 1 ? 0.0 : (start - end) / (safeMaxG - 1);
    for (int i = 0; i < safeMaxG; i++) {
      inertia[i] = start - i * step;
    }
    return inertia;
  }

  private DEConfig buildDEConfig(int maxG) {
    int safeMaxG = Math.max(1, maxG);
    String schedule = TuningConfig.getString("de.fSchedule", "SIGMOID").trim();
    double[] differentialWeight1 = buildDEWeights(schedule, safeMaxG);
    double[] differentialWeight2 = readDoubleArray("de.differentialWeight2", null);
    double crossoverRate = TuningConfig.getDouble(
      "de.crossoverRate",
      DEConfig.DE_DEFAULT_CROSSOVER_RATE);
    double upperBound = TuningConfig.getDouble(
      "de.upperBound",
      AlgorithmConstants.DEFAULT_BOUND);
    double lowerBound = TuningConfig.getDouble(
      "de.lowerBound",
      -AlgorithmConstants.DEFAULT_BOUND);
    DEConfig.BoundaryPolicy boundaryPolicy = readEnum(
      "de.boundaryPolicy",
      DEConfig.BoundaryPolicy.class,
      DEConfig.BoundaryPolicy.RANDOM_REINIT);
    return new DEConfig(
      differentialWeight1,
      differentialWeight2,
      crossoverRate,
      upperBound,
      lowerBound,
      boundaryPolicy);
  }

  private double[] buildDEWeights(String schedule, int safeMaxG) {
    String normalizedSchedule = schedule.toUpperCase(java.util.Locale.ROOT);
    double[] explicitWeights = readDoubleArray("de.differentialWeight1", null);
    if (explicitWeights != null) {
      return expandDEWeights(explicitWeights, safeMaxG);
    }
    switch (normalizedSchedule) {
      case "STATIC":
        return new double[] {
          TuningConfig.getDouble(
            "de.fStatic",
            DEConfig.DE_DEFAULT_DIFFERENTIAL_WEIGHT)
        };
      case "LINEAR":
        return buildDELinearWeights(safeMaxG);
      case "SIGMOID":
      default:
        if (!"SIGMOID".equals(normalizedSchedule)) {
          System.err.println("[HybCecConfigManager] Invalid de.fSchedule=" + schedule
            + ", fallback SIGMOID");
        }
        return buildDEAdaptiveWeights(safeMaxG, DEConfig.DE_ADAPTIVE_F_MODE_SIGMOID);
    }
  }

  private double[] expandDEWeights(double[] weights, int safeMaxG) {
    if (weights.length == 1) {
      return weights;
    }
    if (weights.length != safeMaxG) {
      throw new IllegalArgumentException(
        "de.differentialWeight1 length must be 1 or " + safeMaxG
          + ", got " + weights.length);
    }
    return weights;
  }

  private double[] buildDELinearWeights(int safeMaxG) {
    double fMax = TuningConfig.getDouble(
      "de.fMax",
      DEConfig.DE_F_LINEAR_DECAY_MAX);
    double fMin = TuningConfig.getDouble(
      "de.fMin",
      DEConfig.DE_F_LINEAR_DECAY_MIN);
    double[] weights = new double[safeMaxG];
    for (int i = 0; i < safeMaxG; i++) {
      double t = (safeMaxG == 1) ? 0.0 : (i / (double) (safeMaxG - 1));
      weights[i] = fMax * (1.0 - t) + fMin * t;
    }
    return weights;
  }

  private double[] buildDEAdaptiveWeights(int safeMaxG, int mode) {
    return AdaptiveFSchedule.build(
      safeMaxG,
      mode,
      TuningConfig.getDouble("de.adaptiveFAlpha", DEConfig.DE_ADAPTIVE_F_ALPHA_DEFAULT),
      TuningConfig.getDouble("de.fMax", DEConfig.DE_ADAPTIVE_F_FMAX_DEFAULT),
      TuningConfig.getDouble("de.fMin", DEConfig.DE_ADAPTIVE_F_FMIN_DEFAULT),
      TuningConfig.getDouble("de.piecewiseP0", DEConfig.DE_ADAPTIVE_F_PIECEWISE_P0_DEFAULT),
      TuningConfig.getDouble("de.piecewiseFHigh", DEConfig.DE_ADAPTIVE_F_PIECEWISE_F_HIGH_DEFAULT),
      TuningConfig.getDouble("de.piecewiseFLow", DEConfig.DE_ADAPTIVE_F_PIECEWISE_F_LOW_DEFAULT),
      TuningConfig.getDouble("de.sigmoidP0", DEConfig.DE_ADAPTIVE_F_SIGMOID_P0_DEFAULT),
      TuningConfig.getDouble("de.sigmoidK", DEConfig.DE_ADAPTIVE_F_SIGMOID_K_DEFAULT),
      TuningConfig.getDouble("de.noiseSigma", DEConfig.DE_ADAPTIVE_F_NOISE_SIGMA_DEFAULT),
      null);
  }

  private double[] readDoubleArray(String key, double[] defaultValue) {
    String rawValue = TuningConfig.getString(key, null);
    if (rawValue == null || rawValue.trim().isEmpty()) {
      return defaultValue;
    }
    try {
      return ConfigStringUtils.stringToDoubleArray(rawValue);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
        "[HybCecConfigManager] Cannot parse double array: " + key + "=" + rawValue,
        e);
    }
  }

  private <E extends Enum<E>> E readEnum(String key, Class<E> enumType, E defaultValue) {
    String rawValue = TuningConfig.getString(key, null);
    if (rawValue == null || rawValue.trim().isEmpty()) {
      return defaultValue;
    }
    try {
      return Enum.valueOf(enumType, rawValue.trim());
    } catch (IllegalArgumentException e) {
      System.err.println("[HybCecConfigManager] Invalid " + key + "=" + rawValue
        + ", fallback " + defaultValue.name());
      return defaultValue;
    }
  }

  private ABC.ABCConfig buildABCConfig() {
    return new ABC.ABCConfig(
      TuningConfig.getInt("abc.numNoImp", ABC.ABC_DEFAULT_NO_IMPROVEMENT_LIMIT),
      TuningConfig.getDouble("abc.upperBound", AlgorithmConstants.DEFAULT_BOUND),
      TuningConfig.getDouble("abc.lowerBound", -AlgorithmConstants.DEFAULT_BOUND),
      TuningConfig.getDouble("abc.phiScale", ABC.ABC_PHI_SCALE),
      TuningConfig.getDouble("abc.phiOffset", ABC.ABC_PHI_OFFSET)
    );
  }

  private int estimateGenerationCount(int maxG, int nPop) {
    return Math.max(1, (int) Math.ceil(maxG / (double) nPop));
  }

  private EDAGaussSam.EDAConfig buildEDAConfig() {
    double kElite = TuningConfig.getDouble(
      "eda.kElite",
      EDAGaussSam.EDA_DEFAULT_ELITE_RATIO);
    double sigmaScale = TuningConfig.getDouble(
      "eda.sigmaScale",
      EDAGaussSam.EDA_DEFAULT_SIGMA_SCALE);
    double minSigmaRatio = TuningConfig.getDouble(
      "eda.minSigmaRatio",
      EDAGaussSam.EDA_DEFAULT_MIN_SIGMA_RATIO);
    double stagnationBoostRatio = TuningConfig.getDouble(
      "eda.stagnationBoostRatio",
      1.0);
    int stagnationGenerations = TuningConfig.getInt(
      "eda.stagnationGenerations",
      0);
    return new EDAGaussSam.EDAConfig(
      kElite,
      sigmaScale,
      AlgorithmConstants.DEFAULT_BOUND,
      -AlgorithmConstants.DEFAULT_BOUND,
      minSigmaRatio,
      stagnationBoostRatio,
      stagnationGenerations);
  }

  private ACOGaussGenerate.ACOConfig buildACOConfig() {
    double nSample = TuningConfig.getDouble(
      "aco.nSample",
      ACOGaussGenerate.ACO_DEFAULT_SAMPLE_RATIO);
    double intenseFactor = TuningConfig.getDouble(
      "aco.intenseFactor",
      ACOGaussGenerate.ACO_DEFAULT_INTENSE_FACTOR);
    double deviateDistanceRatio = TuningConfig.getDouble(
      "aco.deviateDistanceRatio",
      ACOGaussGenerate.ACO_DEFAULT_DEVIATE_DISTANCE_RATIO);
    double minSigmaRatio = TuningConfig.getDouble(
      "aco.minSigmaRatio",
      ACOGaussGenerate.ACO_DEFAULT_MIN_SIGMA_RATIO);
    boolean legacySigma = TuningConfig.getBoolean(
      ACOGaussGenerate.ACO_LEGACY_SIGMA_PROPERTY,
      false);
    return new ACOGaussGenerate.ACOConfig(
      nSample,
      intenseFactor,
      deviateDistanceRatio,
      AlgorithmConstants.DEFAULT_BOUND,
      -AlgorithmConstants.DEFAULT_BOUND,
      minSigmaRatio,
      legacySigma);
  }

  private ALNS.ALNSConfig buildALNSConfig() {
    ALNS.ALNSConfig defaults = ALNS.ALNSConfig.defaultConfig();
    return new ALNS.ALNSConfig(
      TuningConfig.getInt("alns.innerIterations", defaults.innerIterations),
      TuningConfig.getInt("alns.weightUpdateInterval", defaults.weightUpdateInterval),
      TuningConfig.getInt("alns.minDestroyCount", defaults.minDestroyCount),
      TuningConfig.getDouble("alns.maxDestroyRatio", defaults.maxDestroyRatio),
      TuningConfig.getDouble("alns.sigmaGlobal", defaults.sigmaGlobal),
      TuningConfig.getDouble("alns.sigmaLocal", defaults.sigmaLocal),
      TuningConfig.getDouble("alns.sigmaBad", defaults.sigmaBad),
      TuningConfig.getDouble("alns.reactionFactor", defaults.reactionFactor),
      TuningConfig.getDouble(
        "alns.initialTemperatureRatio",
        defaults.initialTemperatureRatio),
      TuningConfig.getDouble(
        "alns.maxInitialTemperature",
        defaults.maxInitialTemperature),
      TuningConfig.getDouble("alns.coolingRate", defaults.coolingRate),
      TuningConfig.getDouble("alns.lowerBound", defaults.lowerBound),
      TuningConfig.getDouble("alns.upperBound", defaults.upperBound),
      readEnum("alns.insertionMode", InsertionMode.class, defaults.insertionMode),
      TuningConfig.getBoolean("alns.enableRegret2Repair", defaults.enableRegret2Repair),
      readDoubleArray("alns.destroyWeights", defaults.destroyWeights),
      readDoubleArray("alns.repairWeights", defaults.repairWeights)
    );
  }

  private GeneticAlgorithmNoSelect.GAConfig.ElitismScope parseGAElitismScope(String rawValue) {
    if (rawValue == null || rawValue.trim().isEmpty()) {
      return GeneticAlgorithmNoSelect.GAConfig.ElitismScope.GLOBAL;
    }
    try {
      return GeneticAlgorithmNoSelect.GAConfig.ElitismScope.valueOf(rawValue.trim());
    } catch (IllegalArgumentException e) {
      System.err.println("[HybCecConfigManager] Invalid ga.elitismScope=" + rawValue + ", fallback GLOBAL");
      return GeneticAlgorithmNoSelect.GAConfig.ElitismScope.GLOBAL;
    }
  }

  /**
   * Adjust DE configuration (when maxG changes)
   *
   * @param maxG New maximum number of generations
   * @throws IllegalArgumentException if maxG is non-positive
   */
  public void adjustDEConfig(int maxG) {
    if (maxG <= 0) {
      throw new IllegalArgumentException("maxG must be positive, got: " + maxG);
    }

    if (!isDEActive) {
      return;
    }

    this.deConfig = buildDEConfig(maxG);
  }

  /**
   * Adjust SHADE configuration (when population size changes)
   *
   * @param newNPopEstimate New population size estimate
   * @throws IllegalArgumentException if newNPopEstimate is non-positive
   */
  public void adjustSHADEConfig(int newNPopEstimate) {
    if (newNPopEstimate <= 0) {
      throw new IllegalArgumentException("newNPopEstimate must be positive, got: " + newNPopEstimate);
    }

    if (!isShadeActive) {
      return;
    }

    shadeConfig.adjustForPopulationChange(newNPopEstimate);
  }

  // ==========================================================
  // Validation methods
  // ==========================================================

  /**
   * Validate initialization parameters
   *
   * @throws IllegalArgumentException if parameters are invalid
   */
  private void validateInitializationParameters(int[] flagPar, int maxG, int nPop) {
    if (flagPar == null) {
      throw new IllegalArgumentException("flagPar cannot be null");
    }

    int requiredLength = AlgorithmIndex.getRequiredFlagParLength();
    if (flagPar.length < requiredLength) {
      throw new IllegalArgumentException(
        String.format("flagPar must have at least %d elements, got: %d",
        requiredLength, flagPar.length)
      );
    }

    if (maxG <= 0) {
      throw new IllegalArgumentException("maxG must be positive, got: " + maxG);
    }

    if (nPop <= 0) {
      throw new IllegalArgumentException("nPop must be positive, got: " + nPop);
    }
  }

  /**
   * Check if a specific algorithm flag is active
   *
   * @param flagPar Flag array
   * @param algorithm Algorithm to check
   * @return true if the flag is set to 1
   */
  private boolean isFlagActive(int[] flagPar, AlgorithmIndex algorithm) {
    return flagPar[algorithm.getIndex()] == 1;
  }

  /**
   * Check if any DE variant is activated in flagPar
   *
   * @param flagPar Flag array
   * @return true if any DE variant flag is active
   */
  private boolean isAnyDEVariantActive(int[] flagPar) {
    for (AlgorithmIndex algo : AlgorithmIndex.values()) {
      if (algo.isDEVariant() && isFlagActive(flagPar, algo)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Check if any SHADE variant is activated in flagPar
   *
   * @param flagPar Flag array
   * @return true if any SHADE variant flag is active
   */
  private boolean isAnySHADEVariantActive(int[] flagPar) {
    for (AlgorithmIndex algo : AlgorithmIndex.values()) {
      if (algo.isSHADEVariant() && isFlagActive(flagPar, algo)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Validate that a configuration is properly initialized for the specified algorithm
   *
   * @param algorithmIndex Algorithm index to validate
   * @return true if properly configured
   */
  public boolean validateConfiguration(int algorithmIndex) {
    AlgorithmIndex algo = AlgorithmIndex.fromIndex(algorithmIndex);
    if (algo == null) {
      return false;
    }

    switch (algo) {
      case GA: return gaConfig != null;
      case PSO: return psoConfig != null;
      case EDA: return edaConfig != null;
      case ACO: return acoConfig != null;
      case DE_RAND_1:
      case DE_BEST_1:
      case DE_CURRENT_TO_BEST_1:
      case DE_RAND_2:
      case DE_BEST_2:
      case DE_RAND_TO_BEST_1:
      case DE_CURRENT_TO_RAND_1:
        return deConfig != null;
      case ABC: return abcConfig != null;
      case SA: return saConfig != null;
      case ALNS: return alnsConfig != null;
      case SHADE:
      case L_SHADE:
        return shadeConfig != null;
      default: return false;
    }
  }

  // ==========================================================
  // Configuration getters (ordered by algorithm)
  // ==========================================================

  public GeneticAlgorithmNoSelect.GAConfig getGaConfig() {
    return gaConfig;
  }

  public PSO.PSOConfig getPsoConfig() {
    return psoConfig;
  }

  public EDAGaussSam.EDAConfig getEdaConfig() {
    return edaConfig;
  }

  public ACOGaussGenerate.ACOConfig getAcoConfig() {
    return acoConfig;
  }

  public DEConfig getDeConfig() {
    return deConfig;
  }

  public ABC.ABCConfig getAbcConfig() {
    return abcConfig;
  }

  public SACont.SAConfig getSaConfig() {
    return saConfig;
  }

  public SHADELoopBrief.SHADEConfig getShadeConfig() {
    return shadeConfig;
  }

  public ALNS.ALNSConfig getAlnsConfig() {
    return alnsConfig;
  }

  // ==========================================================
  // Configuration setters (with cache update for consistency)
  // ==========================================================

  public void setGaConfig(GeneticAlgorithmNoSelect.GAConfig gaConfig) {
    this.gaConfig = gaConfig;
  }

  public void setPsoConfig(PSO.PSOConfig psoConfig) {
    this.psoConfig = psoConfig;
  }

  public void setEdaConfig(EDAGaussSam.EDAConfig edaConfig) {
    this.edaConfig = edaConfig;
  }

  public void setAcoConfig(ACOGaussGenerate.ACOConfig acoConfig) {
    this.acoConfig = acoConfig;
  }

  /**
   * Set DE configuration and update cache
   */
  public void setDeConfig(DEConfig deConfig) {
    this.deConfig = deConfig;
    this.isDEActive = (deConfig != null);
  }

  public void setAbcConfig(ABC.ABCConfig abcConfig) {
    this.abcConfig = abcConfig;
  }

  public void setSaConfig(SACont.SAConfig saConfig) {
    this.saConfig = saConfig;
  }

  public void setAlnsConfig(ALNS.ALNSConfig alnsConfig) {
    this.alnsConfig = alnsConfig;
  }

  /**
   * Set SHADE configuration and update cache
   */
  public void setShadeConfig(SHADELoopBrief.SHADEConfig shadeConfig) {
    this.shadeConfig = shadeConfig;
    this.isShadeActive = (shadeConfig != null);
  }

  // ==========================================================
  // Performance optimization cache accessors
  // ==========================================================

  public boolean isShadeActive() {
    return isShadeActive;
  }

  public boolean isDEActive() {
    return isDEActive;
  }
}
