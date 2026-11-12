package method.rkhma.legacy.algorithm.shade;
import method.rkhma.legacy.utils.ListUtils;
import method.rkhma.legacy.utils.FitnessUtils;
import method.rkhma.legacy.algorithm.workspace.SHADEWorkspace;
import method.rkhma.legacy.core.config.ConfigValidatable;
import method.rkhma.legacy.core.config.ConfigValidatable.ValidationResult;
import method.rkhma.legacy.common.AlgorithmConstants;
import method.rkhma.legacy.common.BoundaryHandler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * Optimized SHADE algorithm implementation with improved structure and performance.
 *
 * Key improvements:
 * - Thread-safe statistics tracking
 * - Refactored methods for better maintainability
 * - Support for multiple DE mutation strategies
 * - Performance optimizations using primitive arrays
 * - Extracted constants and eliminated magic numbers
 */
public class SHADELoopBrief {

  // =====================================================
  // SHADE Algorithm Constants
  // =====================================================

  public static final int SHADE_DEFAULT_MEMORY_SIZE = 45;

  public static final double SHADE_DEFAULT_FREQ_INI_FOR_SF = 0.5;

  public static final double SHADE_DEFAULT_PBEST_RATE = 0.4;

  public static final double SHADE_DEFAULT_ARCHIVE_RATE = 3;

  public static final String SHADE_ARCHIVE_RATE_FAST_PROPERTY = "hybcec.shade.archiveRateFast";

  public static final String SHADE_MEMORY_SIZE_PROPERTY = "hybcec.shade.memorySize";

  public static final String SHADE_PBEST_RATE_PROPERTY = "hybcec.shade.pbestRate";

  public static final String SHADE_LEARN_PERIOD_PROPERTY = "hybcec.shade.learnPeriod";

  public static final String SHADE_CR_GAUSSIAN_STD_PROPERTY = "hybcec.shade.crGaussianStd";

  public static final String SHADE_CAUCHY_SCALE_PROPERTY = "hybcec.shade.cauchyScale";

  public static final int SHADE_DEFAULT_ARCHIVE_INITIAL_CAPACITY = 100;

  public static final int SHADE_DEFAULT_POPULATION_SIZE = 100;

  public static final double SHADE_DEFAULT_PROPORTION_SELECT_NEIGHBOUR = 0.5;

  public static final double SHADE_DEFAULT_PROB_EIGEN_CROSSOVER = 0.0;

  public static final int SHADE_DEFAULT_LEARN_PERIOD = 20;

  public static final int SHADE_DEFAULT_GMAX = 3000;

  public static final double SHADE_DEFAULT_CR_GAUSSIAN_STD = 0.1;

  public static final double SHADE_DEFAULT_CAUCHY_SCALE = 0.1;

  public static final double SHADE_STRATEGY_STATS_PSEUDO_COUNT = 0.5;

  // =====================================================
  // Constants
  // =====================================================

  /** Strategy selection threshold during learning period */
  private static final double STRATEGY_SELECTION_THRESHOLD = 0.5;

  /** Maximum attempts for Cauchy distribution sampling */
  private static final int MAX_CAUCHY_ATTEMPTS = 100;

  /** Maximum attempts for r2 index selection */
  private static final int MAX_R2_SELECTION_ATTEMPTS = 100;

  // =====================================================
  // Mutation Strategy - CURRENT_TO_PBEST only
  // =====================================================

  // =====================================================
  // Result wrapper class
  // =====================================================

  /**
   * SHADE iteration result wrapper.
   */
  public static class SHADEResult {
    /** Newly generated offspring population */
    public final List<double[]> newPop;

    /** Updated generation counter */
    public final int shadeGG;

    /** SF parameters used in this generation (performance: unified to double[]) */
    public final double[] shadeSF;

    /** CR parameters used in this generation (performance: unified to double[]) */
    public final double[] shadeCR;

    /** Freq parameters used in this generation (performance: unified to double[]) */
    public final double[] shadeFreq;

    /** Whether strategy 1 was used */
    public final boolean flag1Shade;

    /** Whether strategy 2 was used */
    public final boolean flag2Shade;

