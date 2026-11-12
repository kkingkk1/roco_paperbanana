package method.rkhma.legacy.operator.mutation;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import method.rkhma.legacy.common.BoundaryHandler;

/**
 * DE/current-to-rand/1 mutation operator.
 *
 * <p>This operator implements the mutation formula:
 * <pre>
 * v_i = x_i + F1 * (x_r1 - x_i) + F2 * (x_r2 - x_r3)
 * </pre>
 *
 * <p>This is a utility class and cannot be instantiated.
 */
public final class MutationDECurrentToRand {

  /**
   * Private constructor to prevent instantiation of this utility class.
   *
   * @throws AssertionError always
   */
  private MutationDECurrentToRand() {
    throw new AssertionError("Utility class should not be instantiated");
  }

  /**
   * Perform DE/current-to-rand/1 mutation using indexed selection (high-performance version).
   *
   * <p><strong>This is the recommended high-performance method.</strong> It uses indices instead
   * of searching for array equality, eliminating O(N×M×D) overhead.
   *
   * <p>For each target index in selectIndices, generates a mutant vector using:
   * <pre>
   * v_i = x_i + F1 * (x_r1 - x_i) + F2 * (x_r2 - x_r3)
   * </pre>
   * where x_r1, x_r2, x_r3 are randomly selected from pop (distinct from each other
   * and from x_i).
   *
   * <p>Mutated values that exceed bounds are handled using boundary repair.
   *
   * <p><strong>Performance advantage over List-based version:</strong> Eliminates expensive
   * array equality search (O(N×M×D) → O(M)), resulting in massive speedup for large populations.
   *
   * @param pop Full parent population (must not be null or empty, size >= 4)
   * @param selectIndices Indices of target individuals to mutate (must be valid indices into pop)
   * @param F1 Scaling factor towards random vector (typically in [0, 2])
   * @param F2 Scaling factor for differential vector (typically in [0, 2])
   * @param upperBound Upper bound for all dimensions (must be greater than lowerBound)
   * @param lowerBound Lower bound for all dimensions (must be less than upperBound)
   * @param customRand Custom random number generator (must not be null)
   * @return Mutated population with same size as selectIndices and boundary handling applied
   * @throws IllegalArgumentException If input parameters are invalid
   */
  public static List<double[]> mutateIndexed(List<double[]> pop,
    int[] selectIndices,
    double F1,
    double F2,
    double upperBound,
    double lowerBound,
    Random customRand) {
    return mutateIndexed(pop, selectIndices, F1, F2, upperBound, lowerBound,
      customRand, BoundaryHandler.BoundaryMode.RANDOM);
  }

  public static List<double[]> mutateIndexed(List<double[]> pop,
    int[] selectIndices,
    double F1,
    double F2,
    double upperBound,
    double lowerBound,
    Random customRand,
    BoundaryHandler.BoundaryMode boundaryMode) {

    // Validate input parameters
    validateParametersIndexed(pop, selectIndices, F1, F2, upperBound, lowerBound, customRand);

    int nPop = pop.size();
    int nSelect = selectIndices.length;
    int D = pop.get(0).length;

    List<double[]> mutantPop = new ArrayList<>(nSelect);

    // Generate a mutant vector for each target index
    for (int i = 0; i < nSelect; i++) {
      int targetIndex = selectIndices[i];
      double[] current = pop.get(targetIndex);

      // Enforce DE invariant: r1/r2/r3 must be distinct and must not equal the target individual's index
      int r1 = DEUtils.selectRandomIndex(nPop, targetIndex, -1, -1, -1, -1, -1, customRand);
      int r2 = DEUtils.selectRandomIndex(nPop, targetIndex, r1, -1, -1, -1, -1, customRand);
      int r3 = DEUtils.selectRandomIndex(nPop, targetIndex, r1, r2, -1, -1, -1, customRand);

      double[] mutant = new double[D];

      // Apply DE/current-to-rand/1 mutation for each dimension
      for (int d = 0; d < D; d++) {
        double currentX = current[d];

        double mutatedValue = currentX
        + F1 * (pop.get(r1)[d] - currentX)
        + F2 * (pop.get(r2)[d] - pop.get(r3)[d]);

        mutant[d] = BoundaryHandler.repairDE(
          mutatedValue, currentX, lowerBound, upperBound, customRand, boundaryMode);
      }

      mutantPop.add(mutant);
    }

    return mutantPop;
  }

