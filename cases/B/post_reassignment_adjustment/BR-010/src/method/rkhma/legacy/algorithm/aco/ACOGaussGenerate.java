package method.rkhma.legacy.algorithm.aco;
import method.rkhma.legacy.common.AlgorithmConstants;
import method.rkhma.legacy.common.AlgorithmParameterValidator;
import method.rkhma.legacy.common.BoundaryHandler;
import method.rkhma.legacy.common.FastGaussian;
import method.rkhma.legacy.core.config.ConfigValidatable;
import method.rkhma.legacy.algorithm.workspace.ACOWorkspace;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

public class ACOGaussGenerate {

  // =====================================================
  // ACO Algorithm Constants
  // =====================================================

  /** ACO Default Elite Sample Ratio */
  public static final double ACO_DEFAULT_SAMPLE_RATIO = 0.8;

  /** ACO Default Intensity Factor */
  public static final double ACO_DEFAULT_INTENSE_FACTOR = 0.35;

  /** ACO Default Deviate Distance Ratio */
  public static final double ACO_DEFAULT_DEVIATE_DISTANCE_RATIO = 0.5;

  /** Minimum sigma ratio relative to the decision-variable range. */
  public static final double ACO_DEFAULT_MIN_SIGMA_RATIO = 0.001;
  public static final String ACO_LEGACY_SIGMA_PROPERTY = "aco.legacySigma";
  private static final int FITNESS_SORT_INSERTION_CUTOFF = 16;
  private static final int SIGMA_SORT_INSERTION_CUTOFF = 16;

  /**
   * ACO parameter configuration class, centralizing the management of algorithm hyperparameters.
   */
  public static class ACOConfig implements ConfigValidatable {
    /** Elite sampling ratio (0–1), used for constructing the probability distribution */
    public final double nSample;
    /** Strength factor */
    public final double intenseFactor;
    /** Deviation Distance Ratio */
    public final double deviateDistanceRatio;
    /** Upper bound of the decision variable */
    public final double upperBound;
    /** Lower bound of the decision variable */
    public final double lowerBound;
    /** Minimum sigma ratio relative to the decision-variable range. */
    public final double minSigmaRatio;
    /** Whether to use the old pairwise sigma accumulation path. */
    public final boolean legacySigma;

    public ACOConfig(double nSample,
      double intenseFactor,
      double deviateDistanceRatio,
      double upperBound,
      double lowerBound) {
      this(nSample, intenseFactor, deviateDistanceRatio, upperBound, lowerBound,
        ACO_DEFAULT_MIN_SIGMA_RATIO);
    }

    public ACOConfig(double nSample,
      double intenseFactor,
      double deviateDistanceRatio,
      double upperBound,
      double lowerBound,
      double minSigmaRatio) {
      this(nSample, intenseFactor, deviateDistanceRatio, upperBound, lowerBound,
        minSigmaRatio, false);
    }

    public ACOConfig(double nSample,
      double intenseFactor,
      double deviateDistanceRatio,
      double upperBound,
      double lowerBound,
      double minSigmaRatio,
      boolean legacySigma) {
      this.nSample = nSample;
      this.intenseFactor = intenseFactor;
      this.deviateDistanceRatio = deviateDistanceRatio;
      this.upperBound = upperBound;
      this.lowerBound = lowerBound;
      this.minSigmaRatio = minSigmaRatio;
      this.legacySigma = legacySigma;
      ConfigValidatable.ValidationResult result = validate();
      try {
        result.throwIfInvalid();
      } catch (ConfigValidatable.ConfigValidationException e) {
        throw new IllegalArgumentException(e.getMessage(), e);
      }
    }

    @Override
    public ConfigValidatable.ValidationResult validate() {
      ConfigValidatable.ValidationResultBuilder builder = new ConfigValidatable.ValidationResultBuilder();

      // Validate nSample is in the range (0, 1]
      builder.checkRange("nSample", nSample, 0.0, 1.0);
      if (nSample <= 0.0) {
        builder.addError("nSample must be greater than 0");
      }

      // Validate intenseFactor is positive
      builder.checkPositive("intenseFactor", intenseFactor);

      // Validate deviateDistanceRatio is non-negative
      builder.checkNonNegative("deviateDistanceRatio", deviateDistanceRatio);
      builder.checkRange("minSigmaRatio", minSigmaRatio, 0.0, 1.0);

      // Validate bounds
      if (lowerBound >= upperBound) {
        builder.addError(String.format(
          "lowerBound must be less than upperBound, got lowerBound=%.4f, upperBound=%.4f",
          lowerBound, upperBound));
      }

      return builder.build();
    }

