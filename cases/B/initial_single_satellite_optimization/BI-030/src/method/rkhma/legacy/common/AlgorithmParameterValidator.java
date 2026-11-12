package method.rkhma.legacy.common;

import java.util.List;
import java.util.Random;

/**
 * Utility class providing common parameter validation logic for all evolutionary algorithms.
 *
 * <p>This class centralizes validation of common parameters such as population, indices,
 * fitness arrays, and random generators. It reduces code duplication across different
 * algorithm implementations (GA, DE, PSO, ABC, etc.).
 *
 * <p><strong>Usage example:</strong>
 * <pre>
 * // In algorithm implementation
 * private static void validateParameters(...) {
 *     AlgorithmParameterValidator.validatePopulation(pop);
 *     AlgorithmParameterValidator.validateSelectIndices(selectIndices, pop.size());
 *     AlgorithmParameterValidator.validateConfig(config);
 *     AlgorithmParameterValidator.validateRandom(rand);
 *
 *     // Algorithm-specific validation
 *     if (config.specificParam > threshold) { ... }
 * }
 * </pre>
 *
 * @author Algorithm Framework Team
 * @version 1.0
 */
public final class AlgorithmParameterValidator {

  /**
   * Tolerance used for floating-point boundary comparisons.
   */
  private static final double BOUNDARY_TOLERANCE = 1e-9;

  /**
   * Private constructor to prevent instantiation of this utility class.
   *
   * @throws AssertionError always
   */
  private AlgorithmParameterValidator() {
    throw new AssertionError("Utility class should not be instantiated");
  }

  /**
   * Validates that the population is not null, not empty, and all individuals have
   * consistent non-zero dimensions.
   *
   * <p>This method performs the following checks:
   * <ul>
   *   <li>Population is not null</li>
   *   <li>Population is not empty</li>
   *   <li>First individual's dimension is greater than 0</li>
   *   <li>All individuals are non-null</li>
   *   <li>All individuals have the same dimension as the first individual</li>
   * </ul>
   *
   * @param pop Population to validate
   * @throws IllegalArgumentException If validation fails
   */
  public static void validatePopulation(List<double[]> pop) {
    if (pop == null) {
      throw new IllegalArgumentException("Population (pop) cannot be null");
    }
    if (pop.isEmpty()) {
      throw new IllegalArgumentException("Population (pop) cannot be empty");
    }

    // Validate dimension consistency
    int dimension = pop.get(0).length;
    if (dimension == 0) {
      throw new IllegalArgumentException("Population individuals cannot have zero dimensions");
    }

    // Check all individuals in pop have consistent dimensions
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
  }

  /**
   * Validates that selectIndices is not null, not empty, and all indices are within
   * valid range [0, popSize).
   *
   * @param selectIndices Array of indices to validate
   * @param popSize Size of the population (must be positive)
   * @throws IllegalArgumentException If validation fails
   */
  public static void validateSelectIndices(int[] selectIndices, int popSize) {
    if (selectIndices == null) {
      throw new IllegalArgumentException("Select indices (selectIndices) cannot be null");
    }
    if (selectIndices.length == 0) {
      throw new IllegalArgumentException("Select indices (selectIndices) cannot be empty");
    }

    // Validate all indices are within valid range
    for (int i = 0; i < selectIndices.length; i++) {
      int idx = selectIndices[i];
      if (idx < 0 || idx >= popSize) {
        throw new IllegalArgumentException(
          String.format("Invalid index at selectIndices[%d]: %d (must be in [0, %d))",
          i, idx, popSize));
      }
    }
  }

  /**
   * Validates that the fitness array is not null and matches the population size.
   *
   * @param popFit Fitness array to validate
   * @param popSize Expected size of the fitness array
   * @throws IllegalArgumentException If validation fails
   */
  public static void validateFitness(double[] popFit, int popSize) {
    if (popFit == null) {
      throw new IllegalArgumentException("Population fitness (popFit) cannot be null");
    }
    if (popFit.length != popSize) {
      throw new IllegalArgumentException(
        String.format("Population fitness size (%d) must match population size (%d)",
        popFit.length, popSize));
    }
  }

  /**
   * Validates that the fitness list is not null and matches the population size.
   *
   * @param popFit Fitness list to validate
   * @param popSize Expected size of the fitness list
   * @throws IllegalArgumentException If validation fails
   */
  public static void validateFitness(List<Double> popFit, int popSize) {
    if (popFit == null) {
      throw new IllegalArgumentException("Population fitness (popFit) cannot be null");
    }
    if (popFit.size() != popSize) {
      throw new IllegalArgumentException(
        String.format("Population fitness size (%d) must match population size (%d)",
        popFit.size(), popSize));
    }
  }

  /**
   * Validates that the configuration object is not null.
   *
   * <p>Note: This method only checks for null. Algorithm-specific configuration
   * validation should be performed by the configuration class itself using the
   * {@link core.config.ConfigValidatable} interface.
   *
   * @param config Configuration object to validate
   * @throws IllegalArgumentException If config is null
   */
  public static void validateConfig(Object config) {
    if (config == null) {
      throw new IllegalArgumentException("Configuration (config) cannot be null");
    }
  }

