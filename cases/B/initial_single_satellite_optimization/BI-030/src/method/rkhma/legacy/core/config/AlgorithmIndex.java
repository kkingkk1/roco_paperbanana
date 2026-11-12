package method.rkhma.legacy.core.config;

/**
 * Algorithm index enumeration for HybCec
 *
 * Maps algorithm types to their flagPar indices for configuration management.
 * This eliminates magic numbers and improves code readability.
 */
public enum AlgorithmIndex {
  /** Genetic Algorithm */
  GA(0, "GA"),

  /** Particle Swarm Optimization */
  PSO(1, "PSO"),

  /** Estimation of Distribution Algorithm */
  EDA(2, "EDA"),

  /** Ant Colony Optimization */
  ACO(3, "ACO"),

  /** Differential Evolution: rand/1/bin */
  DE_RAND_1(4, "DE/rand/1/bin"),

  /** Differential Evolution: best/1/bin */
  DE_BEST_1(5, "DE/best/1/bin"),

  /** Differential Evolution: current-to-rand/1 */
  DE_CURRENT_TO_RAND_1(6, "DE/current-to-rand/1"),

  /** Artificial Bee Colony */
  ABC(7, "ABC"),

  /** Simulated Annealing */
  SA(8, "SA"),

  /** Linear population size reduction SHADE */
  L_SHADE(9, "L-SHADE"),

  /** Adaptive Large Neighborhood Search */
  ALNS(10, "ALNS"),

  /** Differential Evolution: rand/2/bin (moved to deprecated tail area) */
  DE_RAND_2(11, "DE/rand/2/bin"),

  /** Differential Evolution: best/2/bin (moved to deprecated tail area) */
  DE_BEST_2(12, "DE/best/2/bin"),

  /** Differential Evolution: current-to-best/1 (moved to deprecated tail area) */
  DE_CURRENT_TO_BEST_1(13, "DE/current-to-best/1"),

  /** Differential Evolution: rand-to-best/1 (moved to deprecated tail area) */
  DE_RAND_TO_BEST_1(14, "DE/rand-to-best/1"),

  /** SHADE without linear population reduction (moved to deprecated tail area) */
  SHADE(15, "SHADE");

  private final int index;
  private final String name;

  AlgorithmIndex(int index, String name) {
    this.index = index;
    this.name = name;
  }

  /**
   * Get the flagPar array index for this algorithm
   */
  public int getIndex() {
    return index;
  }

  /**
   * Get the display name of this algorithm
   */
  public String getName() {
    return name;
  }

  /**
   * Check if this algorithm is a DE variant
   */
  public boolean isDEVariant() {
    switch (this) {
      case DE_RAND_1:
      case DE_BEST_1:
      case DE_CURRENT_TO_RAND_1:
      case DE_RAND_2:
      case DE_BEST_2:
      case DE_CURRENT_TO_BEST_1:
      case DE_RAND_TO_BEST_1:
        return true;
      default:
        return false;
    }
  }

  /**
   * Check if this algorithm is a SHADE variant
   */
  public boolean isSHADEVariant() {
    return this == SHADE || this == L_SHADE;
  }

  /**
   * Get AlgorithmIndex by flagPar index
   *
   * @param index The flagPar array index
   * @return The corresponding AlgorithmIndex, or null if not found
   */
  public static AlgorithmIndex fromIndex(int index) {
    for (AlgorithmIndex algo : values()) {
      if (algo.index == index) {
        return algo;
      }
    }
    return null;
  }

  /**
   * Get the minimum required flagPar array length
   */
  public static int getRequiredFlagParLength() {
    return 16; // Indices 0-15
  }

  @Override
  public String toString() {
    return name + " (index=" + index + ")";
  }
}
