package method.rkhma.legacy.algorithm.de;

import method.rkhma.legacy.common.AlgorithmConstants;
import method.rkhma.legacy.common.BoundaryHandler;
import method.rkhma.legacy.core.config.ConfigValidatable;

/**
 * A general Differential Evolution (DE) algorithm configuration class,
 * designed for unified management of parameters across all DE series algorithms.
 *
 * <p>This configuration supports both static and adaptive F values:
 * <ul>
 *   <li>Static: Single-element arrays with constant F values throughout evolution</li>
 *   <li>Adaptive: Multi-element arrays where array[i] is the F value for generation i</li>
 * </ul>
 *
 * <p>For variants requiring two F values (DE/best/2, DE/rand-to-best/1):
 * <ul>
 *   <li>Set both differentialWeight1 and differentialWeight2</li>
 *   <li>If differentialWeight2 is null, F2 will default to F1 values</li>
 * </ul>
 *
 * <p>Search space bounds apply to solution variables, not to DE parameters.
 */
public class DEConfig implements ConfigValidatable {
  public enum BoundaryPolicy {
    /** Current DE behavior: out-of-bound mutant dimensions are randomly reinitialized. */
    RANDOM_REINIT,
    /** Opt-in midpoint repair between the current value and violated bound. */
    CLIP_MIDPOINT,
    /** Opt-in fold-back reflection of overflow into the feasible interval. */
    REFLECT
  }

  // =====================================================
  // DE Algorithm Constants
  // =====================================================

  /** DE Default Differential Weight */
  public static final double DE_DEFAULT_DIFFERENTIAL_WEIGHT = 0.01;

  /** DE Default Crossover Rate */
  public static final double DE_DEFAULT_CROSSOVER_RATE = 0.3;

  /** DE Default Weight Decay Factor */
  public static final double DE_WEIGHT_DECAY_FACTOR = 0.5;

  /** Adaptive F: Descent Rate factor */
  public static final double DE_ADAPTIVE_F_ALPHA_DEFAULT = 0.05;

  public static final int DE_ADAPTIVE_F_MODE_POWER = 1;
  public static final int DE_ADAPTIVE_F_MODE_PIECEWISE = 2;
  public static final int DE_ADAPTIVE_F_MODE_COSINE = 3;
  public static final int DE_ADAPTIVE_F_MODE_SIGMOID = 4;
  public static final int DE_ADAPTIVE_F_MODE_STOCHASTIC = 5;

  public static final int DE_ADAPTIVE_F_MODE_DEFAULT = DE_ADAPTIVE_F_MODE_POWER;

  public static final double DE_ADAPTIVE_F_FMAX_DEFAULT = 0.1;
  public static final double DE_ADAPTIVE_F_FMIN_DEFAULT = 0.01;

  public static final double DE_ADAPTIVE_F_PIECEWISE_P0_DEFAULT = 0.5;
  public static final double DE_ADAPTIVE_F_PIECEWISE_F_HIGH_DEFAULT = 0.9;
  public static final double DE_ADAPTIVE_F_PIECEWISE_F_LOW_DEFAULT = 0.3;

  public static final double DE_ADAPTIVE_F_SIGMOID_P0_DEFAULT = 0.4;
  public static final double DE_ADAPTIVE_F_SIGMOID_K_DEFAULT = 12.0;

  public static final double DE_ADAPTIVE_F_NOISE_SIGMA_DEFAULT = 0.1;

  /** DE Linear Decay: Initial maximum F value (used in linear decay strategy from max to min) */
  public static final double DE_F_LINEAR_DECAY_MAX = 1.0;

  /** DE Linear Decay: Final minimum F value after full decay */
  public static final double DE_F_LINEAR_DECAY_MIN = 0.5;

  /**
   * Primary differential weight F (scaling factor for difference vectors).
   * Array length determines the adaptation strategy:
   * - Length 1: Static F value for all generations
   * - Length > 1: Adaptive F where F[gen] is used for generation gen
   */
  public final double[] differentialWeight1;

  /**
   * Secondary differential weight F2 (used in variants like DE/best/2).
   * Set to null if not needed; will default to differentialWeight1 values.
   */
  public final double[] differentialWeight2;