  /**
   * Validates that the random number generator is not null.
   *
   * @param rand Random number generator to validate
   * @throws IllegalArgumentException If rand is null
   */
  public static void validateRandom(Random rand) {
    if (rand == null) {
      throw new IllegalArgumentException("Random number generator (rand) cannot be null");
    }
  }

  /**
   * Validates that the generation index is non-negative.
   *
   * @param gen Generation index to validate
   * @throws IllegalArgumentException If gen is negative
   */
  public static void validateGeneration(int gen) {
    if (gen < 0) {
      throw new IllegalArgumentException("Generation index (gen) must be non-negative, got: " + gen);
    }
  }

  /**
   * Validates that the generation index is within the valid range [0, maxGen).
   *
   * @param gen Generation index to validate
   * @param maxGen Maximum generation index (exclusive)
   * @throws IllegalArgumentException If gen is out of range
   */
  public static void validateGeneration(int gen, int maxGen) {
    if (gen < 0) {
      throw new IllegalArgumentException("Generation index (gen) must be non-negative, got: " + gen);
    }
    if (gen >= maxGen) {
      throw new IllegalArgumentException(
        String.format("Generation index (gen) out of bounds: %d >= %d (max generation)",
        gen, maxGen));
    }
  }

  /**
   * Validates that the population size meets the minimum requirement.
   *
   * <p>Different algorithms have different minimum population size requirements:
   * <ul>
   *   <li>DE/rand/1: requires at least 4 individuals (target + 3 random)</li>
   *   <li>PSO: requires at least 2 particles</li>
   *   <li>GA: typically requires at least 2 individuals</li>
   * </ul>
   *
   * @param popSize Actual population size
   * @param minSize Minimum required population size
   * @param algorithmName Name of the algorithm (for error message)
   * @throws IllegalArgumentException If population size is less than minimum
   */
  public static void validateMinPopulationSize(int popSize, int minSize, String algorithmName) {
    if (popSize < minSize) {
      throw new IllegalArgumentException(
        String.format("%s requires at least %d individuals in population, got: %d",
        algorithmName, minSize, popSize));
    }
  }

  /**
   * Validates that a parameter value is within the specified range (inclusive).
   *
   * @param paramName Parameter name (for error message)
   * @param value Value to validate
   * @param min Minimum allowed value (inclusive)
   * @param max Maximum allowed value (inclusive)
   * @throws IllegalArgumentException If value is out of range
   */
  public static void validateRange(String paramName, double value, double min, double max) {
    if (value < min || value > max) {
      throw new IllegalArgumentException(
        String.format("%s must be in range [%.4f, %.4f], got: %.4f",
        paramName, min, max, value));
    }
  }

  /**
   * Validates that a parameter value is within the specified range (inclusive).
   *
   * @param paramName Parameter name (for error message)
   * @param value Value to validate
   * @param min Minimum allowed value (inclusive)
   * @param max Maximum allowed value (inclusive)
   * @throws IllegalArgumentException If value is out of range
   */
  public static void validateRange(String paramName, int value, int min, int max) {
    if (value < min || value > max) {
      throw new IllegalArgumentException(
        String.format("%s must be in range [%d, %d], got: %d",
        paramName, min, max, value));
    }
  }

  /**
   * Validates that all individuals in the population are within the specified bounds.
   *
   * @param pop Population to validate
   * @param lowerBound Lower bound of decision variables
   * @param upperBound Upper bound of decision variables
   * @throws IllegalArgumentException If any individual has values outside bounds
   */
  public static void validatePopulationBounds(List<double[]> pop, double lowerBound, double upperBound) {
    if (pop == null || pop.isEmpty()) {
      return; // Population validation should be done separately
    }

    int D = pop.get(0).length;
    for (int i = 0; i < pop.size(); i++) {
      if (pop.get(i) == null) {
        continue; // Null check should be done separately
      }
      for (int j = 0; j < D; j++) {
        double value = pop.get(i)[j];
        if (value < lowerBound - BOUNDARY_TOLERANCE || value > upperBound + BOUNDARY_TOLERANCE) {
          throw new IllegalArgumentException(
            String.format(
            "Individual[%d][%d] = %.16f is out of bounds [%.16f, %.16f] (tolerance=%.1e)",
            i, j, value, lowerBound, upperBound, BOUNDARY_TOLERANCE));
        }
      }
    }
  }

  /**
   * Validates that the bounds are valid (upperBound > lowerBound).
   *
   * @param lowerBound Lower bound to validate
   * @param upperBound Upper bound to validate
   * @throws IllegalArgumentException If bounds are invalid
   */
  public static void validateBounds(double lowerBound, double upperBound) {
    if (upperBound <= lowerBound) {
      throw new IllegalArgumentException(
        String.format("Upper bound (%.4f) must be greater than lower bound (%.4f)",
        upperBound, lowerBound));
    }
  }

