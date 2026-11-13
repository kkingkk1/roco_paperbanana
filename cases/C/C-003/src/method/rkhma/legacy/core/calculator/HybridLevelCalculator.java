package method.rkhma.legacy.core.calculator;

import method.rkhma.legacy.common.AlgorithmConstants;
import method.rkhma.legacy.core.state.HybCecState;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Hybrid Level Calculator
 *
 * Responsible for calculating the hybrid level and updating the mask, including:
 * - Determine the hybrid Level of OA1 and OA2.
 * - Calculate update masks at the component level
 * - Negative component Update Mask
 * - Calculate the number of individuals that need to be updated.
 *
 * By centrally managing hybrid-level logic, it is possible to:
 * 1. Make hybrid-level logic independent to facilitate testing.
 * 2. Facilitates the optimization of hybrid-level computational performance.
 * 3. Supports various hybrid strategies
 * 4. Reduce the complexity of the HybCec class
 */
public class HybridLevelCalculator {

  private static final String MIXED_TOP_RATIO_PROPERTY = "hybcec.mixedTopRatio";

  /** Operation Algorithm 1 index */
  private static final int INDEX_OA1 = 0;

  /** Operation Algorithm 2 index */
  private static final int INDEX_OA2 = 1;

  /** State Manager Reference */
  private final HybCecState state;

  /** Dimension */
  private final int D;

  /** Operation-level array */
  private final int[] oL;

  /** Maximum evaluations/iterations used for linear update-ratio decay. */
  private final int maxItera;

  /** Random Operation Level */
  private final int oLRand;

  /** Operation order */
  private final int oO;

  /** Random source owned by the current HybCec run. */
  private final Random rand;

  /**
   * Construct hybrid level calculator
   */
  public HybridLevelCalculator(HybCecState state, int D,
    int[] oL, int maxItera, int oLRand, int oO, Random rand) {
    if (rand == null) {
      throw new IllegalArgumentException("rand cannot be null");
    }
    this.state = state;
    this.D = D;
    this.oL = oL;
    this.maxItera = maxItera;
    this.oLRand = oLRand;
    this.oO = oO;
    this.rand = rand;
  }

  // ==========================================================
  // In-place mask helpers (avoid per-iteration allocation)
  // ==========================================================

  /** Reusable sort index array for selectSub (avoids re-allocation each iteration) */
  private Integer[] sortBuf;

  private double getMixedTopRatio() {
    String rawRatio = System.getProperty(MIXED_TOP_RATIO_PROPERTY);
    if (rawRatio == null || rawRatio.trim().isEmpty()) {
      return AlgorithmConstants.UPDATE_POLICY_MIXED_TOP_RATIO;
    }
    try {
      double ratio = Double.parseDouble(rawRatio.trim());
      if (!Double.isFinite(ratio)) {
        return AlgorithmConstants.UPDATE_POLICY_MIXED_TOP_RATIO;
      }
      return Math.max(0.0, Math.min(1.0, ratio));
    } catch (NumberFormatException e) {
      return AlgorithmConstants.UPDATE_POLICY_MIXED_TOP_RATIO;
    }
  }

  /**
   * Ensure a boolean[] mask list has exactly nPop entries, each of length D.
   * If the list is empty or undersized, entries are added. If oversized, extras are removed.
   */
  private void ensureMaskCapacity(List<boolean[]> mask, int nPop) {
    int current = mask.size();
    if (current < nPop) {
      for (int i = current; i < nPop; i++) mask.add(new boolean[D]);
    } else {
      while (mask.size() > nPop) mask.remove(mask.size() - 1);
    }
  }

  /**
   * Ensure a Boolean (indMask) list has exactly nPop entries.
   */
  private static void ensureIndMaskCapacity(List<Boolean> indMask, int nPop) {
    int current = indMask.size();
    if (current < nPop) {
      for (int i = current; i < nPop; i++) indMask.add(Boolean.FALSE);
    } else {
      while (indMask.size() > nPop) indMask.remove(indMask.size() - 1);
    }
  }

