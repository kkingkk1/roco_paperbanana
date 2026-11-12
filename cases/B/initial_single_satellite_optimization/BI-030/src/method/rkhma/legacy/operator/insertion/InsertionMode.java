package method.rkhma.legacy.operator.insertion;

/**
 * Insertion strategy enumeration.
 *
 * Dependencies: none
 */
public enum InsertionMode {

  /** Greedy insertion: selects the first feasible window position */
  GREEDY,

  /** Min transition time insertion: selects the window position with minimum transition time (formerly useOptimal=true) */
  MIN_TRANSITION,

  /** Max slack insertion: selects the feasible position with maximum transition time, leaving maximum gaps for subsequent tasks */
  MAX_SLACK,

  /** Shortest window first: prioritizes windows with the shortest time span, handling the most constrained tasks first */
  SHORTEST_WINDOW_FIRST,

  /** Earliest deadline first: prioritizes windows with the earliest deadline */
  EARLIEST_DEADLINE_FIRST,

  /** Fully random: randomly selects one from all feasible windows */
  RANDOM,

  /** Longest window first: prioritizes windows with the longest time span, preserving more flexibility for subsequent tasks */
  LONGEST_WINDOW_FIRST,

  /** Max remaining capacity: selects the window with the most remaining idle time after insertion, improving overall resource utilization */
  MAX_REMAINING_CAPACITY;

  /**
   * Convert from boolean useOptimal
   */
  public static InsertionMode fromUseOptimal(boolean useOptimal) {
    return useOptimal ? MIN_TRANSITION : GREEDY;
  }
}
