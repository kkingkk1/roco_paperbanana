package method.rkhma.legacy.operator.insertion;

import java.util.EnumMap;
import java.util.Map;

/**
 * Insertion strategy factory.
 *
 * Returns the corresponding insertion strategy instance based on InsertionMode enum.
 * Uses singleton pattern to cache strategy instances, avoiding repeated creation.
 *
 * Dependencies: operator.insertion.InsertionMode, operator.insertion.InsertionStrategy,
 *       operator.insertion.*Insertion (8 strategy implementation classes)
 */
public final class InsertionStrategyFactory {

  private static final Map<InsertionMode, InsertionStrategy> STRATEGY_CACHE = new EnumMap<>(InsertionMode.class);

  static {
    STRATEGY_CACHE.put(InsertionMode.GREEDY, new GreedyInsertion());
    STRATEGY_CACHE.put(InsertionMode.MIN_TRANSITION, new MinTransitionInsertion());
    STRATEGY_CACHE.put(InsertionMode.MAX_SLACK, new MaxSlackInsertion());
    STRATEGY_CACHE.put(InsertionMode.SHORTEST_WINDOW_FIRST, new ShortestWindowFirstInsertion());
    STRATEGY_CACHE.put(InsertionMode.EARLIEST_DEADLINE_FIRST, new EarliestDeadlineFirstInsertion());
    STRATEGY_CACHE.put(InsertionMode.RANDOM, new RandomInsertion());
    STRATEGY_CACHE.put(InsertionMode.LONGEST_WINDOW_FIRST, new LongestWindowFirstInsertion());
    STRATEGY_CACHE.put(InsertionMode.MAX_REMAINING_CAPACITY, new MaxRemainingCapacityInsertion());
  }

  private InsertionStrategyFactory() {
    throw new AssertionError("Utility class should not be instantiated");
  }

  /**
   * Get the corresponding strategy instance for the given insertion mode.
   *
   * @param mode insertion mode
   * @return corresponding insertion strategy instance
   * @throws IllegalArgumentException if mode is null or unsupported
   */
  public static InsertionStrategy getStrategy(InsertionMode mode) {
    if (mode == null) {
      throw new IllegalArgumentException("InsertionMode cannot be null");
    }

    InsertionStrategy strategy = STRATEGY_CACHE.get(mode);
    if (strategy == null) {
      throw new IllegalArgumentException("Unsupported InsertionMode: " + mode);
    }

    return strategy;
  }

  public static InsertionStrategy getStrategy(InsertionMode mode, method.rkhma.legacy.model.Satellite satellite) {
    if (satellite != null && satellite.getInsertionStrategy() != null) return satellite.getInsertionStrategy();
    return getStrategy(mode);
  }
}