  /**
   * Compute only resUnitMask (complement of unitMask) without modifying indMask.
   * Used for checking if fallback is needed in parallel mode.
   */
  private static void computeResUnitMaskOnly(List<boolean[]> unitMask, List<boolean[]> resUnitMask) {
    int nPop = unitMask.size();
    for (int i = 0; i < nPop; i++) {
      boolean[] src = unitMask.get(i);
      boolean[] dst = resUnitMask.get(i);
      for (int j = 0; j < src.length; j++) {
        dst[j] = !src[j];
      }
    }
  }

  /**
   * Clear an existing N×D boolean mask (set all to false).
   */
  private static void clearMask(List<boolean[]> mask) {
    for (boolean[] row : mask) {
      Arrays.fill(row, false);
    }
  }

  /**
   * Copy mask contents from src to dst in-place (no allocation).
   */
  private static void copyMask(List<boolean[]> src, List<boolean[]> dst) {
    for (int i = 0; i < src.size(); i++) {
      System.arraycopy(src.get(i), 0, dst.get(i), 0, src.get(i).length);
    }
  }

  /**
   * Fill an existing mask in-place for a cross-level operation.
   */
  private void fillMaskCrossLevelInPlace(List<boolean[]> mask, int oLCode,
    List<double[]> pop, double[] gbestSolution,
    List<Double> popFit, int nPop) {
    clearMask(mask);
    int oLReal = AlgorithmConstants.getUpdateRatioOperationLevel(oLCode);

    switch (oLReal) {
      case AlgorithmConstants.OPERATION_LEVEL_POPULATION:
        for (boolean[] row : mask) Arrays.fill(row, true);
        break;
      case AlgorithmConstants.OPERATION_LEVEL_INDIVIDUAL:
        fillIndividualsByUpdateRatioInPlace(mask, oLCode, popFit, nPop, pop, gbestSolution);
        break;
      default:
        throw new IllegalStateException("Unknown operation level: " + oLReal);
    }
  }

  /**
   * Fill the top-k individuals by fitness in-place.
   */
  private void fillTopKByFitnessInPlace(List<boolean[]> mask, List<Double> popFit,
    int nPop, int numToSelect) {
    if (sortBuf == null || sortBuf.length < nPop) {
      sortBuf = new Integer[nPop];
    }
    int[] rank = state.getPopFit() == popFit
      ? state.getCachedFitnessRankDesc()
      : null;
    if (rank != null && rank.length >= nPop) {
      for (int i = 0; i < numToSelect; i++) Arrays.fill(mask.get(rank[i]), true);
      return;
    }
    for (int i = 0; i < nPop; i++) sortBuf[i] = i;
    Arrays.sort(sortBuf, 0, nPop, (a, b) -> {
      int cmp = Double.compare(popFit.get(b), popFit.get(a));
      return cmp != 0 ? cmp : Integer.compare(a, b);
    });
    for (int i = 0; i < numToSelect; i++) Arrays.fill(mask.get(sortBuf[i]), true);
  }

  private void fillMixedTopRandomInPlace(List<boolean[]> mask, List<Double> popFit,
    int nPop, int numToSelect) {
    int topCount = Math.max(1, (int) Math.round(numToSelect * getMixedTopRatio()));
    topCount = Math.min(numToSelect, topCount);
    fillTopKByFitnessInPlace(mask, popFit, nPop, topCount);
    int randomCount = numToSelect - topCount;
    if (randomCount <= 0) {
      return;
    }
    Random random = rand;
    int selected = 0;
    int guard = 0;
    while (selected < randomCount && guard < nPop * 4) {
      guard++;
      int index = random.nextInt(nPop);
      if (!mask.get(index)[0]) {
        Arrays.fill(mask.get(index), true);
        selected++;
      }
    }
    for (int i = 0; selected < randomCount && i < nPop; i++) {
      if (!mask.get(i)[0]) {
        Arrays.fill(mask.get(i), true);
        selected++;
      }
    }
  }

