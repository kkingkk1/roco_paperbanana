package method.rkhma.legacy.operator.mutation;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

public final class DEUtils {

  /**
   * Generate an array of all indices from 0 to n-1.
   * Useful for selecting entire population: allIndices(pop.size())
   *
   * @param n Size of the population
   * @return Array [0, 1, 2, ..., n-1]
   * @throws IllegalArgumentException If n is negative
   */
  public static int[] allIndices(int n) {
    if (n < 0) {
      throw new IllegalArgumentException("Population size must be non-negative, got: " + n);
    }
    int[] indices = new int[n];
    for (int i = 0; i < n; i++) {
      indices[i] = i;
    }
    return indices;
  }

  /**
   * Generate a range of indices [start, end).
   *
   * @param start Start index (inclusive)
   * @param end End index (exclusive)
   * @return Array [start, start+1, ..., end-1]
   * @throws IllegalArgumentException If start > end or start < 0
   */
  public static int[] rangeIndices(int start, int end) {
    if (start < 0) {
      throw new IllegalArgumentException("Start index must be non-negative, got: " + start);
    }
    if (start > end) {
      throw new IllegalArgumentException(
        String.format("Start index must be <= end index, got start=%d, end=%d", start, end));
    }
    int[] indices = new int[end - start];
    for (int i = 0; i < indices.length; i++) {
      indices[i] = start + i;
    }
    return indices;
  }

  public static int selectRandomIndex(int nPop, int exclude1, int exclude2, int exclude3, int exclude4, int exclude5, int exclude6, Random customRand) {
    // Count number of valid exclusions
    int numExclusions = 0;
    if (exclude1 >= 0 && exclude1 < nPop) numExclusions++;
    if (exclude2 >= 0 && exclude2 < nPop) numExclusions++;
    if (exclude3 >= 0 && exclude3 < nPop) numExclusions++;
    if (exclude4 >= 0 && exclude4 < nPop) numExclusions++;
    if (exclude5 >= 0 && exclude5 < nPop) numExclusions++;
    if (exclude6 >= 0 && exclude6 < nPop) numExclusions++;

    // Check if there are enough valid indices to select from
    if (nPop - numExclusions <= 0) {
      throw new IllegalArgumentException(
        String.format("Cannot select index: population size (%d) minus exclusions (%d) leaves no valid indices",
        nPop, numExclusions));
    }

    // Performance optimization: switch strategy based on exclusion density
    // When exclusions are sparse (< 30% of population), use rejection sampling (faster for sparse exclusions)
    // When exclusions are dense (>= 30% of population), use candidate set (avoids potential long loops)
    double exclusionRatio = (double) numExclusions / nPop;

    if (exclusionRatio < 0.3) {
      // Rejection sampling: efficient for sparse exclusions
      int index;
      do {
        index = customRand.nextInt(nPop);
      } while (index == exclude1
        || index == exclude2
        || index == exclude3
        || index == exclude4
        || index == exclude5
        || index == exclude6);
      return index;
    } else {
      // Candidate set: efficient for dense exclusions
      return selectRandomIndexFromCandidates(nPop, customRand, exclude1, exclude2, exclude3, exclude4, exclude5, exclude6);
    }
  }

  public static int[] selectKDistinct(int nPop, int exclude, int k, Random customRand) {
    if (customRand == null) {
      throw new IllegalArgumentException("Random number generator cannot be null");
    }
    if (k < 0) {
      throw new IllegalArgumentException("k must be non-negative, got: " + k);
    }
    int validCount = nPop - ((exclude >= 0 && exclude < nPop) ? 1 : 0);
    if (k > validCount) {
      throw new IllegalArgumentException(
        String.format("Cannot select %d distinct indices from nPop=%d excluding %d", k, nPop, exclude));
    }
    int[] pool = new int[validCount];
    int pos = 0;
    for (int i = 0; i < nPop; i++) {
      if (i != exclude) {
        pool[pos++] = i;
      }
    }
    for (int i = 0; i < k; i++) {
      int j = i + customRand.nextInt(validCount - i);
      int tmp = pool[i];
      pool[i] = pool[j];
      pool[j] = tmp;
    }
    return Arrays.copyOf(pool, k);
  }

