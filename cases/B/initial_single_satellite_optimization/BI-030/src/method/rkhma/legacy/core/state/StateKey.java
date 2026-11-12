package method.rkhma.legacy.core.state;

/**
 * Enumeration of state keys for type-safe state management.
 *
 * This enum replaces magic strings used in StateUpdater interface,
 * providing compile-time safety and better IDE support.
 */
public enum StateKey {
  /** ABC limit state */
  ABC_LIMIT("abc_limit"),

  /** SA sigma state */
  SA_SIGMA("sa_sigma"),

  /** SHADE memory for generating Gaussian samples */
  SHADE_GG("shade_gg"),

  /** SHADE successful F parameters */
  SHADE_SF("shade_sf"),

  /** SHADE successful CR parameters */
  SHADE_CR("shade_cr"),

  /** SHADE frequency counter */
  SHADE_FREQ("shade_freq"),

  /** SHADE flag1 */
  FLAG1_SHADE("flag1_shade"),

  /** SHADE flag2 */
  FLAG2_SHADE("flag2_shade");

  private final String key;

  StateKey(String key) {
    this.key = key;
  }

  /**
   * Gets the string representation of this state key.
   * Provided for backward compatibility with existing callers.
   *
   * @return the string key
   */
  public String getKey() {
    return key;
  }

  /**
   * Gets a StateKey from its string representation.
   *
   * @param key the string key
   * @return the corresponding StateKey
   * @throws IllegalArgumentException if the key is not recognized
   */
  public static StateKey fromString(String key) {
    for (StateKey stateKey : values()) {
      if (stateKey.key.equals(key)) {
        return stateKey;
      }
    }
    throw new IllegalArgumentException("Unknown state key: " + key);
  }

  @Override
  public String toString() {
    return key;
  }
}