  private void fillRandomKInPlace(List<boolean[]> mask, int nPop, int numToSelect) {
    if (sortBuf == null || sortBuf.length < nPop) {
      sortBuf = new Integer[nPop];
    }
    for (int i = 0; i < nPop; i++) sortBuf[i] = i;
    Random random = rand;
    for (int i = 0; i < numToSelect; i++) {
      int swapIndex = i + random.nextInt(nPop - i);
      Integer temp = sortBuf[i];
      sortBuf[i] = sortBuf[swapIndex];
      sortBuf[swapIndex] = temp;
      Arrays.fill(mask.get(sortBuf[i]), true);
    }
  }

  /**
   * Find the current historical-best row if it still exists in the population.
   */
  private int findHistoricalBestIndex(List<double[]> pop, double[] gbestSolution, List<Double> popFit) {
    if (gbestSolution != null) {
      for (int i = 0; i < pop.size(); i++) {
        if (Arrays.equals(pop.get(i), gbestSolution)) {
          return i;
        }
      }
    }

    return state.getPopFit() == popFit
      ? state.getCachedBestIndex()
      : findBestIndex(popFit);
  }

  private int findBestIndex(List<Double> popFit) {
    int bestIndex = 0;
    for (int i = 1; i < popFit.size(); i++) {
      if (popFit.get(i) > popFit.get(bestIndex)) {
        bestIndex = i;
      }
    }
    return bestIndex;
  }

  /**
   * Find the current worst-fitness row in the population.
   */
  private int findCurrentWorstIndex(List<Double> popFit) {
    int worstIndex = 0;
    for (int i = 1; i < popFit.size(); i++) {
      if (popFit.get(i) < popFit.get(worstIndex)) {
        worstIndex = i;
      }
    }
    return worstIndex;
  }

  /**
   * Resolve how many individuals should be updated for fixed-size subset policies.
   */
  private int resolveFixedSubsetUpdateCount(int nPop) {
    if (nPop <= 0) {
      return 0;
    }
    int count = (int) Math.ceil(AlgorithmConstants.UPDATE_POLICY_SUBSET_RATIO * nPop);
    return Math.max(1, Math.min(nPop, count));
  }

  /**
   * Resolve how many individuals should be updated for random-decay policy.
   */
  private int resolveDecayUpdateCount(int nPop) {
    if (nPop <= 0) {
      return 0;
    }

    double progress = maxItera <= 0
      ? 1.0
      : Math.min(1.0, Math.max(0.0, (double) state.getItera() / maxItera));
    double ratioStart = AlgorithmConstants.UPDATE_POLICY_DECAY_START_RATIO;
    double ratioEnd = Math.max(1.0 / nPop, ratioStart * AlgorithmConstants.UPDATE_POLICY_DECAY_END_FACTOR);
    double ratio = ratioStart - (ratioStart - ratioEnd) * progress;
    int count = (int) Math.ceil(ratio * nPop);
    return Math.max(1, Math.min(nPop, count));
  }

  /**
   * Fill an above-mean fitness selection in-place, falling back to historical best when empty.
   */
  private void fillAboveMeanInPlace(List<boolean[]> mask, List<Double> popFit,
    int nPop, List<double[]> pop, double[] gbestSolution) {
    double sum = 0.0;
    for (int i = 0; i < nPop; i++) {
      sum += popFit.get(i);
    }
    double mean = sum / nPop;
    boolean anySelected = false;
    for (int i = 0; i < nPop; i++) {
      if (popFit.get(i) > mean) {
        Arrays.fill(mask.get(i), true);
        anySelected = true;
      }
    }
    if (!anySelected) {
      Arrays.fill(mask.get(findHistoricalBestIndex(pop, gbestSolution, popFit)), true);
    }
  }

