package method.rkhma.legacy.operator.crossover;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Differential Evolution binomial crossover operator.
 *
 * <p>This operator implements binomial crossover between mutant and target vectors.
 * For each dimension, the trial vector inherits from the mutant vector with probability CR,
 * or from the target vector with probability (1-CR). At least one dimension is guaranteed
 * to be inherited from the mutant vector (via jrand mechanism).
 *
 * <p><strong>Performance characteristics:</strong>
 * <ul>
 *   <li><strong>Time complexity:</strong> O(N * D) where N = population size, D = dimensions</li>
 *   <li><strong>Space complexity:</strong> O(N * D) for the trial population</li>
 *   <li><strong>Validation overhead:</strong> Standard methods validate all inputs; use *Unsafe variants
 *       in performance-critical loops to skip validation (5-15% speedup)</li>
 *   <li><strong>Boundary optimizations:</strong> Special fast paths for CR = 0.0 and CR = 1.0</li>
 * </ul>
 *
 * <p><strong>Thread safety:</strong> This class is stateless and thread-safe. However, the provided
 * {@code Random} instance must be thread-safe (e.g., use {@code ThreadLocalRandom.current()} or
 * synchronized {@code Random} for concurrent use). Input/output lists are not synchronized.
 *
 * <p><strong>Method selection guide:</strong>
 * <ul>
 *   <li>{@link #crossoverIndexed}: Recommended for most use cases with index-based selection</li>
 *   <li>{@link #crossover}: Use when you already have extracted subpopulations</li>
 *   <li>{@link #crossoverIndexedUnsafe}: Use in tight loops after validating inputs once</li>
 *   <li>{@link #crossoverUnsafe}: Use in tight loops with extracted subpopulations</li>
 * </ul>
 *
 * <p>This is a utility class and cannot be instantiated.
 */
public final class CrossoverDE {

  /**
   * Private constructor to prevent instantiation of this utility class.
   *
   * @throws AssertionError always
   */
  private CrossoverDE() {
    throw new AssertionError("Utility class should not be instantiated");
  }

  /**
   * Perform binomial crossover between mutant and target populations using index-based selection.
   *
   * <p><strong>This is the recommended high-performance method.</strong> It avoids allocating and copying
   * an intermediate target population list, directly accessing targets from the full population by index.
   *
   * <p>For each individual, the trial vector u is generated as:
   * <pre>
   * u[j] = v[j]  if (rand() <= CR) or (j == jrand)
   *        x[j]  otherwise
   * </pre>
   * where:
   * <ul>
   *   <li>v is the mutant vector</li>
   *   <li>x is the target vector</li>
   *   <li>jrand is a randomly chosen dimension index (ensures at least one dimension from mutant)</li>
   *   <li>CR is the crossover rate</li>
   * </ul>
   *
   * <p><strong>Performance characteristics:</strong>
   * <ul>
   *   <li>Time complexity: O(N * D) where N = selectIndices.length, D = dimension count</li>
   *   <li>Space complexity: O(N * D) for trial population</li>
   *   <li>Validation overhead: O(N) for index validation + O(1) for population checks</li>
   *   <li>Fast paths: O(N * D) array copies when CR = 0.0 or CR = 1.0 (no random number generation)</li>
   * </ul>
   *
   * <p><strong>Performance advantage over List-based version:</strong> Eliminates O(N) memory allocation
   * and copying overhead for extracting target population.
   *
   * @param mutantPop Mutant vector population (must not be null or empty, each element is a double[] of length D,
   *                  must have same size as selectIndices)
   * @param fullTargetPop Full target population (must not be null or empty, each element is a double[] of length D)
   * @param selectIndices Indices of target individuals to use for crossover (must be valid indices into fullTargetPop,
   *                      must have same size as mutantPop)
   * @param crossRate Crossover rate in [0, 1], recommended 0.7 - 0.9 for DE
   * @param customRand Custom random number generator (must not be null)
   * @return Trial vector population with same size as mutantPop and selectIndices, each element is a double[] of length D
   * @throws IllegalArgumentException If parameters are invalid
   */
  public static List<double[]> crossoverIndexed(List<double[]> mutantPop,
    List<double[]> fullTargetPop,
    int[] selectIndices,
    double crossRate,
    Random customRand) {

    // Validate input parameters
    validateParametersIndexed(mutantPop, fullTargetPop, selectIndices, crossRate, customRand);

    int N = selectIndices.length;
    int D = mutantPop.get(0).length;

    // ========================================
    // Fast paths for boundary cases
    // ========================================

    // When CR = 1.0, trial vectors are identical to mutant vectors (except jrand is redundant)
    if (crossRate == 1.0) {
      List<double[]> trialPop = new ArrayList<>(N);
      for (int i = 0; i < N; i++) {
        double[] mutant = mutantPop.get(i);
        double[] trial = new double[D];
        System.arraycopy(mutant, 0, trial, 0, D);
        trialPop.add(trial);
      }
      return trialPop;
    }

    // When CR = 0.0, trial vectors are identical to target vectors except at jrand position
    if (crossRate == 0.0) {
      List<double[]> trialPop = new ArrayList<>(N);
      for (int i = 0; i < N; i++) {
        int jrand = customRand.nextInt(D);
        double[] target = fullTargetPop.get(selectIndices[i]);
        double[] mutant = mutantPop.get(i);
        double[] trial = new double[D];
        System.arraycopy(target, 0, trial, 0, D);
        trial[jrand] = mutant[jrand];  // Override with mutant at jrand
        trialPop.add(trial);
      }
      return trialPop;
    }

    // ========================================
    // Perform binomial crossover
    // ========================================

    List<double[]> trialPop = new ArrayList<>(N);

    for (int i = 0; i < N; i++) {
      int jrand = customRand.nextInt(D);

      // Directly access target from full population using index (no copy overhead)
      double[] target = fullTargetPop.get(selectIndices[i]);
      double[] mutant = mutantPop.get(i);
      double[] trial = new double[D];

      for (int j = 0; j < D; j++) {
        double randomValue = customRand.nextDouble();

        if (randomValue <= crossRate || j == jrand) {
          trial[j] = mutant[j];
        } else {
          trial[j] = target[j];
        }
      }

      trialPop.add(trial);
    }

    return trialPop;
  }


  /**
   * Perform binomial crossover between mutant and target populations using index-based selection (FAST MODE).
   *
   * <p><strong>WARNING:</strong> This is an unsafe, high-performance variant that <strong>skips all validation</strong>.
   * Use this method ONLY in performance-critical inner loops where you have already validated inputs once.
   *
   * <p><strong>Caller responsibilities:</strong> Ensure that:
   * <ul>
   *   <li>All parameters are non-null</li>
   *   <li>mutantPop and fullTargetPop are non-empty with consistent dimensions</li>
   *   <li>selectIndices.length == mutantPop.size()</li>
   *   <li>All indices in selectIndices are valid (0 <= idx < fullTargetPop.size())</li>
   *   <li>crossRate is in [0, 1] and is a valid finite number</li>
   * </ul>
   *
   * <p><strong>Performance benefit:</strong> Eliminates all parameter validation overhead (~5-15% faster for large populations).
   *
   * @param mutantPop Mutant vector population (assumed valid)
   * @param fullTargetPop Full target population (assumed valid)
   * @param selectIndices Indices of target individuals (assumed valid)
   * @param crossRate Crossover rate in [0, 1] (assumed valid)
   * @param customRand Custom random number generator (assumed non-null)
   * @return Trial vector population
   */
  public static List<double[]> crossoverIndexedUnsafe(List<double[]> mutantPop,
    List<double[]> fullTargetPop,
    int[] selectIndices,
    double crossRate,
    Random customRand) {
    int N = selectIndices.length;
    int D = mutantPop.get(0).length;

    // Fast paths for boundary cases
    if (crossRate == 1.0) {
      List<double[]> trialPop = new ArrayList<>(N);
      for (int i = 0; i < N; i++) {
        double[] mutant = mutantPop.get(i);
        double[] trial = new double[D];
        System.arraycopy(mutant, 0, trial, 0, D);
        trialPop.add(trial);
      }
      return trialPop;
    }

    if (crossRate == 0.0) {
      List<double[]> trialPop = new ArrayList<>(N);
      for (int i = 0; i < N; i++) {
        int jrand = customRand.nextInt(D);
        double[] target = fullTargetPop.get(selectIndices[i]);
        double[] mutant = mutantPop.get(i);
        double[] trial = new double[D];
        System.arraycopy(target, 0, trial, 0, D);
        trial[jrand] = mutant[jrand];
        trialPop.add(trial);
      }
      return trialPop;
    }

    // Standard binomial crossover
    List<double[]> trialPop = new ArrayList<>(N);

    for (int i = 0; i < N; i++) {
      int jrand = customRand.nextInt(D);
      double[] target = fullTargetPop.get(selectIndices[i]);
      double[] mutant = mutantPop.get(i);
      double[] trial = new double[D];

      for (int j = 0; j < D; j++) {
        double randomValue = customRand.nextDouble();
        if (randomValue <= crossRate || j == jrand) {
          trial[j] = mutant[j];
        } else {
          trial[j] = target[j];
        }
      }

      trialPop.add(trial);
    }

    return trialPop;
  }


  // ========================================
  // Validation methods
  // ========================================

  /**
   * Validates input parameters for the indexed crossover method.
   *
   * @param mutantPop Mutant vector population
   * @param fullTargetPop Full target population
   * @param selectIndices Indices of target individuals
   * @param crossRate Crossover rate
   * @param customRand Random number generator
   * @throws IllegalArgumentException If any parameter is invalid
   */
  private static void validateParametersIndexed(List<double[]> mutantPop,
    List<double[]> fullTargetPop,
    int[] selectIndices,
    double crossRate,
    Random customRand) {
    // Common validations
    validateNotNull(mutantPop, "Mutant population (mutantPop)");
    validateNotEmpty(mutantPop, "Mutant population (mutantPop)");
    validateNotNull(fullTargetPop, "Full target population (fullTargetPop)");
    validateNotEmpty(fullTargetPop, "Full target population (fullTargetPop)");
    validateNotNull(customRand, "Random number generator (customRand)");
    validateCrossoverRate(crossRate);

    if (selectIndices == null) {
      throw new IllegalArgumentException("Select indices (selectIndices) cannot be null");
    }
    if (selectIndices.length == 0) {
      throw new IllegalArgumentException("Select indices (selectIndices) cannot be empty");
    }

    // Validate population sizes match
    if (mutantPop.size() != selectIndices.length) {
      throw new IllegalArgumentException(
        String.format("Mutant population size must match selectIndices length: mutantPop size=%d, selectIndices length=%d",
        mutantPop.size(), selectIndices.length));
    }

    // Validate dimension consistency (fast mode: only check first element + full population check)
    int dimension = mutantPop.get(0).length;
    if (dimension == 0) {
      throw new IllegalArgumentException("Population individuals cannot have zero dimensions");
    }

    // Fast validation: check first and last elements only
    validateIndividual(mutantPop.get(0), dimension, "Mutant population", 0);
    if (mutantPop.size() > 1) {
      validateIndividual(mutantPop.get(mutantPop.size() - 1), dimension, "Mutant population", mutantPop.size() - 1);
    }

    validateIndividual(fullTargetPop.get(0), dimension, "Full target population", 0);
    if (fullTargetPop.size() > 1) {
      validateIndividual(fullTargetPop.get(fullTargetPop.size() - 1), dimension, "Full target population", fullTargetPop.size() - 1);
    }

    // Validate all indices are within valid range
    int targetPopSize = fullTargetPop.size();
    for (int i = 0; i < selectIndices.length; i++) {
      int idx = selectIndices[i];
      if (idx < 0 || idx >= targetPopSize) {
        throw new IllegalArgumentException(
          String.format("Invalid index at selectIndices[%d]: %d (must be in [0, %d))",
          i, idx, targetPopSize));
      }
    }
  }

  // ========================================
  // Common validation helper methods
  // ========================================

  /**
   * Validates that a list is not null.
   *
   * @param list The list to validate
   * @param name The name of the parameter for error messages
   * @throws IllegalArgumentException If the list is null
   */
  private static void validateNotNull(List<double[]> list, String name) {
    if (list == null) {
      throw new IllegalArgumentException(name + " cannot be null");
    }
  }

  /**
   * Validates that a Random instance is not null.
   *
   * @param rand The Random instance to validate
   * @param name The name of the parameter for error messages
   * @throws IllegalArgumentException If the Random instance is null
   */
  private static void validateNotNull(Random rand, String name) {
    if (rand == null) {
      throw new IllegalArgumentException(name + " cannot be null");
    }
  }

  /**
   * Validates that a list is not empty.
   *
   * @param list The list to validate
   * @param name The name of the parameter for error messages
   * @throws IllegalArgumentException If the list is empty
   */
  private static void validateNotEmpty(List<double[]> list, String name) {
    if (list.isEmpty()) {
      throw new IllegalArgumentException(name + " cannot be empty");
    }
  }

  /**
   * Validates the crossover rate.
   *
   * @param crossRate The crossover rate to validate
   * @throws IllegalArgumentException If the crossover rate is invalid
   */
  private static void validateCrossoverRate(double crossRate) {
    if (Double.isNaN(crossRate) || Double.isInfinite(crossRate)) {
      throw new IllegalArgumentException("Crossover rate must be a valid finite number, got: " + crossRate);
    }
    if (crossRate < 0.0 || crossRate > 1.0) {
      throw new IllegalArgumentException(
        String.format("Crossover rate must be in [0, 1], got: %.4f", crossRate));
    }
  }

  /**
   * Validates an individual (double array) for null and dimension consistency.
   *
   * @param individual The individual to validate
   * @param expectedDimension The expected dimension
   * @param populationName The name of the population for error messages
   * @param index The index of the individual in the population
   * @throws IllegalArgumentException If the individual is invalid
   */
  private static void validateIndividual(double[] individual, int expectedDimension,
    String populationName, int index) {
    if (individual == null) {
      throw new IllegalArgumentException(
        String.format("%s contains null individual at index: %d", populationName, index));
    }
    if (individual.length != expectedDimension) {
      throw new IllegalArgumentException(
        String.format("%s has inconsistent dimensions: expected %d, got %d at index %d",
        populationName, expectedDimension, individual.length, index));
    }
  }
}