    public SHADEResult(
      List<double[]> newPop,
      int shadeGG,
      double[] shadeSF,
      double[] shadeCR,
      double[] shadeFreq,
      boolean flag1Shade,
      boolean flag2Shade) {
      this.newPop = newPop;
      this.shadeGG = shadeGG;
      this.shadeSF = shadeSF;
      this.shadeCR = shadeCR;
      this.shadeFreq = shadeFreq;
      this.flag1Shade = flag1Shade;
      this.flag2Shade = flag2Shade;
    }

    // Getter methods
    public List<double[]> getNewPop() { return newPop; }
    public int getGg() { return shadeGG; }
    public double[] getSf() { return shadeSF; }
    public double[] getCr() { return shadeCR; }
    public double[] getFreq() { return shadeFreq; }
    public boolean isFlag1() { return flag1Shade; }
    public boolean isFlag2() { return flag2Shade; }
  }

  // =====================================================
  // Configuration class
  // =====================================================

  public static class SHADEConfig implements ConfigValidatable {
    public List<Double> memorySF;
    public List<Double> memoryCR;
    public List<Double> memoryFreq;
    public UpdateArchive.ListArchive archive;
    public int memorySize;
    // Additional tunable parameters
    public double freqIniForSF;
    public double probEigenCrossover;
    public double proportionSelectNeighbour;
    public double pbestRate;
    public int learnPeriod;
    // Parameters related to population size
    public double archiveRate;
    public int archiveMaxVolume;
    public int currentPopSize;
    public double probStrategy1;
    public double crGaussianStd;
    public double cauchyScale;
    public transient int[] cachedFitnessRankDesc;

    public static SHADEConfig defaultConfig() {
      SHADEConfig cfg = new SHADEConfig();
      cfg.memorySize = resolvePositiveInt(SHADE_MEMORY_SIZE_PROPERTY, SHADE_DEFAULT_MEMORY_SIZE);
      cfg.memorySF = ListUtils.fill1DListDouble(cfg.memorySize, AlgorithmConstants.PROBABILITY_HALF);
      cfg.memoryCR = ListUtils.fill1DListDouble(cfg.memorySize, AlgorithmConstants.PROBABILITY_HALF);
      cfg.memoryFreq = ListUtils.fill1DListDouble(cfg.memorySize, SHADE_DEFAULT_FREQ_INI_FOR_SF);
      cfg.freqIniForSF = SHADE_DEFAULT_FREQ_INI_FOR_SF;
      cfg.probEigenCrossover = SHADE_DEFAULT_PROB_EIGEN_CROSSOVER;
      cfg.proportionSelectNeighbour = SHADE_DEFAULT_PROPORTION_SELECT_NEIGHBOUR;
      cfg.pbestRate = resolveRange(SHADE_PBEST_RATE_PROPERTY, SHADE_DEFAULT_PBEST_RATE, 0.0, 1.0);
      cfg.learnPeriod = resolvePositiveInt(SHADE_LEARN_PERIOD_PROPERTY, SHADE_DEFAULT_LEARN_PERIOD);
      cfg.archiveRate = resolveArchiveRate();
      cfg.currentPopSize = SHADE_DEFAULT_POPULATION_SIZE;
      cfg.archiveMaxVolume = (int) (cfg.archiveRate * cfg.currentPopSize);
      cfg.archive = new UpdateArchive.ListArchive(cfg.archiveMaxVolume);
      cfg.probStrategy1 = AlgorithmConstants.PROBABILITY_HALF;
      cfg.crGaussianStd = resolveNonNegativeDouble(
        SHADE_CR_GAUSSIAN_STD_PROPERTY, SHADE_DEFAULT_CR_GAUSSIAN_STD);
      cfg.cauchyScale = resolvePositiveDouble(
        SHADE_CAUCHY_SCALE_PROPERTY, SHADE_DEFAULT_CAUCHY_SCALE);
      return cfg;
    }

    private static double resolveArchiveRate() {
      String configured = System.getProperty(SHADE_ARCHIVE_RATE_FAST_PROPERTY);
      if (configured == null || configured.isBlank()) {
        return SHADE_DEFAULT_ARCHIVE_RATE;
      }
      double parsed;
      try {
        parsed = Double.parseDouble(configured.trim());
      } catch (NumberFormatException ex) {
        throw new IllegalArgumentException(
          SHADE_ARCHIVE_RATE_FAST_PROPERTY + " must be non-negative, got: " + configured, ex);
      }
      if (parsed < 0.0) {
        throw new IllegalArgumentException(
          SHADE_ARCHIVE_RATE_FAST_PROPERTY + " must be non-negative, got: " + configured);
      }
      return parsed;
    }

