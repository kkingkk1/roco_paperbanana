package method.rkhma.legacy.utils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Fitness related utilities.
 * Provides methods for sorting and processing fitness values.
 */
public class FitnessUtils {

  /**
   * Private constructor to prevent instantiation.
   */
  private FitnessUtils() {
    throw new AssertionError("Utility class should not be instantiated");
  }

  /**
   * Index-fitness pair that stores an index and its corresponding fitness value.
   */
  public static class IndexFitnessPair {
    private final int index;
    private final double fitness;

    /**
     * Creates a new index-fitness pair.
     *
     * @param index the index
     * @param fitness the fitness value
     */
    public IndexFitnessPair(int index, double fitness) {
      this.index = index;
      this.fitness = fitness;
    }

    /**
     * Gets the index.
     *
     * @return the index
     */
    public int getIndex() {
      return index;
    }

    /**
     * Gets the fitness value.
     *
     * @return the fitness value
     */
    public double getFitness() {
      return fitness;
    }

    @Override
    public String toString() {
      return "IndexFitnessPair{index=" + index + ", fitness=" + fitness + "}";
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      IndexFitnessPair that = (IndexFitnessPair) o;
      return index == that.index && Double.compare(that.fitness, fitness) == 0;
    }

    @Override
    public int hashCode() {
      return Objects.hash(index, fitness);
    }
  }

  /**
   * Sorts fitness values in descending order while maintaining their original indices.
   *
   * @param fitness the list of fitness values to sort
   * @return a list of index-fitness pairs sorted by fitness in descending order
   * @throws IllegalArgumentException if fitness is null, contains null elements, or contains invalid values (NaN/Infinity)
   */
  public static List<IndexFitnessPair> sortIndexFitnessDesc(List<Double> fitness) {
    if (fitness == null) {
      throw new IllegalArgumentException("Fitness list cannot be null");
    }

    int n = fitness.size();
    List<IndexFitnessPair> indexedFitness = new ArrayList<>(n);

    for (int i = 0; i < n; i++) {
      Double f = fitness.get(i);
      if (f == null) {
        throw new IllegalArgumentException("Fitness value at index " + i + " is null");
      }
      if (Double.isNaN(f) || Double.isInfinite(f)) {
        throw new IllegalArgumentException("Invalid fitness value at index " + i + ": " + f);
      }
      indexedFitness.add(new IndexFitnessPair(i, f));
    }

    indexedFitness.sort(Comparator.comparingDouble(IndexFitnessPair::getFitness).reversed());
    return indexedFitness;
  }

  /**
   * Sorts fitness values in ascending order while maintaining their original indices.
   *
   * @param fitness the list of fitness values to sort
   * @return a list of index-fitness pairs sorted by fitness in ascending order
   * @throws IllegalArgumentException if fitness is null, contains null elements, or contains invalid values (NaN/Infinity)
   */
  public static List<IndexFitnessPair> sortIndexFitnessAsc(List<Double> fitness) {
    if (fitness == null) {
      throw new IllegalArgumentException("Fitness list cannot be null");
    }

    int n = fitness.size();
    List<IndexFitnessPair> indexedFitness = new ArrayList<>(n);

    for (int i = 0; i < n; i++) {
      Double f = fitness.get(i);
      if (f == null) {
        throw new IllegalArgumentException("Fitness value at index " + i + " is null");
      }
      if (Double.isNaN(f) || Double.isInfinite(f)) {
        throw new IllegalArgumentException("Invalid fitness value at index " + i + ": " + f);
      }
      indexedFitness.add(new IndexFitnessPair(i, f));
    }

    indexedFitness.sort(Comparator.comparingDouble(IndexFitnessPair::getFitness));
    return indexedFitness;
  }

  /**
   * Gets the top K fitness values in descending order.
   * More efficient than full sorting when k is much smaller than the list size.
   *
   * @param fitness the list of fitness values
   * @param k the number of top elements to retrieve
   * @return a list of the top k index-fitness pairs sorted by fitness in descending order
   * @throws IllegalArgumentException if fitness is null, k is invalid, or fitness contains invalid values
   */
  public static List<IndexFitnessPair> getTopK(List<Double> fitness, int k) {
    if (fitness == null) {
      throw new IllegalArgumentException("Fitness list cannot be null");
    }
    if (k < 0 || k > fitness.size()) {
      throw new IllegalArgumentException("Invalid k value: " + k + " (fitness size: " + fitness.size() + ")");
    }

    List<IndexFitnessPair> sorted = sortIndexFitnessDesc(fitness);
    return sorted.subList(0, k);
  }

