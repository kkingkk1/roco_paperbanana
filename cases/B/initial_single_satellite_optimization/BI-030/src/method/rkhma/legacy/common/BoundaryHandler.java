package method.rkhma.legacy.common;

import java.util.List;
import java.util.Random;

/**
 * Boundary handling utility class.
 * Provides multiple boundary handling strategies for out-of-bounds issues in optimization algorithms.
 *
 * Supported boundary handling modes:
 * - CLIP: Clamp directly to boundary
 * - MIDPOINT: Midpoint repair (between current value and boundary)
 * - REFLECT: Reflect back into boundary
 * - RANDOM: Randomly place near boundary region
 * - FOLD_BACK_REFLECTION: Fold-back reflection (canonical multi-reflection implementation)
 * - SHRINK: Exponential/proportional shrink (shrink back along search direction)
 *
 * @author AutoHyb Framework
 * @version 2.0
 */
public class BoundaryHandler {

  /**
   * Boundary handling mode enumeration
   */
  public enum BoundaryMode {
    /** Clamp directly to boundary */
    CLIP,
    /** Midpoint repair strategy (SHADE algorithm) */
    MIDPOINT,
    /** Reflect back into boundary */
    REFLECT,
    /** Randomly place near boundary */
    RANDOM,
    /** Specular reflection (reflect position + reverse velocity) */
    SPECULAR_REFLECTION,
    /** Random rebound (random position + reverse velocity) */
    RANDOM_REBOUND,
    /** Fold-back reflection (canonical multi-reflection implementation, handles large violations) */
    FOLD_BACK_REFLECTION,
    /** Exponential/proportional shrink (shrink back along search direction) */
    SHRINK
  }

  /** Maximum reflection iterations to prevent infinite loops */
  private static final int MAX_REFLECTION_ITERATIONS = 10;

  // ==================== Basic clipping methods ====================

  /**
   * Simple clamp to boundary (most commonly used).
   * Used by: ABC, ACO, GA, SA, etc.
   *
   * @param value Value to clamp
   * @param lowerBound Lower bound
   * @param upperBound Upper bound
   * @return Clamped value
   */
  public static double clip(double value, double lowerBound, double upperBound) {
    return Math.max(lowerBound, Math.min(upperBound, value));
  }

  /**
   * Batch clamp all dimensions in an array.
   *
   * @param values Array of values to clamp (modified in place)
   * @param lowerBound Lower bound
   * @param upperBound Upper bound
   */
  public static void clipArray(double[] values, double lowerBound, double upperBound) {
    for (int i = 0; i < values.length; i++) {
      values[i] = clip(values[i], lowerBound, upperBound);
    }
  }

  /**
   * Velocity clamp (for velocity-based algorithms like PSO).
   *
   * @param velocity Current velocity
   * @param vMinLimit Minimum velocity limit
   * @param vMaxLimit Maximum velocity limit
   * @return Clamped velocity
   */
  public static double clipVelocity(double velocity, double vMinLimit, double vMaxLimit) {
    if (velocity > vMaxLimit) {
      return vMaxLimit;
    } else if (velocity < vMinLimit) {
      return vMinLimit;
    }
    return velocity;
  }

  // ==================== Midpoint repair methods ====================

  /**
   * Midpoint repair strategy (used by SHADE algorithm).
   * If out of bounds, returns the midpoint between current value and boundary,
   * preserving more original information.
   *
   * @param value Mutated value (may be out of bounds)
   * @param currentValue Current individual's value
   * @param lowerBound Lower bound
   * @param upperBound Upper bound
   * @return Repaired value
   */
  public static double clipMidpoint(double value, double currentValue,
    double lowerBound, double upperBound) {
    if (value < lowerBound) {
      return (lowerBound + currentValue) / 2.0;
    }
    if (value > upperBound) {
      return (upperBound + currentValue) / 2.0;
    }
    return value;
  }

  // ==================== Reflection repair methods ====================