    private static int resolvePositiveInt(String propertyName, int defaultValue) {
      String configured = System.getProperty(propertyName);
      if (configured == null || configured.isBlank()) {
        return defaultValue;
      }
      int parsed;
      try {
        parsed = Integer.parseInt(configured.trim());
      } catch (NumberFormatException ex) {
        throw new IllegalArgumentException(
          propertyName + " must be positive integer, got: " + configured, ex);
      }
      if (parsed <= 0) {
        throw new IllegalArgumentException(
          propertyName + " must be positive integer, got: " + configured);
      }
      return parsed;
    }

    private static double resolveRange(
      String propertyName,
      double defaultValue,
      double minInclusive,
      double maxInclusive) {
      String configured = System.getProperty(propertyName);
      if (configured == null || configured.isBlank()) {
        return defaultValue;
      }
      double parsed = parseDoubleProperty(propertyName, configured);
      if (parsed < minInclusive || parsed > maxInclusive) {
        throw new IllegalArgumentException(String.format(
          "%s must be in [%.3f, %.3f], got: %s",
          propertyName, minInclusive, maxInclusive, configured));
      }
      return parsed;
    }

    private static double resolveNonNegativeDouble(String propertyName, double defaultValue) {
      String configured = System.getProperty(propertyName);
      if (configured == null || configured.isBlank()) {
        return defaultValue;
      }
      double parsed = parseDoubleProperty(propertyName, configured);
      if (parsed < 0.0) {
        throw new IllegalArgumentException(
          propertyName + " must be non-negative, got: " + configured);
      }
      return parsed;
    }

    private static double resolvePositiveDouble(String propertyName, double defaultValue) {
      String configured = System.getProperty(propertyName);
      if (configured == null || configured.isBlank()) {
        return defaultValue;
      }
      double parsed = parseDoubleProperty(propertyName, configured);
      if (parsed <= 0.0) {
        throw new IllegalArgumentException(
          propertyName + " must be positive, got: " + configured);
      }
      return parsed;
    }

    private static double parseDoubleProperty(String propertyName, String configured) {
      try {
        return Double.parseDouble(configured.trim());
      } catch (NumberFormatException ex) {
        throw new IllegalArgumentException(
          propertyName + " must be numeric, got: " + configured, ex);
      }
    }

    /**
     * Create configuration based on population size
     */
    public static SHADEConfig forPopulationSize(int nPop) {
      SHADEConfig cfg = defaultConfig();
      cfg.currentPopSize = nPop;
      cfg.archiveMaxVolume = (int)(cfg.archiveRate * nPop);
      cfg.archive = new UpdateArchive.ListArchive(cfg.archiveMaxVolume);
      return cfg;
    }

    /**
     * Reset the memory parameter (used to reinitialize the learning process of SHADE)
     */
    public void resetMemory() {
      this.memorySF = ListUtils.fill1DListDouble(memorySize, AlgorithmConstants.PROBABILITY_HALF);
      this.memoryCR = ListUtils.fill1DListDouble(memorySize, AlgorithmConstants.PROBABILITY_HALF);
      this.memoryFreq = ListUtils.fill1DListDouble(memorySize, SHADE_DEFAULT_FREQ_INI_FOR_SF);
    }

    /**
     * Adjust the relevant parameters when the population size changes.
     */
    public void adjustForPopulationChange(int newPopSize) {
      if (newPopSize == this.currentPopSize) {
        return;
      }

      this.currentPopSize = newPopSize;
      this.archiveMaxVolume = (int) (this.archiveRate * newPopSize);

      if (archive == null) {
        archive = new UpdateArchive.ListArchive(archiveMaxVolume);
        return;
      }

      archive.NP = archiveMaxVolume;
      if (archive.pop.size() > archiveMaxVolume) {
        archive.pop.subList(archiveMaxVolume, archive.pop.size()).clear();
        archive.fitness.subList(archiveMaxVolume, archive.fitness.size()).clear();
      }
    }