  /**
   * Finds the index with the maximum fitness value.
   *
   * @param fitness the list of fitness values
   * @return the index of the maximum fitness value
   * @throws IllegalArgumentException if fitness is null, empty, or contains invalid values
   */
  public static int getMaxIndex(List<Double> fitness) {
    if (fitness == null || fitness.isEmpty()) {
      throw new IllegalArgumentException("Fitness list cannot be null or empty");
    }

    int maxIndex = 0;
    double maxFitness = Double.NEGATIVE_INFINITY;

    for (int i = 0; i < fitness.size(); i++) {
      Double f = fitness.get(i);
      if (f == null) {
        throw new IllegalArgumentException("Fitness value at index " + i + " is null");
      }
      if (Double.isNaN(f)) {
        throw new IllegalArgumentException("Invalid fitness value at index " + i + ": NaN");
      }
      if (f > maxFitness) {
        maxFitness = f;
        maxIndex = i;
      }
    }

    return maxIndex;
  }

  /**
   * Finds the index with the minimum fitness value.
   *
   * @param fitness the list of fitness values
   * @return the index of the minimum fitness value
   * @throws IllegalArgumentException if fitness is null, empty, or contains invalid values
   */
  public static int getMinIndex(List<Double> fitness) {
    if (fitness == null || fitness.isEmpty()) {
      throw new IllegalArgumentException("Fitness list cannot be null or empty");
    }

    int minIndex = 0;
    double minFitness = Double.POSITIVE_INFINITY;

    for (int i = 0; i < fitness.size(); i++) {
      Double f = fitness.get(i);
      if (f == null) {
        throw new IllegalArgumentException("Fitness value at index " + i + " is null");
      }
      if (Double.isNaN(f)) {
        throw new IllegalArgumentException("Invalid fitness value at index " + i + ": NaN");
      }
      if (f < minFitness) {
        minFitness = f;
        minIndex = i;
      }
    }

    return minIndex;
  }

  /**
   * Binary search on cumulative probability list for roulette-wheel selection.
   * This method uses binary search to efficiently find the appropriate index in a cumulative
   * probability distribution, which is commonly used in evolutionary algorithms for selection.
   *
   * <p>The cumulative probability list should be sorted in ascending order with the last
   * element being 1.0 (or very close to 1.0 due to floating-point precision).</p>
   *
   * <p>Example usage:
   * <pre>
   * // Cumulative probabilities: [0.2, 0.5, 0.8, 1.0]
   * // Random value: 0.6
   * // Result: index 2 (because 0.5 < 0.6 <= 0.8)
   * int selected = FitnessUtils.binarySearchRoulette(cumulativeProb, 0.6);
   * </pre>
   *
   * @param cumulativeProb the cumulative probability list (must be sorted in ascending order)
   * @param randomValue the random value in [0, 1] used for selection
   * @return the selected index from 0 to cumulativeProb.size()-1
   * @throws IllegalArgumentException if cumulativeProb is null or empty, or if randomValue is not in [0, 1]
   */
  public static int binarySearchRoulette(List<Double> cumulativeProb, double randomValue) {
    if (cumulativeProb == null || cumulativeProb.isEmpty()) {
      throw new IllegalArgumentException("Cumulative probability list cannot be null or empty");
    }
    if (randomValue < 0.0 || randomValue > 1.0) {
      throw new IllegalArgumentException("Random value must be in [0, 1], got: " + randomValue);
    }

    int n = cumulativeProb.size();

    // Optimization: handle common case where random value is very small
    if (randomValue <= cumulativeProb.get(0)) {
      return 0;
    }

    // Binary search
    int left = 0;
    int right = n - 1;

    while (left < right) {
      int mid = left + (right - left) / 2;
      if (cumulativeProb.get(mid) < randomValue) {
        left = mid + 1;
      } else {
        right = mid;
      }
    }

    return left;
  }
}
