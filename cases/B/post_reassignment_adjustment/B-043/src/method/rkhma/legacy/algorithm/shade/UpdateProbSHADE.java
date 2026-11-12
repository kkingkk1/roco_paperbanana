package method.rkhma.legacy.algorithm.shade;
import java.util.ArrayList;
import java.util.List;
import method.rkhma.legacy.common.AlgorithmConstants;

/**
 * SHADE parameter memory update using index-based population access.
 *
 * <p>This class updates SHADE's adaptive parameter memories (SF, CR, Freq) based on
 * the success/failure of individuals in the current generation. It uses weighted
 * Lehmer mean to compute new memory values.
 *
 * <p><strong>Performance:</strong> Index-based access (O(1)) instead of List comparisons
 * provides significant speedup for large populations.
 */
public class UpdateProbSHADE {

  // ====================================================================
  // Constants
  // ====================================================================

  /** Default parameter value when weighted mean denominator is zero */
  private static final double DEFAULT_PARAMETER_VALUE = 0.5;

  /** Special value indicating all CR/Freq values are near zero */
  private static final double SPECIAL_FLAG_ALL_ZERO = -1.0;

  /** Threshold for considering a value as effectively zero */
  private static final double ZERO_THRESHOLD = 1e-12;

  // ====================================================================
  // Public API
  // ====================================================================