  /**
   * Fill subset selection for the G2/G3 update-policy menu.
   */
  private void fillIndividualsByUpdateRatioInPlace(List<boolean[]> mask, int updateRatioCode,
    List<Double> popFit, int nPop, List<double[]> pop, double[] gbestSolution) {
    int normalizedCode = AlgorithmConstants.normalizeUpdatePolicyCode(updateRatioCode);
    switch (normalizedCode) {
      case AlgorithmConstants.UPDATE_RATIO_CODE_TOP_SUBSET:
        fillTopKByFitnessInPlace(mask, popFit, nPop, resolveFixedSubsetUpdateCount(nPop));
        return;
      case AlgorithmConstants.UPDATE_RATIO_CODE_MIXED_SUBSET:
        fillMixedTopRandomInPlace(mask, popFit, nPop, resolveFixedSubsetUpdateCount(nPop));
        return;
      case AlgorithmConstants.UPDATE_RATIO_CODE_RANDOM_DECAY:
        fillRandomKInPlace(mask, nPop, resolveDecayUpdateCount(nPop));
        return;
      case AlgorithmConstants.UPDATE_RATIO_CODE_ABOVE_MEAN:
        fillAboveMeanInPlace(mask, popFit, nPop, pop, gbestSolution);
        return;
      case AlgorithmConstants.UPDATE_RATIO_CODE_LOCAL_SEARCH:
        Arrays.fill(mask.get(findHistoricalBestIndex(pop, gbestSolution, popFit)), true);
        return;
      case AlgorithmConstants.UPDATE_RATIO_CODE_WORST_REPAIR:
        Arrays.fill(mask.get(findCurrentWorstIndex(popFit)), true);
        return;
      default:
        throw new IllegalArgumentException("Invalid updateRatioCode: " + updateRatioCode);
    }
  }

  /**
   * Fill mask for same-level serial mode in-place.
   */
  private void fillMaskHomoSerialInPlace(List<boolean[]> mask, List<Double> popFit,
    int nPop, List<double[]> pop, double[] gbestSolution) {
    clearMask(mask);
    int oLReal1 = AlgorithmConstants.getUpdateRatioOperationLevel(oL[1]);

    switch (oLReal1) {
      case AlgorithmConstants.OPERATION_LEVEL_POPULATION:
        for (boolean[] row : mask) Arrays.fill(row, true);
        break;
      case AlgorithmConstants.OPERATION_LEVEL_INDIVIDUAL:
        fillIndividualsByUpdateRatioInPlace(mask, oLRand, popFit, nPop, pop, gbestSolution);
        break;
      default:
        throw new IllegalStateException("Unknown operation level: " + oLReal1);
    }
  }

  /**
   * Fill mask for same-level parallel mode in-place.
   */
  private void fillMaskHomoParallelInPlace(List<boolean[]> mask, List<Double> popFit,
    int nPop, List<double[]> pop, double[] gbestSolution) {
    fillMaskHomoSerialInPlace(mask, popFit, nPop, pop, gbestSolution);
  }

  /**
   * Compute indMask, resUnitMask, and count from unitMask in a single pass.
   * Avoids separate calls to anyTruePerRow(), negate(), and countTrue().
   *
   * @return count of individuals to be updated
   */
  private int computeDerivedMasksInPlace(List<boolean[]> unitMask,
    List<Boolean> indMask,
    List<boolean[]> resUnitMask) {
    int count = 0;
    int nPop = unitMask.size();
    for (int i = 0; i < nPop; i++) {
      boolean[] src = unitMask.get(i);
      boolean[] dst = resUnitMask.get(i);
      boolean hasTrue = false;
      for (int j = 0; j < src.length; j++) {
        dst[j] = !src[j];
        if (src[j]) hasTrue = true;
      }
      indMask.set(i, hasTrue);
      if (hasTrue) count++;
    }
    return count;
  }