    /**
     * Provide a set of default parameters for convenient direct use.
     */
    public static ACOConfig defaultConfig() {
      return new ACOConfig(
        ACO_DEFAULT_SAMPLE_RATIO,
        ACO_DEFAULT_INTENSE_FACTOR,
        ACO_DEFAULT_DEVIATE_DISTANCE_RATIO,
        AlgorithmConstants.DEFAULT_BOUND,
        -AlgorithmConstants.DEFAULT_BOUND,
        ACO_DEFAULT_MIN_SIGMA_RATIO,
        false
      );
    }
  }

  /**
   * Execute ACO using configuration object with index-based selection.
   *
   * @param pop Full population
   * @param selectIndices Indices of individuals to operate on
   * @param popFit Full fitness list
   * @param config Configuration object containing algorithm parameters
   * @param customRand Custom random number generator
   * @return Newly generated population (same size as selectIndices)
   */
  public static List<double[]> run(
    List<double[]> pop,
    int[] selectIndices,
    List<Double> popFit,
    ACOConfig config,
    Random customRand) {
    return run(pop, selectIndices, popFit, config, customRand, null);
  }

  public static List<double[]> run(
    List<double[]> pop,
    int[] selectIndices,
    List<Double> popFit,
    ACOConfig config,
    Random customRand,
    ACOWorkspace workspace) {

    AlgorithmParameterValidator.validateCommonParameters(pop, selectIndices, popFit, config, customRand);
    AlgorithmParameterValidator.validatePopulationBounds(pop, config.lowerBound, config.upperBound);
    return runUnsafe(pop, selectIndices, popFit, config, customRand, workspace);
  }

  public static List<double[]> runUnsafe(
    List<double[]> pop,
    int[] selectIndices,
    List<Double> popFit,
    ACOConfig config,
    Random customRand,
    ACOWorkspace workspace,
    int[] fitnessRankDesc) {

    return runUnsafeInternal(
      pop,
      selectIndices,
      popFit,
      config,
      customRand,
      workspace,
      fitnessRankDesc);
  }

  /**
   * HybCec hot-path entry. Assumes population, selected indices, fitness, config,
   * and random generator were validated at the strategy/state boundary.
   */
  public static List<double[]> runUnsafe(
    List<double[]> pop,
    int[] selectIndices,
    List<Double> popFit,
    ACOConfig config,
    Random customRand,
    ACOWorkspace workspace) {

    return runUnsafeInternal(
      pop,
      selectIndices,
      popFit,
      config,
      customRand,
      workspace,
      null);
  }