  /**
   * Updates SHADE parameter memories based on generation results (index-based version).
   *
   * <p>This is the <strong>recommended method</strong> providing O(1) index lookup performance.
   *
   * <p><strong>Algorithm steps:</strong>
   * <ol>
   *   <li>Collect success/failure data by comparing offspring vs parent fitness</li>
   *   <li>Update strategy statistics (goodFit/badFit counters)</li>
   *   <li>Update external archive with replaced parents</li>
   *   <li>Compute weighted Lehmer mean for SF, CR, Freq using improvement magnitudes as weights</li>
   *   <li>Store updated values in memory and advance memory position</li>
   * </ol>
   *
   * <p><strong>Lehmer Mean Formula:</strong>
   * <pre>
   * weighted_mean = Σ(w_i × x_i²) / Σ(w_i × x_i)
   * where w_i = (newFit[i] - oldFit[i]) / Σ(all improvements)
   * </pre>
   *
   * <p><strong>Special values:</strong>
   * <ul>
   *   <li>-1.0 for CR/Freq: Indicates all successful values were near zero</li>
   * </ul>
   *
   * @param pop Full parent population (must not be null, contains all individuals)
   * @param popFit Full parent fitness values (must match pop size)
   * @param selectIndices Indices of individuals that were evolved (must be valid indices into pop)
   * @param newPopFit Offspring fitness values (must match selectIndices length)
   * @param shadeSF SF vectors used in this generation (must match selectIndices length)
   * @param shadeCR CR values used in this generation (must match selectIndices length)
   * @param shadeFreq Freq values used in this generation (must match selectIndices length)
   * @param archive External archive for storing replaced individuals
   * @param goodFit1All Strategy 1 success statistics
   * @param badFit1All Strategy 1 failure statistics
   * @param goodFit2All Strategy 2 success statistics
   * @param badFit2All Strategy 2 failure statistics
   * @param memoryPos Current memory position (circular buffer index)
   * @param memorySf SF memory (circular buffer)
   * @param memoryCr CR memory (circular buffer)
   * @param memoryFreq Freq memory (circular buffer)
   * @param flag1Shade True if Strategy 1 was used in this generation
   * @param flag2Shade True if Strategy 2 was used in this generation
   * @param memorySize Size of memory buffers
   * @return Updated memory position (incremented and wrapped if needed)
   * @throws IllegalArgumentException If input parameters are invalid
   */
  public static int updateProbSHADE(
    List<double[]> pop,
    List<Double> popFit,
    int[] selectIndices,
    List<Double> newPopFit,
    List<double[]> shadeSF,
    List<Double> shadeCR,
    List<Double> shadeFreq,
    UpdateArchive.ListArchive archive,
    List<Double> goodFit1All,
    List<Double> badFit1All,
    List<Double> goodFit2All,
    List<Double> badFit2All,
    int memoryPos,
    List<Double> memorySf,
    List<Double> memoryCr,
    List<Double> memoryFreq,
    boolean flag1Shade,
    boolean flag2Shade,
    int memorySize) {

    // =======================================================
    // Input validation
    // =======================================================

    validateInputs(pop, popFit, selectIndices, newPopFit, shadeSF, shadeCR,
      shadeFreq, memoryPos, memorySize);

    int selectSize = selectIndices.length;

    // =======================================================
    // Collect success/failure data
    // =======================================================

    List<Double> goodCR = new ArrayList<>();
    List<double[]> goodSF = new ArrayList<>();
    List<Double> goodFreq = new ArrayList<>();
    List<Double> differenceValues = new ArrayList<>();
    List<double[]> badSF = new ArrayList<>();

    List<double[]> archiveCandidatesPop = new ArrayList<>();
    List<Double> archiveCandidatesFit = new ArrayList<>();

    for (int i = 0; i < selectSize; i++) {
      int parentIdx = selectIndices[i];
      double parentFitness = popFit.get(parentIdx);
      double offspringFitness = newPopFit.get(i);

      if (offspringFitness > parentFitness) {
        // Offspring better → success (maximization problem)
        goodCR.add(shadeCR.get(i));
        goodSF.add(shadeSF.get(i));
        goodFreq.add(shadeFreq.get(i));
        differenceValues.add(offspringFitness - parentFitness);

        archiveCandidatesPop.add(pop.get(parentIdx));
        archiveCandidatesFit.add(parentFitness);
      } else {
        // Parent better or equal → failure
        badSF.add(shadeSF.get(i));
      }
    }

    // =======================================================
    // Update statistics
    // =======================================================

    updateStrategyStatistics(
      flag1Shade, flag2Shade,
      goodSF.size(), badSF.size(),
      goodFit1All, badFit1All, goodFit2All, badFit2All
    );

    // =======================================================
    // Update external archive
    // =======================================================

    if (!archiveCandidatesPop.isEmpty()) {
      UpdateArchive.updateArchive(
        archive,
        archiveCandidatesPop,
        archiveCandidatesFit
      );
    }

    int newMemoryPos = memoryPos;

    // =======================================================
    // Update parameter memories
    // =======================================================

    if (!goodCR.isEmpty()) {
      double sumDifference = 0.0;
      for (double diff : differenceValues) {
        sumDifference += diff;
      }

      if (sumDifference > 0) {
        // Compute weighted Lehmer means
        double weightedSFNumerator = 0.0, weightedSFDenominator = 0.0;
        double weightedCRNumerator = 0.0, weightedCRDenominator = 0.0;
        double weightedFreqNumerator = 0.0, weightedFreqDenominator = 0.0;

        for (int i = 0; i < goodCR.size(); i++) {
          double weight = differenceValues.get(i) / sumDifference;

          // Mean of SF vector (avoid Stream overhead)
          double meanSf = computeMean(goodSF.get(i));

          weightedSFNumerator += weight * meanSf * meanSf;
          weightedSFDenominator += weight * meanSf;

          double cr = goodCR.get(i);
          weightedCRNumerator += weight * cr * cr;
          weightedCRDenominator += weight * cr;

          double freq = goodFreq.get(i);
          weightedFreqNumerator += weight * freq * freq;
          weightedFreqDenominator += weight * freq;
        }

        // Update SF memory
        memorySf.set(
          newMemoryPos,
          computeLehmerMean(weightedSFNumerator, weightedSFDenominator)
        );

        // Update CR memory (with special handling for all-zero case)
        memoryCr.set(
          newMemoryPos,
          areAllValuesNearZero(goodCR)
          ? SPECIAL_FLAG_ALL_ZERO
          : computeLehmerMean(weightedCRNumerator, weightedCRDenominator)
        );

        // Update Freq memory (with special handling for all-zero case)
        memoryFreq.set(
          newMemoryPos,
          areAllValuesNearZero(goodFreq)
          ? SPECIAL_FLAG_ALL_ZERO
          : computeLehmerMean(weightedFreqNumerator, weightedFreqDenominator)
        );
      }

      // Advance memory position (circular buffer)
      newMemoryPos++;
      if (newMemoryPos >= memorySize) {
        newMemoryPos = 0;
      }
    }

    return newMemoryPos;
  }