  /**
   * Determine the hybrid level of OA1 (in-place, allocation-free).
   */
  public int determineHybridLevel1() {
    int[] oLReal = state.getOLReal();
    List<Double> popFit = state.getPopFit();
    int nPop = state.getNPop();
    List<double[]> pop = state.getPop();
    double[] gbestSolution = state.getGbestSolution();

    List<boolean[]> unitMask1 = state.getUnitMask1();
    ensureMaskCapacity(unitMask1, nPop);
    List<boolean[]> resUnitMask1 = state.getResUnitMask1();
    ensureMaskCapacity(resUnitMask1, nPop);
    List<Boolean> indMask1 = state.getIndMask1();
    ensureIndMaskCapacity(indMask1, nPop);

    if (oLReal[INDEX_OA1] != oLReal[INDEX_OA2]) {
      fillMaskCrossLevelInPlace(unitMask1, oL[INDEX_OA1], pop, gbestSolution, popFit, nPop);
    } else {
      fillMaskHomoSerialInPlace(unitMask1, popFit, nPop, pop, gbestSolution);
    }

    // Validate + compute derived masks in single pass
    validateUnitMask(unitMask1, "OA1", oLReal, INDEX_OA1);
    return computeDerivedMasksInPlace(unitMask1, indMask1, resUnitMask1);
  }

  /**
   * Determine the hybrid level of OA2 (serial mode, in-place).
   */
  public int determineHybridLevel2() {
    int[] oLReal = state.getOLReal();
    List<Double> popFit = state.getPopFit();
    int nPop = state.getNPop();
    List<double[]> pop = state.getPop();
    double[] gbestSolution = state.getGbestSolution();

    List<boolean[]> unitMask2 = state.getUnitMask2();
    ensureMaskCapacity(unitMask2, nPop);
    List<boolean[]> resUnitMask2 = state.getResUnitMask2();
    ensureMaskCapacity(resUnitMask2, nPop);
    List<Boolean> indMask2 = state.getIndMask2();
    ensureIndMaskCapacity(indMask2, nPop);

    if (oLReal[INDEX_OA1] != oLReal[INDEX_OA2]) {
      // Cross-level hybrid
      fillMaskCrossLevelInPlace(unitMask2, oL[INDEX_OA2], pop, gbestSolution, popFit, nPop);
    } else {
      // Same-level hybrid: re-calculate unitMask1 then derive unitMask2
      List<boolean[]> unitMask1 = state.getUnitMask1();
      ensureMaskCapacity(unitMask1, nPop);
      fillMaskHomoSerialInPlace(unitMask1, popFit, nPop, pop, gbestSolution);
      // Compute resUnitMask1 in-place (needed for complement)
      List<boolean[]> resUnitMask1 = state.getResUnitMask1();
      ensureMaskCapacity(resUnitMask1, nPop);
      List<Boolean> indMask1 = state.getIndMask1();
      ensureIndMaskCapacity(indMask1, nPop);
      computeDerivedMasksInPlace(unitMask1, indMask1, resUnitMask1);

      int oLReal2 = oLReal[INDEX_OA2];
      if (oLReal2 == AlgorithmConstants.OPERATION_LEVEL_POPULATION) {
        // OA2 reuses OA1's selection - copy in-place
        copyMask(unitMask1, unitMask2);
      } else if (oLReal2 == AlgorithmConstants.OPERATION_LEVEL_INDIVIDUAL) {
        // OA2 uses complement of OA1's selection
        if (!hasAnyTrueInMask(resUnitMask1) && nPop > 0) {
          // Fallback: OA1 selected everything, randomly select one for OA2
          clearMask(unitMask2);
          Arrays.fill(unitMask2.get(rand.nextInt(nPop)), true);
        } else {
          copyMask(resUnitMask1, unitMask2);
        }
      }
    }

    validateUnitMask(unitMask2, "OA2_Serial", oLReal, INDEX_OA2);
    return computeDerivedMasksInPlace(unitMask2, indMask2, resUnitMask2);
  }