  /**
   * Combined validation for common algorithm parameters.
   *
   * <p>This is a convenience method that performs the most common validations in a single call:
   * <ul>
   *   <li>Population validation (null, empty, dimension consistency)</li>
   *   <li>Select indices validation (null, empty, range check)</li>
   *   <li>Configuration validation (null check)</li>
   *   <li>Random generator validation (null check)</li>
   * </ul>
   *
   * @param pop Population to validate
   * @param selectIndices Indices to validate
   * @param config Configuration to validate
   * @param rand Random generator to validate
   * @throws IllegalArgumentException If any validation fails
   */
  public static void validateCommonParameters(List<double[]> pop,
    int[] selectIndices,
    Object config,
    Random rand) {
    validatePopulation(pop);
    validateSelectIndices(selectIndices, pop.size());
    validateConfig(config);
    validateRandom(rand);
  }

  /**
   * Combined validation including fitness array.
   *
   * @param pop Population to validate
   * @param selectIndices Indices to validate
   * @param popFit Fitness array to validate
   * @param config Configuration to validate
   * @param rand Random generator to validate
   * @throws IllegalArgumentException If any validation fails
   */
  public static void validateCommonParameters(List<double[]> pop,
    int[] selectIndices,
    double[] popFit,
    Object config,
    Random rand) {
    validatePopulation(pop);
    validateSelectIndices(selectIndices, pop.size());
    validateFitness(popFit, pop.size());
    validateConfig(config);
    validateRandom(rand);
  }

  /**
   * Combined validation including fitness list.
   *
   * @param pop Population to validate
   * @param selectIndices Indices to validate
   * @param popFit Fitness list to validate
   * @param config Configuration to validate
   * @param rand Random generator to validate
   * @throws IllegalArgumentException If any validation fails
   */
  public static void validateCommonParameters(List<double[]> pop,
    int[] selectIndices,
    List<Double> popFit,
    Object config,
    Random rand) {
    validatePopulation(pop);
    validateSelectIndices(selectIndices, pop.size());
    validateFitness(popFit, pop.size());
    validateConfig(config);
    validateRandom(rand);
  }

  /**
   * Validates that the sigma array (step size factors) is not null, matches the population
   * size, and all sigma vectors have consistent dimensions matching the population.
   *
   * <p>This method is commonly used in algorithms that maintain per-individual step sizes,
   * such as Simulated Annealing, Evolution Strategies, and adaptive variants of other algorithms.
   *
   * <p>Validation checks performed:
   * <ul>
   *   <li>Sigma list is not null</li>
   *   <li>Sigma list size matches population size</li>
   *   <li>Each sigma vector is not null</li>
   *   <li>Each sigma vector has the same dimension as population individuals</li>
   * </ul>
   *
   * @param sigma List of step size factor vectors to validate
   * @param popSize Expected size of the sigma list (must match population size)
   * @param dimension Expected dimension of each sigma vector (must match individual dimension)
   * @throws IllegalArgumentException If validation fails
   */
  public static void validateSigma(List<double[]> sigma, int popSize, int dimension) {
    if (sigma == null) {
      throw new IllegalArgumentException("Sigma cannot be null");
    }
    if (sigma.size() != popSize) {
      throw new IllegalArgumentException(
        String.format("Sigma size (%d) must match population size (%d)",
        sigma.size(), popSize));
    }
    for (int i = 0; i < popSize; i++) {
      if (sigma.get(i) == null) {
        throw new IllegalArgumentException("Sigma contains null at index: " + i);
      }
      if (sigma.get(i).length != dimension) {
        throw new IllegalArgumentException(
          String.format("Sigma has inconsistent dimensions at index %d: expected %d, got %d",
          i, dimension, sigma.get(i).length));
      }
    }
  }

  /**
   * Combined validation for Simulated Annealing and other algorithms using sigma.
   *
   * <p>This is a convenience method that validates all parameters commonly used in SA:
   * <ul>
   *   <li>Population validation</li>
   *   <li>Fitness validation</li>
   *   <li>Sigma (step size) validation</li>
   *   <li>Select indices validation</li>
   *   <li>Configuration validation</li>
   *   <li>Random generator validation</li>
   * </ul>
   *
   * @param pop Population to validate
   * @param popFit Fitness list to validate
   * @param sigma Step size factors to validate
   * @param selectIndices Indices to validate
   * @param config Configuration to validate
   * @param rand Random generator to validate
   * @throws IllegalArgumentException If any validation fails
   */
  public static void validateSAParameters(List<double[]> pop,
    List<Double> popFit,
    List<double[]> sigma,
    int[] selectIndices,
    Object config,
    Random rand) {
    validatePopulation(pop);
    int popSize = pop.size();
    int dimension = pop.get(0).length;

    validateFitness(popFit, popSize);
    validateSigma(sigma, popSize, dimension);
    validateSelectIndices(selectIndices, popSize);
    validateConfig(config);
    validateRandom(rand);
  }
}