  /**
   * Updates SHADE parameter memories (array-based overload for compatibility).
   *
   * <p>This overload accepts arrays instead of Lists for SF, CR, and Freq parameters,
   * useful when working with SHADELoopBrief results.
   *
   * @param pop Full parent population
   * @param popFit Full parent fitness values
   * @param selectIndices Indices of individuals that were evolved
   * @param newPopFit Offspring fitness values
   * @param shadeSF SF values as a single array (one per individual, not vectors)
   * @param shadeCR CR values as array
   * @param shadeFreq Freq values as array
   * @param archive External archive
   * @param goodFit1All Strategy 1 success statistics
   * @param badFit1All Strategy 1 failure statistics
   * @param goodFit2All Strategy 2 success statistics
   * @param badFit2All Strategy 2 failure statistics
   * @param memoryPos Current memory position
   * @param memorySf SF memory
   * @param memoryCr CR memory
   * @param memoryFreq Freq memory
   * @param flag1Shade True if Strategy 1 was used
   * @param flag2Shade True if Strategy 2 was used
   * @param memorySize Size of memory buffers
   * @return Updated memory position
   * @throws IllegalArgumentException If input parameters are invalid
   */
  public static int updateProbSHADE(
    List<double[]> pop,
    List<Double> popFit,
    int[] selectIndices,
    List<Double> newPopFit,
    double[] shadeSF,
    double[] shadeCR,
    double[] shadeFreq,
    UpdateArchive.ListArchive archive,
    List<Double> goodFit1All,
    List<Double> badFit1All,
    List<Double> goodFit2All,
    List<Double> badFit2All,
    int memoryPos,
    List<Double> memorySf,
    List<Double> memoryCr,
    List<Double> memoryFreq,
    boolean flag1Shade,
    boolean flag2Shade,
    int memorySize) {

    // Convert arrays to Lists
    List<double[]> shadeSFList = new ArrayList<>(shadeSF.length);
    for (double sf : shadeSF) {
      shadeSFList.add(new double[]{sf});  // Wrap scalar as single-element array
    }

    List<Double> shadeCRList = new ArrayList<>(shadeCR.length);
    for (double cr : shadeCR) {
      shadeCRList.add(cr);
    }

    List<Double> shadeFreqList = new ArrayList<>(shadeFreq.length);
    for (double freq : shadeFreq) {
      shadeFreqList.add(freq);
    }

    // Delegate to main implementation
    return updateProbSHADE(
      pop, popFit, selectIndices, newPopFit,
      shadeSFList, shadeCRList, shadeFreqList,
      archive, goodFit1All, badFit1All, goodFit2All, badFit2All,
      memoryPos, memorySf, memoryCr, memoryFreq,
      flag1Shade, flag2Shade, memorySize
    );
  }

  // ====================================================================
  // Private Helper Methods
  // ====================================================================