    @Override
    public ValidationResult validate() {
      ConfigValidatable.ValidationResultBuilder builder = new ConfigValidatable.ValidationResultBuilder();

      builder.checkPositiveInt("memorySize", memorySize);
      builder.checkRange("probEigenCrossover", probEigenCrossover, 0.0, 1.0);
      builder.checkRange("pbestRate", pbestRate, 0.0, 1.0);
      builder.checkRange("proportionSelectNeighbour", proportionSelectNeighbour, 0.0, 1.0);
      builder.checkNonNegative("crGaussianStd", crGaussianStd);
      builder.checkPositive("cauchyScale", cauchyScale);
      builder.checkNonNegative("archiveRate", archiveRate);
      builder.checkPositiveInt("currentPopSize", currentPopSize);

      return builder.build();
    }
  }

  // =====================================================
  // Main run method (refactored for clarity)
  // =====================================================

  /**
   * Main run method with index-based selection (DE/rand-style)
   *
   * @param popOld Full population
   * @param fitness Fitness values for full population
   * @param selectedIndices Indices of individuals to evolve
   * @param shadeGG Generation counter
   * @param nfes Current number of function evaluations
   * @param maxNfes Maximum number of function evaluations
   * @param config SHADE configuration
   * @param goodFit1All Strategy 1 good fitness history
   * @param goodFit2All Strategy 2 good fitness history
   * @param badFit1All Strategy 1 bad fitness history
   * @param badFit2All Strategy 2 bad fitness history
   * @param random Random number generator
   * @return SHADE result containing offspring population
   */
  public static SHADEResult run(
    List<double[]> popOld,
    List<Double> fitness,
    int[] selectedIndices,
    int shadeGG,
    int nfes,
    int maxNfes,
    SHADEConfig config,
    List<Double> goodFit1All,
    List<Double> goodFit2All,
    List<Double> badFit1All,
    List<Double> badFit2All,
    Random random) {

    // Validate and initialize config
    if (config == null) {
      config = SHADEConfig.defaultConfig();
    }

    ValidationResult validationResult = config.validate();
    if (!validationResult.isValid()) {
      throw new IllegalArgumentException(validationResult.getErrorMessage());
    }

    // Extract dimensions
    int popSize = popOld.size();
    int D = popOld.get(0).length;
    int selectSize = selectedIndices.length;

    // Increment generation counter
    shadeGG++;

    List<double[]> pop = popOld;

    // Sort indices by fitness (descending) using FitnessUtils
    int[] sortIndex = resolveSortIndex(fitness, config.cachedFitnessRankDesc);

    // Generate parameters from memory (for selected individuals)
    ParameterSet params = generateParameters(selectSize, D, config, random);

    // Select special SF generation strategy (first half only)
    SFStrategy sfStrategy = selectSFStrategy(nfes, maxNfes, shadeGG, config, random);
    double[] shadeSF = generateSF(selectSize, D, shadeGG, sfStrategy, params.sFCauchy, params.shadeFreq, config);

    // Perform DE mutation (index-based)
    List<double[]> vi = performMutation(
      pop, selectedIndices, sortIndex, config.archive.pop,
      shadeSF, config, random
    );

    // Perform crossover (index-based)
    List<double[]> ui = performCrossover(
      pop, selectedIndices, vi, params.shadeCR,
      config, sortIndex, random
    );

    // Return results
    return new SHADEResult(
      ui, shadeGG, shadeSF, params.shadeCR, params.shadeFreq,
      sfStrategy.flag1Shade, sfStrategy.flag2Shade
    );
  }