  /** Crossover rate CR in range [0, 1], controls parameter inheritance probability */
  public final double crossoverRate;

  /** Upper bound of the search space for solution variables */
  public final double upperBound;

  /** Lower bound of the search space for solution variables */
  public final double lowerBound;

  /**
   * Opt-in DE boundary policy. RANDOM_REINIT preserves the historical default.
   */
  public final BoundaryPolicy boundaryPolicy;

  public DEConfig(double[] differentialWeight1,
    double[] differentialWeight2,
    double crossoverRate,
    double upperBound,
    double lowerBound) {
    this(differentialWeight1, differentialWeight2, crossoverRate, upperBound, lowerBound,
      BoundaryPolicy.RANDOM_REINIT);
  }

  public DEConfig(double[] differentialWeight1,
    double[] differentialWeight2,
    double crossoverRate,
    double upperBound,
    double lowerBound,
    BoundaryPolicy boundaryPolicy) {
    // Defensive copy to ensure immutability
    this.differentialWeight1 = (differentialWeight1 == null) ? null : differentialWeight1.clone();
    this.differentialWeight2 = (differentialWeight2 == null) ? null : differentialWeight2.clone();
    this.crossoverRate = crossoverRate;
    this.upperBound = upperBound;
    this.lowerBound = lowerBound;
    this.boundaryPolicy = (boundaryPolicy == null) ? BoundaryPolicy.RANDOM_REINIT : boundaryPolicy;
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

    // Validate differentialWeight1
    builder.checkNotNull("differentialWeight1", differentialWeight1);
    if (differentialWeight1 != null) {
      if (differentialWeight1.length == 0) {
        builder.addError("differentialWeight1 cannot be empty");
      }
    }

    // Validate differentialWeight2 (if provided)
    if (differentialWeight2 != null) {
      if (differentialWeight2.length == 0) {
        builder.addError("differentialWeight2 cannot be empty (use null if not needed)");
      }
    }

    // Validate crossoverRate is in [0, 1]
    builder.checkRange("crossoverRate", crossoverRate, 0.0, 1.0);

    // Validate bounds
    if (lowerBound >= upperBound) {
      builder.addError(String.format(
        "lowerBound must be less than upperBound, got lowerBound=%.4f, upperBound=%.4f",
        lowerBound, upperBound));
    }

    return builder.build();
  }

  public boolean usesDefaultBoundaryPolicy() {
    return boundaryPolicy == BoundaryPolicy.RANDOM_REINIT;
  }

  public BoundaryHandler.BoundaryMode boundaryMode() {
    switch (boundaryPolicy) {
      case CLIP_MIDPOINT:
        return BoundaryHandler.BoundaryMode.MIDPOINT;
      case REFLECT:
        return BoundaryHandler.BoundaryMode.FOLD_BACK_REFLECTION;
      case RANDOM_REINIT:
      default:
        return BoundaryHandler.BoundaryMode.RANDOM;
    }
  }

  /**
   * Create configuration for DE variants requiring two F values.
   * Examples: DE/best/2, DE/rand/2, DE/rand-to-best/1, DE/current-to-best/1
   *
   * @param differentialWeight1 Primary F values (required, non-null)
   * @param differentialWeight2 Secondary F values (required for two-F variants)
   * @param crossoverRate Crossover probability in [0, 1]
   * @param upperBound Upper search space bound
   * @param lowerBound Lower search space bound
   * @return DEConfig instance with two F parameters
   */
  public static DEConfig withTwoF(double[] differentialWeight1,
    double[] differentialWeight2,
    double crossoverRate,
    double upperBound,
    double lowerBound) {
    return new DEConfig(differentialWeight1, differentialWeight2, crossoverRate, upperBound, lowerBound);
  }

  /**
   * Provide a default configuration with standard DE parameters.
   * Uses static F = 0.01, CR = 0.3, and search bounds = [-750, 750].
   *
   * @return DEConfig with default static parameters
   */
  public static DEConfig defaultConfig() {
    return new DEConfig(
      new double[]{DE_DEFAULT_DIFFERENTIAL_WEIGHT},
      null,
      DE_DEFAULT_CROSSOVER_RATE,
      AlgorithmConstants.DEFAULT_BOUND,
      -AlgorithmConstants.DEFAULT_BOUND
    );
  }

