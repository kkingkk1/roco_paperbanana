package method.rkhma.legacy.core.state;

import java.util.List;
import java.util.Map;

/**
 * State Updater Interface (Refactored)
 *
 * Used to update the state in HybCec after the execution of strategy algorithms.
 * Through this interface, strategies can update the states they need without directly accessing the HybCec object.
 *
 * Design principles:
 * 1. Use generic methods to decouple from specific algorithm implementations
 * 2. Support both partial (with mask) and full updates
 * 3. Extensible to new algorithms without modifying the interface
 * 4. Type-safe state keys using StateKey enum
 */
public interface StateUpdater {

  // ============ Generic Update Methods ============

  /**
   * Update state with optional partial update mask (type-safe version using enum)
   *
   * @param <T> State data type
   * @param stateKey State identifier (use StateKey enum)
   * @param stateData New state data
   * @param updateMask Update mask (true = update, false = skip). null or empty means full update
   */
  <T> void updateState(StateKey stateKey, List<T> stateData, List<Boolean> updateMask);

  /**
   * Batch update multiple states at once
   *
   * @param states Map of state key to state data
   */
  void updateStates(Map<String, Object> states);

  /**
   * Increment iteration counter
   *
   * @param increment Amount to increment by
   */
  void incrementIteration(int increment);

  // ============ Convenience Methods (Backward Compatibility) ============

  /**
   * Update ABC limit state (full update)
   *
   * @param limit New limit values
   */
  default void updateABCLimit(List<Double> limit) {
    updateState(StateKey.ABC_LIMIT, limit, null);
  }

  /**
   * Update SA sigma state (partial update with mask)
   *
   * @param sigma New sigma values (only for individuals where mask is true)
   * @param updateMask Boolean mask indicating which individuals to update
   */
  default void updateSASigma(List<double[]> sigma, List<Boolean> updateMask) {
    updateState(StateKey.SA_SIGMA, sigma, updateMask);
  }

  /**
   * Update SHADE algorithm state (convenience method for SHADE result)
   * Note: Handles type conversion between SHADE result types and state storage types
   *
   * @param shadeGG SHADE generation counter
   * @param shadeSF SHADE successful F parameters (double[] will be converted to List)
   * @param shadeCR SHADE successful CR parameters (double[] will be converted to List)
   * @param shadeFreq SHADE frequency counter (double[] will be converted to List)
   * @param flag1Shade SHADE flag1
   * @param flag2Shade SHADE flag2
   */
  default void updateSHADEState(int shadeGG,
    double[] shadeSF,
    double[] shadeCR,
    double[] shadeFreq,
    boolean flag1Shade,
    boolean flag2Shade) {
    Map<String, Object> states = new java.util.HashMap<>();
    states.put(StateKey.SHADE_GG.getKey(), shadeGG);

    // Convert double[] to List<double[]> for SF
    // Each element corresponds to one selected individual's SF value
    if (shadeSF != null) {
      List<double[]> sfList = new java.util.ArrayList<>();
      for (double val : shadeSF) {
        sfList.add(new double[]{val});
      }
      states.put(StateKey.SHADE_SF.getKey(), sfList);
    }

    // Convert double[] to List<Double> for CR
    if (shadeCR != null) {
      List<Double> crList = new java.util.ArrayList<>();
      for (double val : shadeCR) {
        crList.add(val);
      }
      states.put(StateKey.SHADE_CR.getKey(), crList);
    }

    // Convert double[] to List<Double> for Freq
    if (shadeFreq != null) {
      List<Double> freqList = new java.util.ArrayList<>();
      for (double val : shadeFreq) {
        freqList.add(val);
      }
      states.put(StateKey.SHADE_FREQ.getKey(), freqList);
    }

    states.put(StateKey.FLAG1_SHADE.getKey(), flag1Shade);
    states.put(StateKey.FLAG2_SHADE.getKey(), flag2Shade);
    updateStates(states);
  }
}