  /**
   * Reflect position back into boundary (used by PSO).
   * Supports multiple reflections, up to MAX_REFLECTION_ITERATIONS to prevent infinite loops.
   *
   * @param position Position value (may be out of bounds)
   * @param lowerBound Lower bound
   * @param upperBound Upper bound
   * @return Reflected position
   */
  public static double reflect(double position, double lowerBound, double upperBound) {
    double reflectedPos = position;
    int iterations = 0;

    while ((reflectedPos < lowerBound || reflectedPos > upperBound)
      && iterations < MAX_REFLECTION_ITERATIONS) {
      if (reflectedPos < lowerBound) {
        reflectedPos = lowerBound + (lowerBound - reflectedPos);
      } else if (reflectedPos > upperBound) {
        reflectedPos = upperBound - (reflectedPos - upperBound);
      }
      iterations++;
    }

    // If still out of bounds after max iterations, clamp to boundary
    if (reflectedPos < lowerBound) {
      reflectedPos = lowerBound;
    } else if (reflectedPos > upperBound) {
      reflectedPos = upperBound;
    }

    return reflectedPos;
  }

  /**
   * Reflect position and reverse velocity if boundary is hit (classic PSO strategy).
   *
   * @param position Position value
   * @param velocity Velocity value
   * @param lowerBound Lower bound
   * @param upperBound Upper bound
   * @return [reflected position, updated velocity] array
   */
  public static double[] reflectWithVelocity(double position, double velocity,
    double lowerBound, double upperBound) {
    double reflectedPos = position;
    boolean hitBoundary = false;
    int iterations = 0;

    while ((reflectedPos < lowerBound || reflectedPos > upperBound)
      && iterations < MAX_REFLECTION_ITERATIONS) {
      if (reflectedPos < lowerBound) {
        reflectedPos = lowerBound + (lowerBound - reflectedPos);
        hitBoundary = true;
      } else if (reflectedPos > upperBound) {
        reflectedPos = upperBound - (reflectedPos - upperBound);
        hitBoundary = true;
      }
      iterations++;
    }

    if (reflectedPos < lowerBound) {
      reflectedPos = lowerBound;
      hitBoundary = true;
    } else if (reflectedPos > upperBound) {
      reflectedPos = upperBound;
      hitBoundary = true;
    }

    double newVelocity = hitBoundary ? -velocity : velocity;
    return new double[]{reflectedPos, newVelocity};
  }

  /**
   * Reflect position and reverse velocity if boundary is hit (classic PSO strategy),
   * writing the scalar results directly into the caller-provided arrays.
   *
   * <p>This is allocation-free equivalent to {@link #reflectWithVelocity(double, double, double, double)}.
   */
  public static void reflectWithVelocityInPlace(
    double[] posArray,
    double[] velArray,
    int dimIdx,
    double position,
    double velocity,
    double lowerBound,
    double upperBound) {
    double reflectedPos = position;
    boolean hitBoundary = false;
    int iterations = 0;

    while ((reflectedPos < lowerBound || reflectedPos > upperBound)
      && iterations < MAX_REFLECTION_ITERATIONS) {
      if (reflectedPos < lowerBound) {
        reflectedPos = lowerBound + (lowerBound - reflectedPos);
        hitBoundary = true;
      } else if (reflectedPos > upperBound) {
        reflectedPos = upperBound - (reflectedPos - upperBound);
        hitBoundary = true;
      }
      iterations++;
    }

    if (reflectedPos < lowerBound) {
      reflectedPos = lowerBound;
      hitBoundary = true;
    } else if (reflectedPos > upperBound) {
      reflectedPos = upperBound;
      hitBoundary = true;
    }

    posArray[dimIdx] = reflectedPos;
    velArray[dimIdx] = hitBoundary ? -velocity : velocity;
  }