  /**
   * Select a random index using candidate set method (efficient for many exclusions).
   *
   * <p>This method builds a list of valid candidate indices and randomly selects from them.
   * Time complexity: O(N + E) where N is population size and E is number of exclusions.
   * Space complexity: O(N - E) for the candidate list.
   *
   * <p><strong>Use case:</strong> Efficient when exclusions are dense (many indices to exclude).
   * For sparse exclusions, rejection sampling (do-while loop) is typically faster.
   *
   * @param nPop Population size
   * @param customRand Random number generator
   * @param exclusions Variable-length list of indices to exclude (must be in [0, nPop))
   * @return A randomly selected valid index
   * @throws IllegalArgumentException If all indices are excluded
   */
  private static int selectRandomIndexFromCandidates(int nPop, Random customRand, int... exclusions) {
    // Build a candidate set by filtering out exclusions
    boolean[] isExcluded = new boolean[nPop];
    int numExclusions = 0;

    for (int exclude : exclusions) {
      if (exclude >= 0 && exclude < nPop && !isExcluded[exclude]) {
        isExcluded[exclude] = true;
        numExclusions++;
      }
    }

    int numCandidates = nPop - numExclusions;
    if (numCandidates <= 0) {
      throw new IllegalArgumentException(
        String.format("Cannot select index: all %d indices are excluded", nPop));
    }

    // Build candidate array
    int[] candidates = new int[numCandidates];
    int candidateIndex = 0;
    for (int i = 0; i < nPop; i++) {
      if (!isExcluded[i]) {
        candidates[candidateIndex++] = i;
      }
    }

    // Randomly select from candidates
    return candidates[customRand.nextInt(numCandidates)];
  }

  /**
   * Find the index of a target array in the population by comparing array contents.
   * Uses Arrays.equals() to perform value-based comparison, not reference equality.
   *
   * <p>This method searches for an array with identical values (element-by-element)
   * rather than searching for the same object reference.
   *
   * @param pop Population list (must not be null)
   * @param target Target array to find (must not be null)
   * @return Index of the first matching array, or -1 if not found
   */
  public static int indexOfEqualArray(List<double[]> pop, double[] target) {
    for (int i = 0; i < pop.size(); i++) {
      if (Arrays.equals(pop.get(i), target)) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Find the index of the individual with maximum fitness.
   *
   * @param fitness Fitness list (must be non-null and non-empty)
   * @return Index of the best individual
   * @throws IllegalArgumentException If fitness is null or empty
   */
  public static int findBestIndexMax(List<Double> fitness) {
    if (fitness == null || fitness.isEmpty()) {
      throw new IllegalArgumentException("Fitness list cannot be null or empty");
    }

    int bestIndex = 0;
    double bestFitness = fitness.get(0);

    for (int i = 1; i < fitness.size(); i++) {
      if (fitness.get(i) > bestFitness) {
        bestFitness = fitness.get(i);
        bestIndex = i;
      }
    }

    return bestIndex;
  }

  /**
   * Find the index of the individual with minimum fitness.
   *
   * @param fitness Fitness list (must be non-null and non-empty)
   * @return Index of the best individual
   * @throws IllegalArgumentException If fitness is null or empty
   */
  public static int findBestIndexMin(List<Double> fitness) {
    if (fitness == null || fitness.isEmpty()) {
      throw new IllegalArgumentException("Fitness list cannot be null or empty");
    }

    int bestIndex = 0;
    double bestFitness = fitness.get(0);

    for (int i = 1; i < fitness.size(); i++) {
      if (fitness.get(i) < bestFitness) {
        bestFitness = fitness.get(i);
        bestIndex = i;
      }
    }

    return bestIndex;
  }

  /**
   * Convert a boolean mask to an index array.
   *
   * <p>This method extracts indices where the mask is true, useful for converting
   * selection masks to index-based access patterns.
   *
   * <p><strong>Example:</strong>
   * <pre>
   * List&lt;Boolean&gt; mask = Arrays.asList(false, true, false, true, true);
   * int[] indices = maskToIndices(mask);  // Returns [1, 3, 4]
   * </pre>
   *
   * @param mask Boolean mask (must not be null, can contain nulls which are treated as false)
   * @return Array of indices where mask is true
   * @throws IllegalArgumentException If mask is null
   */
  public static int[] maskToIndices(List<Boolean> mask) {
    if (mask == null) {
      throw new IllegalArgumentException("Mask cannot be null");
    }

    // First pass: count true values
    int count = 0;
    for (Boolean b : mask) {
      if (Boolean.TRUE.equals(b)) {
        count++;
      }
    }

    // Second pass: collect indices
    int[] indices = new int[count];
    int pos = 0;
    for (int i = 0; i < mask.size(); i++) {
      if (Boolean.TRUE.equals(mask.get(i))) {
        indices[pos++] = i;
      }
    }

    return indices;
  }
}