  /**
   * Validates input parameters for the indexed mutate method.
   *
   * @param pop Full parent population
   * @param selectIndices Indices of target individuals
   * @param F1 First scaling factor
   * @param F2 Second scaling factor
   * @param upperBound Upper bound
   * @param lowerBound Lower bound
   * @param customRand Random number generator
   * @throws IllegalArgumentException If any parameter is invalid
   */
  private static void validateParametersIndexed(List<double[]> pop,
    int[] selectIndices,
    double F1,
    double F2,
    double upperBound,
    double lowerBound,
    Random customRand) {
    if (pop == null) {
      throw new IllegalArgumentException("Population (pop) cannot be null");
    }
    if (pop.isEmpty()) {
      throw new IllegalArgumentException("Population (pop) cannot be empty");
    }
    if (selectIndices == null) {
      throw new IllegalArgumentException("Select indices (selectIndices) cannot be null");
    }
    if (selectIndices.length == 0) {
      throw new IllegalArgumentException("Select indices (selectIndices) cannot be empty");
    }
    if (customRand == null) {
      throw new IllegalArgumentException("Random number generator (customRand) cannot be null");
    }

    // Validate F parameters
    if (Double.isNaN(F1) || Double.isInfinite(F1)) {
      throw new IllegalArgumentException("F1 must be a valid finite number, got: " + F1);
    }
    if (Double.isNaN(F2) || Double.isInfinite(F2)) {
      throw new IllegalArgumentException("F2 must be a valid finite number, got: " + F2);
    }

    // Validate bounds
    if (Double.isNaN(lowerBound) || Double.isInfinite(lowerBound)) {
      throw new IllegalArgumentException("lowerBound must be a valid finite number, got: " + lowerBound);
    }
    if (Double.isNaN(upperBound) || Double.isInfinite(upperBound)) {
      throw new IllegalArgumentException("upperBound must be a valid finite number, got: " + upperBound);
    }
    if (lowerBound >= upperBound) {
      throw new IllegalArgumentException(
        String.format("lowerBound must be less than upperBound, got lowerBound=%.4f, upperBound=%.4f",
        lowerBound, upperBound));
    }

    // Validate population size: need at least 4 individuals (current + 3 random)
    if (pop.size() < 4) {
      throw new IllegalArgumentException(
        "Population size must be at least 4 for DE/current-to-rand/1, got: " + pop.size());
    }

    // Validate dimension consistency
    int dimension = pop.get(0).length;
    if (dimension == 0) {
      throw new IllegalArgumentException("Population individuals cannot have zero dimensions");
    }

    for (int i = 0; i < pop.size(); i++) {
      if (pop.get(i) == null) {
        throw new IllegalArgumentException("Population contains null individual at index: " + i);
      }
      if (pop.get(i).length != dimension) {
        throw new IllegalArgumentException(
          String.format("Population has inconsistent dimensions: expected %d, got %d at index %d",
          dimension, pop.get(i).length, i));
      }
    }

    // Validate all indices are within valid range
    int popSize = pop.size();
    for (int i = 0; i < selectIndices.length; i++) {
      int idx = selectIndices[i];
      if (idx < 0 || idx >= popSize) {
        throw new IllegalArgumentException(
          String.format("Invalid index at selectIndices[%d]: %d (must be in [0, %d))",
          i, idx, popSize));
      }
    }
  }
}