  private static List<double[]> runUnsafeInternal(
    List<double[]> pop,
    int[] selectIndices,
    List<Double> popFit,
    ACOConfig config,
    Random customRand,
    ACOWorkspace workspace,
    int[] fitnessRankDesc) {

    int nSelect = selectIndices.length;
    int N = pop.size();  // Full population size
    int D = pop.get(0).length;

    // ========================================
    // 1. Select elite individuals from the entire population (not just selectIndices)
    // ========================================

    // Calculate number of elites based on full population size
    int nElite = Math.max(1, (int) Math.round(config.nSample * N));
    if (workspace != null) {
      workspace.ensureCapacity(nElite, D, N);
    }

    int[] fitnessOrder = workspace != null ? workspace.fitnessOrder : new int[N];
    if (!copyFitnessRankIfUsable(fitnessRankDesc, fitnessOrder, N)) {
      sortFitnessIndexDesc(popFit, fitnessOrder, N);
    }

    // Extract top nElite individuals from entire population
    double[][] elitePop = workspace != null
      ? workspace.elitePop
      : new double[nElite][];
    double searchRange = config.upperBound - config.lowerBound;
    double[][] sigma;
    if (workspace != null && workspace.hasDistributionCache(
      pop,
      fitnessOrder,
      nElite,
      D,
      config.deviateDistanceRatio,
      searchRange,
      config.minSigmaRatio,
      config.legacySigma)) {
      sigma = workspace.sigma;
    } else {
      fillElitePopulation(pop, fitnessOrder, elitePop, nElite);
      sigma = calculateSigma(
        elitePop,
        config.deviateDistanceRatio,
        searchRange,
        config.minSigmaRatio,
        config.legacySigma,
        nElite,
        D,
        workspace
      );
      if (workspace != null) {
        workspace.markDistributionCache(
          fitnessOrder,
          nElite,
          D,
          config.deviateDistanceRatio,
          searchRange,
          config.minSigmaRatio,
          config.legacySigma);
      }
    }

    // ========================================
    // 2. Compute weights and cumulative probabilities for elite individuals
    // ========================================

    double[] cumulativeProb;
    if (workspace != null) {
      if (!workspace.hasWeightCache(nElite, config.intenseFactor)) {
        calculateGaussianWeights(nElite, config.intenseFactor, workspace.weights);
        calculateCumulativeProbabilities(workspace.weights, workspace.cumulativeProb, nElite);
        workspace.markWeightCache(nElite, config.intenseFactor);
      }
      cumulativeProb = workspace.cumulativeProb;
    } else {
      double[] weights = new double[nElite];
      double[] cumulative = new double[nElite];
      calculateGaussianWeights(nElite, config.intenseFactor, weights);
      calculateCumulativeProbabilities(weights, cumulative, nElite);
      cumulativeProb = cumulative;
    }

    // ========================================
    // 4. Generate new individuals (same number as selectIndices)
    // ========================================

    List<double[]> newPop = new ArrayList<>(nSelect);

    for (int t = 0; t < nSelect; t++) {
      double[] newIndividual = new double[D];

      // ACOR samples one parent vector, then perturbs all dimensions around it.
      int selectedIdx = binarySearchRoulette(cumulativeProb, nElite, customRand.nextDouble());
      double[] selectedElite = elitePop[selectedIdx];
      double[] selectedSigma = sigma[selectedIdx];
      for (int i = 0; i < D; i++) {
        // Generate new value based on Gaussian distribution
        double gaussian = FastGaussian.isEnabled()
          ? FastGaussian.nextGaussian(customRand)
          : customRand.nextGaussian();
        newIndividual[i] = selectedElite[i] + selectedSigma[i] * gaussian;

        // Boundary constraints
        newIndividual[i] = BoundaryHandler.clip(newIndividual[i], config.lowerBound, config.upperBound);
      }

      newPop.add(newIndividual);
    }

    return newPop;
  }

  private static boolean copyFitnessRankIfUsable(int[] sourceRank, int[] targetOrder, int n) {
    if (sourceRank == null || sourceRank.length < n) {
      return false;
    }
    System.arraycopy(sourceRank, 0, targetOrder, 0, n);
    return true;
  }

  private static void fillElitePopulation(
    List<double[]> pop,
    int[] fitnessOrder,
    double[][] elitePop,
    int nElite) {
    for (int i = 0; i < nElite; i++) {
      int eliteIndex = fitnessOrder[i];
      elitePop[i] = pop.get(eliteIndex);
    }
  }

  static void sortFitnessIndexDescForTest(List<Double> popFit, int[] order, int n) {
    sortFitnessIndexDesc(popFit, order, n);
  }

  private static void sortFitnessIndexDesc(List<Double> popFit, int[] order, int n) {
    for (int i = 0; i < n; i++) {
      order[i] = i;
    }
    sortFitnessOrder(popFit, order, 0, n - 1);
  }

  private static void sortFitnessOrder(List<Double> popFit, int[] order, int low, int high) {
    while (low < high) {
      if (high - low + 1 <= FITNESS_SORT_INSERTION_CUTOFF) {
        insertionSortFitnessOrder(popFit, order, low, high);
        return;
      }
      int pivotIndex = partitionFitnessOrder(popFit, order, low, high);
      if (pivotIndex - low < high - pivotIndex) {
        sortFitnessOrder(popFit, order, low, pivotIndex - 1);
        low = pivotIndex + 1;
      } else {
        sortFitnessOrder(popFit, order, pivotIndex + 1, high);
        high = pivotIndex - 1;
      }
    }
  }

  private static int partitionFitnessOrder(List<Double> popFit, int[] order, int low, int high) {
    int pivot = medianOfThreeFitness(popFit, order, low, (low + high) >>> 1, high);
    swap(order, pivot, high);
    int pivotIndex = order[high];
    int store = low;
    for (int i = low; i < high; i++) {
      if (compareFitnessIndex(popFit, order[i], pivotIndex) <= 0) {
        swap(order, store, i);
        store++;
      }
    }
    swap(order, store, high);
    return store;
  }

  private static void insertionSortFitnessOrder(List<Double> popFit, int[] order, int low, int high) {
    for (int i = low + 1; i <= high; i++) {
      int index = order[i];
      int j = i - 1;
      while (j >= low && compareFitnessIndex(popFit, order[j], index) > 0) {
        order[j + 1] = order[j];
        j--;
      }
      order[j + 1] = index;
    }
  }