  public static SHADEResult runInto(
    List<double[]> popOld,
    List<Double> fitness,
    int[] selectedIndices,
    int shadeGG,
    int nfes,
    int maxNfes,
    SHADEConfig config,
    List<Double> goodFit1All,
    List<Double> goodFit2All,
    List<Double> badFit1All,
    List<Double> badFit2All,
    Random random,
    SHADEWorkspace workspace) {

    if (config == null) {
      config = SHADEConfig.defaultConfig();
    }

    ValidationResult validationResult = config.validate();
    if (!validationResult.isValid()) {
      throw new IllegalArgumentException(validationResult.getErrorMessage());
    }
    if (workspace == null) {
      throw new IllegalArgumentException("workspace cannot be null");
    }
    if (config.probEigenCrossover != 0.0) {
      return run(popOld, fitness, selectedIndices, shadeGG, nfes, maxNfes, config,
        goodFit1All, goodFit2All, badFit1All, badFit2All, random);
    }

    int D = popOld.get(0).length;
    int selectSize = selectedIndices.length;
    workspace.ensureCapacity(selectSize, D);

    shadeGG++;

    int[] sortIndex = resolveSortIndex(fitness, config.cachedFitnessRankDesc);

    ParameterSet params = generateParameters(selectSize, D, config, random);
    SFStrategy sfStrategy = selectSFStrategy(nfes, maxNfes, shadeGG, config, random);
    double[] shadeSF = generateSF(selectSize, D, shadeGG, sfStrategy, params.sFCauchy, params.shadeFreq, config);

    mutateCurrentToPbestInto(
      popOld, selectedIndices, sortIndex, config.archive.pop, shadeSF, config, random, workspace);
    random.nextDouble(); // Preserve performCrossover's eigen-branch draw when probEigenCrossover is 0.
    performBinomialCrossoverInto(
      popOld, selectedIndices, workspace.mutantView(selectSize), params.shadeCR, random, workspace);

    return new SHADEResult(
      workspace.trialView(selectSize), shadeGG, shadeSF, params.shadeCR, params.shadeFreq,
      sfStrategy.flag1Shade, sfStrategy.flag2Shade
    );
  }

  /**
   * Convenience method: operate on entire population
   */
  public static SHADEResult run(
    List<double[]> popOld,
    List<Double> fitness,
    int shadeGG,
    int nfes,
    int maxNfes,
    SHADEConfig config,
    List<Double> goodFit1All,
    List<Double> goodFit2All,
    List<Double> badFit1All,
    List<Double> badFit2All,
    Random random) {

    // Create indices for entire population
    int[] allIndices = IntStream.range(0, popOld.size()).toArray();

    return run(popOld, fitness, allIndices, shadeGG, nfes, maxNfes, config,
      goodFit1All, goodFit2All, badFit1All, badFit2All, random);
  }

  // =====================================================
  // Parameter generation
  // =====================================================

  private static class ParameterSet {
    double[] shadeCR;
    double[] shadeFreq;
    double[] sFCauchy;

    ParameterSet(double[] shadeCR, double[] shadeFreq, double[] sFCauchy) {
      this.shadeCR = shadeCR;
      this.shadeFreq = shadeFreq;
      this.sFCauchy = sFCauchy;
    }
  }

  private static ParameterSet generateParameters(
    int popSelectSize, int D, SHADEConfig config, Random random) {

    int memorySize = config.memorySize;
    List<Double> memorySF = config.memorySF;
    List<Double> memoryCR = config.memoryCR;
    List<Double> memoryFreq = config.memoryFreq;

    // Sample from memory
    double[] randSF = new double[popSelectSize];
    double[] randCR = new double[popSelectSize];
    double[] randFreq = new double[popSelectSize];

    for (int i = 0; i < popSelectSize; i++) {
      int randIndex = random.nextInt(memorySize);
      randSF[i] = memorySF.get(randIndex);
      randCR[i] = memoryCR.get(randIndex);
      randFreq[i] = memoryFreq.get(randIndex);
    }

    // Generate CR (Gaussian)
    double[] shadeCR = generateCR(randCR, config.crGaussianStd, random);

    // Generate SF and Freq (Cauchy)
    double[] sFCauchy = new double[popSelectSize];
    double[] shadeFreq = new double[popSelectSize];

    for (int i = 0; i < popSelectSize; i++) {
      sFCauchy[i] = sampleCauchy(randSF[i], config.cauchyScale, random);
      shadeFreq[i] = sampleCauchy(randFreq[i], config.cauchyScale, random);
    }

    return new ParameterSet(shadeCR, shadeFreq, sFCauchy);
  }

  private static int[] resolveSortIndex(List<Double> fitness, int[] rankHint) {
    if (rankHint != null && rankHint.length == fitness.size()) {
      return rankHint;
    }
    List<FitnessUtils.IndexFitnessPair> sortedPairs = FitnessUtils.sortIndexFitnessDesc(fitness);
    int[] sortIndex = new int[sortedPairs.size()];
    for (int i = 0; i < sortedPairs.size(); i++) {
      sortIndex[i] = sortedPairs.get(i).getIndex();
    }
    return sortIndex;
  }