  /**
   * Fold-back reflection.
   * Handles large boundary violations by folding the value back into the interval,
   * producing a more symmetric distribution. Suitable for DE/SHADE algorithms.
   *
   * Algorithm principle:
   * 1. Translate value to a coordinate system with lower bound as origin
   * 2. Take modulo of twice the range to get the folded position
   * 3. Determine final mapped position based on the folded position
   *
   * @param value Value that may be out of bounds
   * @param lowerBound Lower bound
   * @param upperBound Upper bound
   * @return Fold-back reflected value
   */
  public static double foldBackReflect(double value, double lowerBound, double upperBound) {
    // If within bounds, return directly
    if (value >= lowerBound && value <= upperBound) {
      return value;
    }

    double range = upperBound - lowerBound;

    // Translate to coordinate system with lower bound as origin
    double y = value - lowerBound;

    // Compute m = y mod (2*R), mapping to [0, 2*R)
    double m = y - Math.floor(y / (2.0 * range)) * (2.0 * range);

    // Determine final value based on m's position
    if (m <= range) {
      // In the first half, map directly
      return lowerBound + m;
    } else {
      // In the second half, reverse map from upper bound
      return upperBound - (m - range);
    }
  }

  /**
   * Batch fold-back reflection (array version).
   *
   * @param values Array of values to process (modified in place)
   * @param lowerBound Lower bound
   * @param upperBound Upper bound
   */
  public static void foldBackReflectArray(double[] values, double lowerBound, double upperBound) {
    for (int i = 0; i < values.length; i++) {
      values[i] = foldBackReflect(values[i], lowerBound, upperBound);
    }
  }

  // ==================== Random repair methods ====================

  /**
   * Random reinitialization (commonly used by DE algorithm).
   * When value is out of bounds, randomly reinitialize within the entire feasible domain
   * [lowerBound, upperBound]. This is an aggressive boundary handling strategy suitable
   * for the exploration phase.
   *
   * @param value Value (may be out of bounds)
   * @param lowerBound Lower bound
   * @param upperBound Upper bound
   * @param random Random number generator
   * @return Repaired value (randomly reinitialized if out of bounds, otherwise original value)
   */
  public static double randomReinitialize(double value, double lowerBound, double upperBound,
    Random random) {
    if (value < lowerBound || value > upperBound) {
      return lowerBound + (upperBound - lowerBound) * random.nextDouble();
    }
    return value;
  }

  /**
   * Boundary repair used by DE-family mutations.
   *
   * <p>{@link BoundaryMode#RANDOM} preserves the historical DE behavior.
   * {@link BoundaryMode#MIDPOINT} matches SHADE-style midpoint clipping, and
   * {@link BoundaryMode#REFLECT} uses fold-back reflection for large violations.
   */
  public static double repairDE(double value,
    double currentValue,
    double lowerBound,
    double upperBound,
    Random random,
    BoundaryMode mode) {
    BoundaryMode effectiveMode = (mode == null) ? BoundaryMode.RANDOM : mode;
    switch (effectiveMode) {
      case MIDPOINT:
        return clipMidpoint(value, currentValue, lowerBound, upperBound);
      case REFLECT:
      case FOLD_BACK_REFLECTION:
        return foldBackReflect(value, lowerBound, upperBound);
      case CLIP:
        return clip(value, lowerBound, upperBound);
      case RANDOM:
      default:
        return randomReinitialize(value, lowerBound, upperBound, random);
    }
  }

  /**
   * Random repair to near-boundary region (used by EDA algorithm).
   * Out-of-bounds dimensions are randomly placed in a repair region near the boundary.
   *
   * @param value Value (may be out of bounds)
   * @param lowerBound Lower bound
   * @param upperBound Upper bound
   * @param repairRatio Repair region ratio (0.0-1.0)
   * @param random Random number generator
   * @return Repaired value
   */
  public static double randomRepair(double value, double lowerBound, double upperBound,
    double repairRatio, Random random) {
    double range = upperBound - lowerBound;
    double repairRange = repairRatio * range;

    if (value < lowerBound) {
      // Below lower bound: randomly place in [lowerBound, lowerBound + repairRange]
      return lowerBound + repairRange * random.nextDouble();
    } else if (value > upperBound) {
      // Above upper bound: randomly place in [upperBound - repairRange, upperBound]
      return upperBound - repairRange * random.nextDouble();
    }
    return value;
  }