  private static int medianOfThreeFitness(List<Double> popFit, int[] order, int a, int b, int c) {
    if (compareFitnessIndex(popFit, order[a], order[b]) > 0) {
      int tmp = a;
      a = b;
      b = tmp;
    }
    if (compareFitnessIndex(popFit, order[b], order[c]) > 0) {
      int tmp = b;
      b = c;
      c = tmp;
    }
    if (compareFitnessIndex(popFit, order[a], order[b]) > 0) {
      return a;
    }
    return b;
  }

  private static int compareFitnessIndex(List<Double> popFit, int leftIndex, int rightIndex) {
    int fitnessCompare = Double.compare(popFit.get(rightIndex), popFit.get(leftIndex));
    return fitnessCompare != 0 ? fitnessCompare : Integer.compare(leftIndex, rightIndex);
  }

  /**
   * Compute Gaussian weights
   *
   * @param intenseFactor Intensity factor
   * @return Weight list
   */

  private static void calculateGaussianWeights(int nPop, double intenseFactor, double[] weights) {
    double term1 = 1.0 / (Math.sqrt(2 * Math.PI) * intenseFactor * nPop);

    for (int i = 0; i < nPop; i++) {
      double exponent = -0.5 * Math.pow(i / (intenseFactor * nPop), 2);
      weights[i] = term1 * Math.exp(exponent);
    }
  }

  /**
   * Compute cumulative probability distribution
   *
   * @param weights Weight list
   * @return Cumulative probability list
   *
   * FIXED: Corrected cumulative probability calculation.
   * First compute total weight, then compute cumulative probabilities.
   */

  private static void calculateCumulativeProbabilities(double[] weights, double[] cumulativeProb, int n) {
    // First pass: compute total weight
    double sumW = 0;
    for (int i = 0; i < n; i++) {
      sumW += weights[i];
    }

    // Second pass: compute cumulative probabilities
    double cumulative = 0.0;

    for (int i = 0; i < n; i++) {
      cumulative += weights[i];
      cumulativeProb[i] = cumulative / sumW;
    }

    // Ensure final probability is exactly 1.0 to avoid floating-point errors
    if (n > 0) {
      cumulativeProb[n - 1] = 1.0;
    }
  }

  /**
   * Calculate ACOR-style sigma matrix.
   *
   * @param sortedPop Sorted population
   * @param deviateDistanceRatio Deviation ratio
   * @param searchRange Decision-variable range
   * @param nPop Population size
   * @param D Number of dimensions
   * @return List of sigma arrays List<double[]>
   *
   * Uses the mean pairwise distance from each elite to the other elites, with
   * a small floor to avoid sampling collapse when elites cluster.
   */

  static double[][] calculateSigmaForTest(
    double[][] sortedPop,
    double deviateDistanceRatio,
    double searchRange,
    double minSigmaRatio,
    boolean legacySigma,
    int nPop,
    int D,
    ACOWorkspace workspace) {
    if (workspace != null) {
      workspace.ensureCapacity(nPop, D);
    }
    return calculateSigma(
      sortedPop,
      deviateDistanceRatio,
      searchRange,
      minSigmaRatio,
      legacySigma,
      nPop,
      D,
      workspace
    );
  }

  private static double[][] calculateSigma(
    double[][] sortedPop,
    double deviateDistanceRatio,
    double searchRange,
    double minSigmaRatio,
    boolean legacySigma,
    int nPop,
    int D,
    ACOWorkspace workspace) {

    double[][] sigma = workspace != null ? workspace.sigma : new double[nPop][D];
    double minSigma = Math.max(0.0, searchRange) * minSigmaRatio;

    if (legacySigma) {
      return calculateSigmaPairwise(sortedPop, deviateDistanceRatio, minSigma, nPop, D, sigma);
    }

    if (nPop <= 1) {
      for (int l = 0; l < nPop; l++) {
        Arrays.fill(sigma[l], 0, D, minSigma);
      }
      return sigma;
    }

    double[] values = workspace != null ? workspace.sigmaSortValues : new double[nPop];
    int[] rows = workspace != null ? workspace.sigmaSortRows : new int[nPop];

    for (int d = 0; d < D; d++) {
      for (int row = 0; row < nPop; row++) {
        values[row] = sortedPop[row][d];
        rows[row] = row;
      }
      sortByValue(values, rows, 0, nPop - 1);

      double prefix = 0.0;
      double suffix = 0.0;
      for (int rank = 0; rank < nPop; rank++) {
        suffix += values[rank];
      }

      for (int rank = 0; rank < nPop; rank++) {
        double value = values[rank];
        suffix -= value;
        double lowerDistance = value * rank - prefix;
        double upperDistance = suffix - value * (nPop - rank - 1);
        double meanPairwiseDistance = (lowerDistance + upperDistance) / (nPop - 1);
        sigma[rows[rank]][d] = Math.max(minSigma, deviateDistanceRatio * meanPairwiseDistance);
        prefix += value;
      }
    }

    return sigma;
  }