  /**
   * Validates input parameters.
   *
   * @throws IllegalArgumentException If any validation fails
   */
  private static void validateInputs(
    List<double[]> pop, List<Double> popFit, int[] selectIndices,
    List<Double> newPopFit, List<double[]> shadeSF, List<Double> shadeCR,
    List<Double> shadeFreq, int memoryPos, int memorySize) {

    if (pop == null || pop.isEmpty()) {
      throw new IllegalArgumentException("Population cannot be null or empty");
    }
    if (popFit == null || popFit.size() != pop.size()) {
      throw new IllegalArgumentException("Population fitness must match population size");
    }
    if (selectIndices == null || selectIndices.length == 0) {
      throw new IllegalArgumentException("Select indices cannot be null or empty");
    }

    int selectSize = selectIndices.length;

    if (newPopFit == null || newPopFit.size() != selectSize) {
      throw new IllegalArgumentException("New population fitness must match select indices length");
    }
    if (shadeSF == null || shadeSF.size() != selectSize) {
      throw new IllegalArgumentException("SHADE SF must match select indices length");
    }
    if (shadeCR == null || shadeCR.size() != selectSize) {
      throw new IllegalArgumentException("SHADE CR must match select indices length");
    }
    if (shadeFreq == null || shadeFreq.size() != selectSize) {
      throw new IllegalArgumentException("SHADE Freq must match select indices length");
    }
    if (memoryPos < 0 || memoryPos >= memorySize) {
      throw new IllegalArgumentException(
        String.format("Memory position %d must be in range [0, %d)", memoryPos, memorySize)
      );
    }

    // Validate indices are within bounds
    for (int i = 0; i < selectIndices.length; i++) {
      int idx = selectIndices[i];
      if (idx < 0 || idx >= pop.size()) {
        throw new IllegalArgumentException(
          String.format("Invalid index at selectIndices[%d]: %d (must be in [0, %d))",
          i, idx, pop.size())
        );
      }
    }
  }

  /**
   * Updates strategy statistics based on success/failure counts.
   *
   * @param flag1Shade True if Strategy 1 was used
   * @param flag2Shade True if Strategy 2 was used
   * @param successCount Number of successful individuals
   * @param failureCount Number of failed individuals
   * @param goodFit1All Strategy 1 success statistics
   * @param badFit1All Strategy 1 failure statistics
   * @param goodFit2All Strategy 2 success statistics
   * @param badFit2All Strategy 2 failure statistics
   */
  private static void updateStrategyStatistics(
    boolean flag1Shade, boolean flag2Shade,
    int successCount, int failureCount,
    List<Double> goodFit1All, List<Double> badFit1All,
    List<Double> goodFit2All, List<Double> badFit2All) {

    if (flag1Shade) {
      goodFit1All.add((double) successCount);
      badFit1All.add((double) failureCount);
      goodFit2All.add(SHADELoopBrief.SHADE_STRATEGY_STATS_PSEUDO_COUNT);
      badFit2All.add(SHADELoopBrief.SHADE_STRATEGY_STATS_PSEUDO_COUNT);
    } else if (flag2Shade) {
      goodFit2All.add((double) successCount);
      badFit2All.add((double) failureCount);
      goodFit1All.add(SHADELoopBrief.SHADE_STRATEGY_STATS_PSEUDO_COUNT);
      badFit1All.add(SHADELoopBrief.SHADE_STRATEGY_STATS_PSEUDO_COUNT);
    }
  }

  /**
   * Computes arithmetic mean of an array (optimized to avoid Stream overhead).
   *
   * @param values Array of values
   * @return Arithmetic mean, or 0.0 if array is empty
   */
  private static double computeMean(double[] values) {
    if (values == null || values.length == 0) {
      return 0.0;
    }
    double sum = 0.0;
    for (double v : values) {
      sum += v;
    }
    return sum / values.length;
  }

  /**
   * Computes weighted Lehmer mean from precomputed numerator and denominator.
   *
   * <p>Lehmer mean formula: mean = Σ(w_i × x_i²) / Σ(w_i × x_i)
   *
   * @param numerator Σ(w_i × x_i²)
   * @param denominator Σ(w_i × x_i)
   * @return Lehmer mean, or default value if denominator is zero
   */
  private static double computeLehmerMean(double numerator, double denominator) {
    if (Math.abs(denominator) < ZERO_THRESHOLD) {
      return DEFAULT_PARAMETER_VALUE;
    }
    return numerator / denominator;
  }

  /**
   * Checks if all values in a list are near zero.
   *
   * <p><strong>Fixed logic:</strong> Checks ALL values, not just the maximum.
   * This correctly identifies when the entire list contains near-zero values.
   *
   * @param values List of values to check
   * @return True if all values are within ZERO_THRESHOLD of zero
   */
  private static boolean areAllValuesNearZero(List<Double> values) {
    if (values == null || values.isEmpty()) {
      return false;
    }

    for (Double value : values) {
      if (value != null && Math.abs(value) >= ZERO_THRESHOLD) {
        return false;
      }
    }

    return true;
  }
}