  /**
   * Batch random repair for entire population (used by EDA algorithm).
   *
   * @param population Population (modified in place)
   * @param lowerBound Lower bound
   * @param upperBound Upper bound
   * @param repairRatio Repair region ratio
   * @param random Random number generator
   */
  public static void randomRepairPopulation(List<double[]> population,
    double lowerBound, double upperBound,
    double repairRatio, Random random) {
    for (double[] individual : population) {
      for (int d = 0; d < individual.length; d++) {
        individual[d] = randomRepair(individual[d], lowerBound, upperBound,
          repairRatio, random);
      }
    }
  }

  // ==================== Exponential/proportional shrink methods ====================

  /**
   * Exponential/proportional shrink (Shrink / Back-off along direction).
   * When the trial vector is out of bounds, shrink back along the direction from
   * parent to trial to the feasible domain boundary.
   * Formula: u = parent + alpha * (trial - parent), alpha in (0, 1]
   *
   * Particularly suitable for DE/SHADE algorithms because it:
   * 1. Preserves the search direction
   * 2. Does not introduce random jitter
   * 3. Provides stable convergence in later stages
   *
   * @param parent Parent vector (current individual)
   * @param trial Mutant vector (may be out of bounds)
   * @param lowerBound Lower bound
   * @param upperBound Upper bound
   * @param shrinkFactor Shrink factor (recommended 0.99), leaves a small margin near boundary
   * @return Shrunk vector
   */
  public static double[] shrinkAlongDirection(double[] parent, double[] trial,
    double lowerBound, double upperBound,
    double shrinkFactor) {
    int dim = parent.length;
    double[] result = new double[dim];

    // First check if shrinking is needed
    boolean needsShrink = false;
    for (int i = 0; i < dim; i++) {
      if (trial[i] < lowerBound || trial[i] > upperBound) {
        needsShrink = true;
        break;
      }
    }

    // If no shrinking needed, return trial directly
    if (!needsShrink) {
      System.arraycopy(trial, 0, result, 0, dim);
      return result;
    }

    // Compute maximum feasible alpha
    // u = parent + alpha * (trial - parent)
    // Require: lowerBound <= parent[i] + alpha * (trial[i] - parent[i]) <= upperBound
    double maxAlpha = 1.0;

    for (int i = 0; i < dim; i++) {
      double diff = trial[i] - parent[i];

      if (Math.abs(diff) < 1e-10) {
        // If diff is very small, skip
        continue;
      }

      if (diff > 0) {
        // Moving upward: check upper bound constraint
        // parent[i] + alpha * diff <= upperBound
        // alpha <= (upperBound - parent[i]) / diff
        double alphaUpper = (upperBound - parent[i]) / diff;
        maxAlpha = Math.min(maxAlpha, alphaUpper);
      } else {
        // Moving downward: check lower bound constraint
        // parent[i] + alpha * diff >= lowerBound
        // alpha <= (lowerBound - parent[i]) / diff (note: diff < 0)
        double alphaLower = (lowerBound - parent[i]) / diff;
        maxAlpha = Math.min(maxAlpha, alphaLower);
      }
    }

    // Apply shrink factor, leaving a small margin near boundary
    maxAlpha = Math.max(0.0, Math.min(1.0, maxAlpha * shrinkFactor));

    // Compute shrunk vector
    for (int i = 0; i < dim; i++) {
      result[i] = parent[i] + maxAlpha * (trial[i] - parent[i]);
      // Safety clamp to handle numerical errors
      result[i] = clip(result[i], lowerBound, upperBound);
    }

    return result;
  }