  /**
   * Determine the hybrid level of OA2 (parallel mode, in-place).
   */
  public int determineHybridLevel2ForParallel() {
    int[] oLReal = state.getOLReal();
    List<Double> popFit = state.getPopFit();
    int nPop = state.getNPop();
    List<double[]> pop = state.getPop();
    double[] gbestSolution = state.getGbestSolution();

    List<boolean[]> unitMask2 = state.getUnitMask2();
    ensureMaskCapacity(unitMask2, nPop);
    List<boolean[]> resUnitMask2 = state.getResUnitMask2();
    ensureMaskCapacity(resUnitMask2, nPop);
    List<Boolean> indMask2 = state.getIndMask2();
    ensureIndMaskCapacity(indMask2, nPop);

    if (oLReal[INDEX_OA1] != oLReal[INDEX_OA2]) {
      fillMaskCrossLevelInPlace(unitMask2, oL[INDEX_OA2], pop, gbestSolution, popFit, nPop);
    } else {
      // Same-level: re-calculate unitMask1 using parallel variant
      List<boolean[]> unitMask1 = state.getUnitMask1();
      ensureMaskCapacity(unitMask1, nPop);
      fillMaskHomoParallelInPlace(unitMask1, popFit, nPop, pop, gbestSolution);
      // Compute resUnitMask1 only (for complement check), without modifying indMask1
      List<boolean[]> resUnitMask1 = state.getResUnitMask1();
      ensureMaskCapacity(resUnitMask1, nPop);
      computeResUnitMaskOnly(unitMask1, resUnitMask1);

      int oLReal2 = oLReal[INDEX_OA2];
      if (oLReal2 == AlgorithmConstants.OPERATION_LEVEL_POPULATION
        || oLReal2 == AlgorithmConstants.OPERATION_LEVEL_INDIVIDUAL) {
        copyMask(unitMask1, unitMask2);
      }
    }

    validateUnitMask(unitMask2, "OA2_Parallel", oLReal, INDEX_OA2);
    return computeDerivedMasksInPlace(unitMask2, indMask2, resUnitMask2);
  }

  /**
   * Validate unitMask to ensure at least one cell is selected.
   * If all cells are false, throw detailed error with calculation flow information.
   */
  private void validateUnitMask(List<boolean[]> unitMask, String oaName, int[] oLReal, int oLIndex) {
    if (!hasAnyTrueInMask(unitMask)) {
      int nPop = state.getNPop();
      String hybridMode = (oLReal[INDEX_OA1] != oLReal[INDEX_OA2]) ? "Cross-level" : "Same-level";
      String oOMode;
      switch (state.getOOReal()) {
        case AlgorithmConstants.OPERATION_ORDER_REAL_SERIAL:
          oOMode = "Serial";
          break;
        case AlgorithmConstants.OPERATION_ORDER_REAL_PARALLEL:
          oOMode = "Parallel";
          break;
        case AlgorithmConstants.OPERATION_ORDER_REAL_ADAPTIVE:
          oOMode = "Adaptive";
          break;
        default:
          oOMode = "Unknown";
          break;
      }

      throw new IllegalStateException(
        "UnitMask validation failed: All cells are false after generation.\n"
        + "  Context: " + oaName + "\n"
        + "  Hybrid Mode: " + hybridMode + "\n"
        + "  Operation Order: oO=" + oO + ", oOReal=" + state.getOOReal() + " (" + oOMode + ")\n"
        + "  Operation Level: oL[" + oLIndex + "]=" + oL[oLIndex] + ", oLReal[" + oLIndex + "]=" + oLReal[oLIndex] + ", oLRand=" + oLRand + "\n"
        + "  Population: nPop=" + nPop + ", D=" + D + "\n"
        + "  Iteration: itera=" + state.getItera() + ", gen=" + state.getGen() + ", hybLevel=" + state.getHybLevel() + "\n"
        + "  This indicates a bug in the current update-ratio mask selection mechanism.");
    }
  }

  /**
   * Check if the mask has any true value.
   * @param mask the mask to check
   * @return true if any cell is true, false otherwise
   */
  private boolean hasAnyTrueInMask(List<boolean[]> mask) {
    for (boolean[] row : mask) {
      for (boolean val : row) {
        if (val) {
          return true;
        }
      }
    }
    return false;
  }
}