  /**
   * Generate CR parameters using Gaussian distribution
   */
  private static double[] generateCR(double[] randCR, double crGaussianStd, Random random) {
    int size = randCR.length;
    double[] shadeCR = new double[size];

    for (int i = 0; i < size; i++) {
      double cr;
      if (randCR[i] == -1) {
        cr = 0;
      } else {
        cr = random.nextGaussian() * crGaussianStd + randCR[i];
      }
      shadeCR[i] = Math.max(0, Math.min(1, cr));
    }

    return shadeCR;
  }

  /**
   * Sample from Cauchy distribution with clamping to (0, 1]
   */
  private static double sampleCauchy(double location, double scale, Random random) {
    int attempts = 0;
    double value;

    do {
      value = scale * Math.tan(Math.PI * (random.nextDouble() - 0.5)) + location;
      attempts++;

      if (attempts >= MAX_CAUCHY_ATTEMPTS) {
        value = AlgorithmConstants.PROBABILITY_HALF;
        break;
      }
    } while (value <= 0 || value > 1.0);

    return Math.min(1, value);
  }

  // =====================================================
  // SF generation strategy
  // =====================================================

  private static class SFStrategy {
    boolean flag1Shade;
    boolean flag2Shade;
    boolean useSpecialStrategy;

    SFStrategy(boolean flag1, boolean flag2, boolean useSpecial) {
      this.flag1Shade = flag1;
      this.flag2Shade = flag2;
      this.useSpecialStrategy = useSpecial;
    }
  }

  private static SFStrategy selectSFStrategy(
    int nfes, int maxNfes, int shadeGG, SHADEConfig config, Random random) {

    // Only use special strategies in first half
    if (nfes > maxNfes / 2.0) {
      return new SFStrategy(false, false, false);
    }

    boolean flag1 = false, flag2 = false;

    if (shadeGG <= config.learnPeriod) {
      // Learning period: random choice
      if (random.nextDouble() < STRATEGY_SELECTION_THRESHOLD) {
        flag1 = true;
      } else {
        flag2 = true;
      }
    } else {
      // After learning: use learned probabilities
      double p1 = Math.max(0.0, Math.min(1.0, config.probStrategy1));
      if (random.nextDouble() < p1) {
        flag1 = true;
      } else {
        flag2 = true;
      }
    }

    return new SFStrategy(flag1, flag2, true);
  }

  private static double[] generateSF(
    int popSelectSize, int D, int shadeGG,
    SFStrategy strategy, double[] sFCauchy, double[] shadeFreq,
    SHADEConfig config) {

    double[] shadeSF = new double[popSelectSize];

    if (!strategy.useSpecialStrategy) {
      // Second half: use Cauchy distribution
      System.arraycopy(sFCauchy, 0, shadeSF, 0, popSelectSize);
      return shadeSF;
    }

    int gMax = SHADELoopBrief.SHADE_DEFAULT_GMAX;

    if (strategy.flag1Shade) {
      // Strategy 1: global unified frequency
      double val = 0.5 * (Math.sin(2 * Math.PI * config.freqIniForSF * shadeGG + Math.PI)
         * ((double)(gMax - shadeGG) / gMax) + 1);
      Arrays.fill(shadeSF, val);
    }

    if (strategy.flag2Shade) {
      // Strategy 2: individualized frequency
      for (int i = 0; i < popSelectSize; i++) {
        double val = 0.5 * (Math.sin(2 * Math.PI * shadeFreq[i] * shadeGG)
           * ((double)shadeGG / gMax) + 1);
        shadeSF[i] = val;
      }
    }

    return shadeSF;
  }

  // =====================================================
  // Mutation
  // =====================================================

  /**
   * Perform DE mutation using CURRENT_TO_PBEST strategy (index-based)
   *
   * @param pop Full population
   * @param selectedIndices Indices of individuals to evolve
   * @param sortIndex Sorted indices by fitness
   * @param archivePop Archive population
   * @param shadeSF Scaling factors
   * @param config SHADE configuration
   * @param random Random generator
   * @return Mutant vectors for selected individuals
   */
  private static List<double[]> performMutation(
    List<double[]> pop,
    int[] selectedIndices,
    int[] sortIndex,
    List<double[]> archivePop,
    double[] shadeSF,
    SHADEConfig config,
    Random random) {

    return mutateCurrentToPbest(pop, selectedIndices, sortIndex, archivePop, shadeSF, config, random);
  }