  /**
   * Create configuration with sigmoid adaptive F schedule,
   * matching the tuned parameters used in standalone DE/rand/1.
   * F decays from fMax=0.1 to fMin=0.01 using a sigmoid curve
   * (sigmoidP0=0.4, sigmoidK=12.0).
   *
   * @param maxG Maximum generation count
   * @return DEConfig with sigmoid adaptive F values
   */
  public static DEConfig sigmoidAdaptiveForGenerations(int maxG) {
    int safeMaxG = Math.max(1, maxG);
    double[] fArray = AdaptiveFSchedule.build(
      safeMaxG,
      DE_ADAPTIVE_F_MODE_SIGMOID,
      DE_ADAPTIVE_F_ALPHA_DEFAULT,
      DE_ADAPTIVE_F_FMAX_DEFAULT,
      DE_ADAPTIVE_F_FMIN_DEFAULT,
      DE_ADAPTIVE_F_PIECEWISE_P0_DEFAULT,
      DE_ADAPTIVE_F_PIECEWISE_F_HIGH_DEFAULT,
      DE_ADAPTIVE_F_PIECEWISE_F_LOW_DEFAULT,
      DE_ADAPTIVE_F_SIGMOID_P0_DEFAULT,
      DE_ADAPTIVE_F_SIGMOID_K_DEFAULT,
      DE_ADAPTIVE_F_NOISE_SIGMA_DEFAULT,
      null
    );
    return new DEConfig(
      fArray, fArray,
      DE_DEFAULT_CROSSOVER_RATE,
      AlgorithmConstants.DEFAULT_BOUND,
      -AlgorithmConstants.DEFAULT_BOUND
    );
  }

  /**
   * Create default configurations based on generations,
   * with F values linearly decaying from max to min over iterations.
   *
   * @param maxG Maximum generation count
   * @return DEConfig with linearly decaying F values from 1.0 to 0.5
   */
  public static DEConfig defaultForGenerations(int maxG) {
    int safeMaxG = Math.max(1, maxG);

    double[] fArray = new double[safeMaxG];
    double fMax = DE_F_LINEAR_DECAY_MAX;
    double fMin = DE_F_LINEAR_DECAY_MIN;

    for (int i = 0; i < safeMaxG; i++) {
      double t = (safeMaxG == 1) ? 0.0 : (i / (double) (safeMaxG - 1));
      // Linear interpolation from fMax to fMin
      fArray[i] = fMax * (1.0 - t) + fMin * t;
    }
    return new DEConfig(
      fArray, fArray,
      DE_DEFAULT_CROSSOVER_RATE,
      AlgorithmConstants.DEFAULT_BOUND,
      -AlgorithmConstants.DEFAULT_BOUND
    );
  }

  /**
   * Get the F1 score of the current generation.
   *
   * @param gen Current generation index (must be non-negative)
   * @return F1 value for the generation
   * @throws IllegalArgumentException If gen is negative or differentialWeight1 is empty
   */
  public double getF1(int gen) {
    return getFValue(gen, differentialWeight1, "differentialWeight1");
  }

  /**
   * Get the F2 value of the current generation.
   *
   * @param gen Current generation index (must be non-negative)
   * @return F2 value for the generation (returns F1 if differentialWeight2 is null)
   * @throws IllegalArgumentException If gen is negative
   */
  public double getF2(int gen) {
    if (differentialWeight2 == null) {
      return getF1(gen);
    }
    return getFValue(gen, differentialWeight2, "differentialWeight2");
  }

  /**
   * Helper method to retrieve F value from array with bounds checking.
   *
   * @param gen Generation index
   * @param weights Weight array
   * @param paramName Parameter name for error messages
   * @return F value for the generation
   */
  private double getFValue(int gen, double[] weights, String paramName) {
    if (gen < 0) {
      throw new IllegalArgumentException("Generation index must be non-negative, got: " + gen);
    }
    if (weights == null || weights.length == 0) {
      throw new IllegalStateException(paramName + " is not initialized or is empty");
    }
    return weights[Math.min(gen, weights.length - 1)];
  }

}