  /**
   * In-place shrink (modifies trial array).
   * Memory-saving version.
   *
   * @param parent Parent vector
   * @param trial Mutant vector (modified in place)
   * @param lowerBound Lower bound
   * @param upperBound Upper bound
   * @param shrinkFactor Shrink factor
   */
  public static void shrinkAlongDirectionInPlace(double[] parent, double[] trial,
    double lowerBound, double upperBound,
    double shrinkFactor) {
    double[] result = shrinkAlongDirection(parent, trial, lowerBound, upperBound, shrinkFactor);
    System.arraycopy(result, 0, trial, 0, trial.length);
  }


  // ==================== PSO-specific multi-mode repair methods ====================

  /**
   * PSO position boundary repair (supports multiple modes).
   *
   * @param posArray Position array (to be modified)
   * @param velArray Velocity array (to be modified)
   * @param dimIdx Dimension index
   * @param position New position value (may be out of bounds)
   * @param velocity New velocity value
   * @param lowerBound Lower bound
   * @param upperBound Upper bound
   * @param range Boundary range width
   * @param random Random number generator
   * @param mode Boundary handling mode
   * @param boundaryRepairRatio Repair range ratio
   */
  public static void repairPositionInPlace(
    double[] posArray,
    double[] velArray,
    int dimIdx,
    double position,
    double velocity,
    double lowerBound,
    double upperBound,
    double range,
    Random random,
    BoundaryMode mode,
    double boundaryRepairRatio) {

    if (position >= lowerBound && position <= upperBound) {
      // Within bounds, no repair needed
      posArray[dimIdx] = position;
      velArray[dimIdx] = velocity;
      return;
    }

    switch (mode) {
      case CLIP:
        // Clamp position to boundary, velocity unchanged
        posArray[dimIdx] = clip(position, lowerBound, upperBound);
        velArray[dimIdx] = velocity;
        break;

      case REFLECT:
        // Reflect position back into boundary, velocity unchanged
        posArray[dimIdx] = reflect(position, lowerBound, upperBound);
        velArray[dimIdx] = velocity;
        break;

      case FOLD_BACK_REFLECTION:
        // Fold-back reflection (handles large violations)
        posArray[dimIdx] = foldBackReflect(position, lowerBound, upperBound);
        velArray[dimIdx] = velocity;
        break;

      case SPECULAR_REFLECTION:
        // Classic PSO: reflect position + reverse velocity (recommended)
        reflectWithVelocityInPlace(
          posArray, velArray, dimIdx,
          position, velocity, lowerBound, upperBound);
        break;

      case RANDOM_REBOUND:
        // Random rebound: randomize position + reverse velocity
        double repairRange = boundaryRepairRatio * range;
        if (position < lowerBound) {
          posArray[dimIdx] = lowerBound + repairRange * random.nextDouble();
          velArray[dimIdx] = -velocity;
      } else if (position > upperBound) {
        posArray[dimIdx] = upperBound - repairRange * random.nextDouble();
        velArray[dimIdx] = -velocity;
      }
      break;

      default:
        // Default: use CLIP
        posArray[dimIdx] = clip(position, lowerBound, upperBound);
        velArray[dimIdx] = velocity;
        break;
    }
  }

  // ==================== General boundary check methods ====================

  /**
   * Check if a value is within bounds.
   *
   * @param value Value to check
   * @param lowerBound Lower bound
   * @param upperBound Upper bound
   * @return true if within bounds, false otherwise
   */
  public static boolean isInBounds(double value, double lowerBound, double upperBound) {
    return value >= lowerBound && value <= upperBound;
  }

  /**
   * Check if all dimensions of an array are within bounds.
   *
   * @param values Array of values to check
   * @param lowerBound Lower bound
   * @param upperBound Upper bound
   * @return true if all dimensions are within bounds, false otherwise
   */
  public static boolean isArrayInBounds(double[] values, double lowerBound, double upperBound) {
    for (double value : values) {
      if (!isInBounds(value, lowerBound, upperBound)) {
        return false;
      }
    }
    return true;
  }
}