  private static double[][] calculateSigmaPairwise(
    double[][] sortedPop,
    double deviateDistanceRatio,
    double minSigma,
    int nPop,
    int D,
    double[][] sigma) {

    for (int l = 0; l < nPop; l++) {
      double[] sigmaRow = sigma[l];
      double[] elite = sortedPop[l];
      for (int d = 0; d < D; d++) {
        double distanceSum = 0.0;
        for (int k = 0; k < nPop; k++) {
          if (k != l) {
            distanceSum += Math.abs(elite[d] - sortedPop[k][d]);
          }
        }
        double meanPairwiseDistance = (nPop > 1) ? distanceSum / (nPop - 1) : 0.0;
        sigmaRow[d] = Math.max(minSigma, deviateDistanceRatio * meanPairwiseDistance);
      }
    }

    return sigma;
  }

  private static void sortByValue(double[] values, int[] rows, int low, int high) {
    while (low < high) {
      if (high - low + 1 <= SIGMA_SORT_INSERTION_CUTOFF) {
        insertionSortByValue(values, rows, low, high);
        return;
      }
      int pivotIndex = partitionByValue(values, rows, low, high);
      if (pivotIndex - low < high - pivotIndex) {
        sortByValue(values, rows, low, pivotIndex - 1);
        low = pivotIndex + 1;
      } else {
        sortByValue(values, rows, pivotIndex + 1, high);
        high = pivotIndex - 1;
      }
    }
  }

  private static int partitionByValue(double[] values, int[] rows, int low, int high) {
    int pivot = medianOfThree(values, rows, low, (low + high) >>> 1, high);
    swap(values, rows, pivot, high);
    double pivotValue = values[high];
    int pivotRow = rows[high];
    int store = low;
    for (int i = low; i < high; i++) {
      int compare = compareValueRow(values[i], rows[i], pivotValue, pivotRow);
      if (compare <= 0) {
        swap(values, rows, store, i);
        store++;
      }
    }
    swap(values, rows, store, high);
    return store;
  }

  private static void insertionSortByValue(double[] values, int[] rows, int low, int high) {
    for (int i = low + 1; i <= high; i++) {
      double value = values[i];
      int row = rows[i];
      int j = i - 1;
      while (j >= low && compareValueRow(values[j], rows[j], value, row) > 0) {
        values[j + 1] = values[j];
        rows[j + 1] = rows[j];
        j--;
      }
      values[j + 1] = value;
      rows[j + 1] = row;
    }
  }

  private static int medianOfThree(double[] values, int[] rows, int a, int b, int c) {
    if (compareValueRow(values[a], rows[a], values[b], rows[b]) > 0) {
      int tmp = a;
      a = b;
      b = tmp;
    }
    if (compareValueRow(values[b], rows[b], values[c], rows[c]) > 0) {
      int tmp = b;
      b = c;
      c = tmp;
    }
    if (compareValueRow(values[a], rows[a], values[b], rows[b]) > 0) {
      return a;
    }
    return b;
  }

  private static int compareValueRow(double leftValue, int leftRow, double rightValue, int rightRow) {
    int valueCompare = Double.compare(leftValue, rightValue);
    return valueCompare != 0 ? valueCompare : Integer.compare(leftRow, rightRow);
  }

  private static void swap(double[] values, int[] rows, int i, int j) {
    if (i == j) {
      return;
    }
    double value = values[i];
    values[i] = values[j];
    values[j] = value;
    int row = rows[i];
    rows[i] = rows[j];
    rows[j] = row;
  }

  private static void swap(int[] values, int i, int j) {
    if (i == j) {
      return;
    }
    int value = values[i];
    values[i] = values[j];
    values[j] = value;
  }

  private static int binarySearchRoulette(double[] cumulativeProb, int n, double r) {
    int low = 0;
    int high = n - 1;
    while (low < high) {
      int mid = (low + high) >>> 1;
      if (r <= cumulativeProb[mid]) {
        high = mid;
      } else {
        low = mid + 1;
      }
    }
    return low;
  }

}