  /**
   * DE/current-to-pbest/1 with archive: v = x[i] + F*(x[pbest] - x[i] + x[r1] - x[r2])
   * Index-based: only evolve selected individuals
   */
  private static List<double[]> mutateCurrentToPbest(
    List<double[]> pop,
    int[] selectedIndices,
    int[] sortIndex,
    List<double[]> archivePop,
    double[] shadeSF,
    SHADEConfig config,
    Random random) {

    int popSize = pop.size();
    int selectSize = selectedIndices.length;
    int D = pop.get(0).length;

    int popAllSize = popSize + archivePop.size();

    // Select indices for each selected individual
    IndexSet indices = selectIndicesForCurrentToPbest(selectedIndices, popSize, popAllSize, sortIndex, pop, config, random);

    // Perform mutation
    List<double[]> vi = new ArrayList<>(selectSize);
    double lowerBound = -AlgorithmConstants.DEFAULT_BOUND;
    double upperBound = AlgorithmConstants.DEFAULT_BOUND;

    for (int idx = 0; idx < selectSize; idx++) {
      int i = selectedIndices[idx];
      double[] v = new double[D];
      double[] xi = pop.get(i);
      double F = shadeSF[idx];
      double[] xPbest = indices.pbest.get(idx);
      double[] xR1 = pop.get(indices.r1[idx]);
      double[] xR2 = indices.r2[idx] < popSize
        ? pop.get(indices.r2[idx])
        : archivePop.get(indices.r2[idx] - popSize);

      for (int j = 0; j < D; j++) {
        v[j] = xi[j] + F * (xPbest[j] - xi[j] + xR1[j] - xR2[j]);
        v[j] = BoundaryHandler.clipMidpoint(v[j], xi[j], lowerBound, upperBound);
      }
      vi.add(v);
    }

    return vi;
  }

  private static void mutateCurrentToPbestInto(
    List<double[]> pop,
    int[] selectedIndices,
    int[] sortIndex,
    List<double[]> archivePop,
    double[] shadeSF,
    SHADEConfig config,
    Random random,
    SHADEWorkspace workspace) {

    int popSize = pop.size();
    int selectSize = selectedIndices.length;
    int D = pop.get(0).length;
    int popAllSize = popSize + archivePop.size();
    IndexSet indices = selectIndicesForCurrentToPbest(selectedIndices, popSize, popAllSize, sortIndex, pop, config, random);

    double lowerBound = -AlgorithmConstants.DEFAULT_BOUND;
    double upperBound = AlgorithmConstants.DEFAULT_BOUND;

    for (int idx = 0; idx < selectSize; idx++) {
      int i = selectedIndices[idx];
      double[] v = workspace.mutantBuffer[idx];
      double[] xi = pop.get(i);
      double F = shadeSF[idx];
      double[] xPbest = indices.pbest.get(idx);
      double[] xR1 = pop.get(indices.r1[idx]);
      double[] xR2 = indices.r2[idx] < popSize
        ? pop.get(indices.r2[idx])
        : archivePop.get(indices.r2[idx] - popSize);

      for (int j = 0; j < D; j++) {
        v[j] = xi[j] + F * (xPbest[j] - xi[j] + xR1[j] - xR2[j]);
        v[j] = BoundaryHandler.clipMidpoint(v[j], xi[j], lowerBound, upperBound);
      }
    }
  }

  // =====================================================
  // Index selection
  // =====================================================

  private static class IndexSet {
    int[] r1;
    int[] r2;
    List<double[]> pbest;

    IndexSet(int[] r1, int[] r2, List<double[]> pbest) {
      this.r1 = r1;
      this.r2 = r2;
      this.pbest = pbest;
    }
  }

  /**
   * Select indices for DE/current-to-pbest/1 mutation (index-based)
   *
   * @param selectedIndices Indices of individuals to evolve
   * @param popSize Full population size
   * @param popAllSize Population + archive size
   * @param sortIndex Sorted indices by fitness
   * @param pop Full population
   * @param config SHADE configuration
   * @param random Random generator
   * @return Index set containing r1, r2, and pbest for each selected individual
   */
  private static IndexSet selectIndicesForCurrentToPbest(
    int[] selectedIndices,
    int popSize,
    int popAllSize,
    int[] sortIndex,
    List<double[]> pop,
    SHADEConfig config,
    Random random) {

    int selectSize = selectedIndices.length;
    int[] r1 = new int[selectSize];
    int[] r2 = new int[selectSize];
    List<double[]> pbest = new ArrayList<>(selectSize);

    // Calculate pNP
    int pNP = Math.max(1, Math.min((int) Math.round(config.pbestRate * popSize), popSize));

    // Select indices for each selected individual
    for (int idx = 0; idx < selectSize; idx++) {
      int currentIdx = selectedIndices[idx];

      // Select r1 (different from current individual)
      do {
        r1[idx] = random.nextInt(popSize);
      } while (r1[idx] == currentIdx);

      // Select r2 (different from current and r1)
      r2[idx] = selectR2Index(currentIdx, r1[idx], popAllSize, random);

      // Select pbest
      int pbestIdx = sortIndex[random.nextInt(pNP)];
      pbest.add(pop.get(pbestIdx));
    }

    return new IndexSet(r1, r2, pbest);
  }

  /**
   * Select r2 index with proper fallback handling
   */
  private static int selectR2Index(int i, int r1Val, int popAllSize, Random random) {
    int r2Val;
    int attempts = 0;

    do {
      r2Val = random.nextInt(popAllSize);
      attempts++;

      if (attempts >= MAX_R2_SELECTION_ATTEMPTS) {
        // Improved fallback: ensure r2 != i and r2 != r1
        r2Val = (r1Val + 1) % popAllSize;
        if (r2Val == i) {
          r2Val = (r2Val + 1) % popAllSize;
        }
        break;
      }
    } while (r2Val == i || r2Val == r1Val);

    return r2Val;
  }

  // =====================================================
  // Crossover
  // =====================================================

  /**
   * Perform crossover (binomial or eigen-based) - index-based
   *
   * @param pop Full population
   * @param selectedIndices Indices of individuals to evolve
   * @param vi Mutant vectors for selected individuals
   * @param shadeCR Crossover rates
   * @param config SHADE configuration
   * @param sortIndex Sorted indices by fitness
   * @param random Random generator
   * @return Trial vectors for selected individuals
   */
  private static List<double[]> performCrossover(
    List<double[]> pop,
    int[] selectedIndices,
    List<double[]> vi,
    double[] shadeCR,
    SHADEConfig config,
    int[] sortIndex,
    Random random) {

    if (random.nextDouble() < config.probEigenCrossover) {
      // Calculate neighbour selection size
      int neighbourSelectSize = Math.max(2,
        (int) Math.round(config.proportionSelectNeighbour * pop.size()));

      throw new UnsupportedOperationException("RKHMA 410000 不使用 SHADE 特征交叉；该可选分支未迁入");
    } else {
      return performBinomialCrossover(pop, selectedIndices, vi, shadeCR, random);
    }
  }

  private static List<Integer> toIntegerList(int[] indices) {
    List<Integer> list = new ArrayList<>(indices.length);
    for (int index : indices) {
      list.add(index);
    }
    return list;
  }

  /**
   * Standard binomial crossover - index-based
   */
  private static List<double[]> performBinomialCrossover(
    List<double[]> pop,
    int[] selectedIndices,
    List<double[]> vi,
    double[] shadeCR,
    Random random) {

    int selectSize = selectedIndices.length;
    int D = pop.get(0).length;
    List<double[]> ui = new ArrayList<>(selectSize);

    for (int idx = 0; idx < selectSize; idx++) {
      int i = selectedIndices[idx];
      double[] u = new double[D];
      int jRand = random.nextInt(D);

      for (int j = 0; j < D; j++) {
        if (random.nextDouble() < shadeCR[idx] || j == jRand) {
          u[j] = vi.get(idx)[j];
        } else {
          u[j] = pop.get(i)[j];
        }
      }
      ui.add(u);
    }

    return ui;
  }

  private static void performBinomialCrossoverInto(
    List<double[]> pop,
    int[] selectedIndices,
    List<double[]> vi,
    double[] shadeCR,
    Random random,
    SHADEWorkspace workspace) {

    int selectSize = selectedIndices.length;
    int D = pop.get(0).length;

    for (int idx = 0; idx < selectSize; idx++) {
      int i = selectedIndices[idx];
      double[] u = workspace.trialBuffer[idx];
      int jRand = random.nextInt(D);

      for (int j = 0; j < D; j++) {
        if (random.nextDouble() < shadeCR[idx] || j == jRand) {
          u[j] = vi.get(idx)[j];
        } else {
          u[j] = pop.get(i)[j];
        }
      }
    }
  }
}
