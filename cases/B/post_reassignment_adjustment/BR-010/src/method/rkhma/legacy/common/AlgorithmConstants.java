package method.rkhma.legacy.common;
/**
 * Algorithm Constants Class: Centralized management of all magic numbers used in algorithms.
 */
public final class AlgorithmConstants {

  /** Current chromosome length: parents, update ratios, operation order, survivor. */
  public static final int HYBRID_CONFIG_DIMENSION = 6;

  // =====================================================
  // Constant population size
  // =====================================================

  /** Population Iteration Factor */
  public static final int DEFAULT_POPULATION_Factor = 3;

  /** HybCec minimum population size */
  public static final int HYBCEC_MIN_POP_SIZE = 6;

  /** Default iteration coefficient (used for maxFes = popFactor * d * coefficient) */
  public static final double DEFAULT_ITERATION_COEFFICIENT = 70.0;

  // =====================================================
  // Search space boundary constants
  // =====================================================

  public static final double DEFAULT_BOUND = 750.0;

  // =====================================================
  // General Constants (Framework-Level)
  // =====================================================

  /** Probability threshold: 50% */
  public static final double PROBABILITY_HALF = 0.5;

  /** Probability threshold: 5% */
  public static final double PROBABILITY_5_PERCENT = 0.05;

  /** Epsilon: Tolerance value for floating-point comparison and numerical stability */
  public static final double EPSILON = 1e-9;

  /** Sliding-window size used by adaptive credit routing. */
  public static final int CREDIT_ASSIGNMENT_WINDOW = 8;

  /** Default OA1 routing ratio before adaptive credit collects enough evidence. */
  public static final double CREDIT_ROUTING_DEFAULT_RATIO = 0.5;

  /** HybCec dispersion threshold ((maxFit - minFit) / minFit < threshold when considered insufficient dispersion) */
  public static final double HYBCEC_DISPERSITY_THRESHOLD = 0.1;

  /** Normalized continuous-population diversity threshold for G4 diversity switching. */
  public static final double HYBCEC_NORMALIZED_DIVERSITY_THRESHOLD = 0.02;

  /** G4 switch interval as a fraction of maxItera. */
  public static final double OPERATION_ORDER_SWITCH_INTERVAL_RATIO = 0.05;

  /** Diversity check interval as a fraction of maxItera. */
  public static final double DIVERSITY_CHECK_INTERVAL_RATIO = 0.01;

  /** Relative fitness gap used to estimate how much of the population lies on a neutral plateau. */
  public static final double LANDSCAPE_NEUTRAL_RELATIVE_EPSILON = 5.0e-3;

  /** HybCec operation level random selection probability threshold */
  public static final double HYBCEC_RAND_OL = 0.4;

  /** Default maximum capacity of the alternative optimal sequence library (stores the top-K optimal candidates) */
  public static final int CANDIDATE_ARCHIVE_DEFAULT_MAX_SIZE = 10;

  /** Number of parent algorithms kept in the HybCec runtime library, including archived tail IDs 11..15. */
  public static final int HYBCEC_PARENT_ALGORITHM_COUNT = 16;

  /** Number of parent algorithms exposed to hybrid search and RunSingleHybridConfig: active IDs 0..10 only. */
  public static final int HYBCEC_ACTIVE_PARENT_ALGORITHM_COUNT = 11;

  /** Fixed random seed (used for repeatable experiments, set to null or comment out to use system time as seed) */
  public static final Long FIXED_RANDOM_SEED = 42L;

  public static final long FIXED_RANDOM_SEED_LONG = FIXED_RANDOM_SEED;

  // =====================================================
  // Update ratio / operation scope constants
  // =====================================================

  public static final int OPERATION_LEVEL_POPULATION = 0;

  public static final int OPERATION_LEVEL_SUBPOPULATION = 1;

  public static final int OPERATION_LEVEL_INDIVIDUAL = 2;

  public static final int OPERATION_LEVEL_COMPONENT = 3;

  /** Update policy code: update the full population. */
  public static final int UPDATE_RATIO_CODE_FULL = 0;

  /** Update policy code: fixed-size top-fitness subset. */
  public static final int UPDATE_RATIO_CODE_TOP_SUBSET = 1;

  /** Update policy code: fixed-size mixed top/random subset. */
  public static final int UPDATE_RATIO_CODE_MIXED_SUBSET = 2;

  /** Update policy code: random subset with a decaying internal ratio. */
  public static final int UPDATE_RATIO_CODE_RANDOM_DECAY = 3;

  /** Update policy code: select individuals whose fitness is above the population mean. */
  public static final int UPDATE_RATIO_CODE_ABOVE_MEAN = 4;

  /** Update policy code: local-search mode, update exactly one individual. */
  public static final int UPDATE_RATIO_CODE_LOCAL_SEARCH = 5;

  /** Update policy code: repair the current worst individual. */
  public static final int UPDATE_RATIO_CODE_WORST_REPAIR = 6;

  /** Number of supported update policies exposed to G2/G3. */
  public static final int UPDATE_RATIO_CODE_COUNT = 7;

  /** Compatibility aliases for older code names. Do not use in new documentation. */
  public static final int UPDATE_RATIO_CODE_TOP_DECAY = UPDATE_RATIO_CODE_TOP_SUBSET;
  public static final int UPDATE_RATIO_CODE_MIXED_DECAY = UPDATE_RATIO_CODE_MIXED_SUBSET;
  public static final int UPDATE_RATIO_CODE_DYNAMIC_MEAN = UPDATE_RATIO_CODE_ABOVE_MEAN;
  public static final int UPDATE_RATIO_CODE_70_PERCENT = UPDATE_RATIO_CODE_TOP_SUBSET;
  public static final int UPDATE_RATIO_CODE_50_PERCENT = UPDATE_RATIO_CODE_MIXED_SUBSET;
  public static final int UPDATE_RATIO_CODE_30_PERCENT = UPDATE_RATIO_CODE_ABOVE_MEAN;
  public static final int UPDATE_RATIO_CODE_15_PERCENT = UPDATE_RATIO_CODE_LOCAL_SEARCH;

  /** Fixed subset ratio used by top and mixed subset policies. */
  public static final double UPDATE_POLICY_SUBSET_RATIO = 0.5;

  /** Start ratio used by the random-decay update policy. */
  public static final double UPDATE_POLICY_DECAY_START_RATIO = 0.5;

  /** Linear decay factor used by the random-decay update policy. */
  public static final double UPDATE_POLICY_DECAY_END_FACTOR = 0.2;

  /** Top-fitness share used by the mixed-subset update policy. */
  public static final double UPDATE_POLICY_MIXED_TOP_RATIO = 0.8;

  // =====================================================
  // HybCec algorithm index constants
  // =====================================================

  /** GA algorithm index */
  public static final int ALGORITHM_INDEX_GA = 0;

  /** PSO algorithm index */
  public static final int ALGORITHM_INDEX_PSO = 1;

  /** EDA algorithm index */
  public static final int ALGORITHM_INDEX_EDA = 2;

  /** ACO algorithm index */
  public static final int ALGORITHM_INDEX_ACO = 3;

  /** DE/rand/1 algorithm index */
  public static final int ALGORITHM_INDEX_DE_RAND_1 = 4;

  /** DE/best/1 algorithm index */
  public static final int ALGORITHM_INDEX_DE_BEST_1 = 5;

  /** DE/current-to-rand/1 algorithm index */
  public static final int ALGORITHM_INDEX_DE_CURRENT_TO_RAND = 6;

  /** ABC algorithm index */
  public static final int ALGORITHM_INDEX_ABC = 7;

  /** SA algorithm index */
  public static final int ALGORITHM_INDEX_SA = 8;

  /** L-SHADE algorithm index */
  public static final int ALGORITHM_INDEX_LSHADE = 9;

  /** Adaptive Large Neighborhood Search algorithm index */
  public static final int ALGORITHM_INDEX_ALNS = 10;

  /** DE/rand/2 algorithm index (moved to deprecated tail area of the library) */
  public static final int ALGORITHM_INDEX_DE_RAND_2 = 11;

  /** DE/best/2 algorithm index (moved to deprecated tail area of the library) */
  public static final int ALGORITHM_INDEX_DE_BEST_2 = 12;

  /** DE/current-to-best/1 algorithm index (moved to deprecated tail area of the library) */
  public static final int ALGORITHM_INDEX_DE_CURRENT_TO_BEST = 13;

  /** DE/rand-to-best/1 algorithm index (moved to deprecated tail area of the library) */
  public static final int ALGORITHM_INDEX_DE_RAND_TO_BEST = 14;

  /** SHADE algorithm index (moved to deprecated tail area of the library) */
  public static final int ALGORITHM_INDEX_SHADE = 15;

  // =====================================================
  // Insertion Strategy Constants
  // =====================================================

  /** Insertion Strategy: Greedy */
  public static final int INSERTION_STRATEGY_GREEDY = 0;

  /** Insertion Strategy: Min Transition */
  public static final int INSERTION_STRATEGY_MIN_TRANSITION = 1;

  /** Insertion Strategy: Max Slack */
  public static final int INSERTION_STRATEGY_MAX_SLACK = 2;

  /** Insertion Strategy: Shortest Window First */
  public static final int INSERTION_STRATEGY_SHORTEST_WINDOW_FIRST = 3;

  /** Insertion Strategy: Earliest Deadline First */
  public static final int INSERTION_STRATEGY_EARLIEST_DEADLINE_FIRST = 4;

  /** Insertion Strategy: Random */
  public static final int INSERTION_STRATEGY_RANDOM = 5;

  /** Insertion Strategy: Longest Window First */
  public static final int INSERTION_STRATEGY_LONGEST_WINDOW_FIRST = 6;

  /** Insertion Strategy: Max Remaining Capacity */
  public static final int INSERTION_STRATEGY_MAX_REMAINING_CAPACITY = 7;

  /** Number of supported insertion strategies. */
  public static final int INSERTION_STRATEGY_COUNT = 8;

  // =====================================================
  // Operation Order Type Constants
  // =====================================================

  /** Operation Order: run OA1 then OA2 each generation. */
  public static final int OPERATION_ORDER_SEQUENTIAL = 0;

  /** Operation Order: switch periodically by iteration budget. */
  public static final int OPERATION_ORDER_PERIODIC_SWITCH = 1;

  /** Operation Order: early OA1 and late OA2. */
  public static final int OPERATION_ORDER_STAGE_SWITCH = 2;

  /** Operation Order: switch when the historical best solution stops improving. */
  public static final int OPERATION_ORDER_BEST_NO_IMPROVEMENT_SWITCH = 3;

  /** Operation Order: switch when the current search process stops improving. */
  public static final int OPERATION_ORDER_PROCESS_NO_IMPROVEMENT_SWITCH = 4;

  /** Operation Order: switch when normalized population diversity is low. */
  public static final int OPERATION_ORDER_DIVERSITY_SWITCH = 5;

  /** Operation Order: combined candidate generation and merged survivor selection. */
  public static final int OPERATION_ORDER_PARALLEL = 6;

  /** Operation Order: credit-routed adaptive serial execution. */
  public static final int OPERATION_ORDER_ADAPTIVE_CREDIT = 7;

  /** Number of supported operation-order strategies exposed to G4. */
  public static final int OPERATION_ORDER_COUNT = 8;

  /** Compatibility aliases for older operation-order names. */
  public static final int OPERATION_ORDER_MULTI_STAGE = OPERATION_ORDER_STAGE_SWITCH;
  public static final int OPERATION_ORDER_SWITCH_AFTER_STAGNATION =
    OPERATION_ORDER_BEST_NO_IMPROVEMENT_SWITCH;
  public static final int OPERATION_ORDER_COMBINED = OPERATION_ORDER_PARALLEL;

  /** Operation Order runtime mode: serial */
  public static final int OPERATION_ORDER_REAL_SERIAL = 0;

  /** Operation Order runtime mode: combined/parallel survivor preparation */
  public static final int OPERATION_ORDER_REAL_PARALLEL = 1;

  /** Operation Order runtime mode: adaptive credit-routed serial execution */
  public static final int OPERATION_ORDER_REAL_ADAPTIVE = 2;

  // =====================================================
  // HybCec Hybrid-Level Constants
  // =====================================================

  public static final int HYB_LEVEL_POPULATION = 0;

  public static final int HYB_LEVEL_SUBPOPULATION = 1;

  public static final int HYB_LEVEL_INDIVIDUAL = 2;
  public static final int HYB_LEVEL_COMPONENT = 3;

  // =====================================================
  // Survival Selection Strategy Constants
  // =====================================================

  /** Survival Selection Strategy: elitist best-N selection from merged parents and offspring. */
  public static final int SURVIVOR_SELECT_STRATEGY_ELITIST_BEST_N = 0;

  /** Survival Selection Strategy: tournament selection from merged parents and offspring. */
  public static final int SURVIVOR_SELECT_STRATEGY_TOURNAMENT = 1;

  /** Survival Selection Strategy: pairwise greedy offspring-vs-parent replacement. */
  public static final int SURVIVOR_SELECT_STRATEGY_PAIRWISE_GREEDY = 2;

  /** Survival Selection Strategy: rank-weighted stochastic selection from merged parents and offspring. */
  public static final int SURVIVOR_SELECT_STRATEGY_RANK_WEIGHTED = 3;

  /** Number of pure survivor-selection strategies exposed to G5. */
  public static final int SURVIVOR_SELECT_STRATEGY_COUNT = 4;

  /** Compatibility aliases for older survivor-strategy names. Do not use in new documentation. */
  public static final int SURVIVOR_SELECT_STRATEGY_PERMUTATION =
    SURVIVOR_SELECT_STRATEGY_ELITIST_BEST_N;
  public static final int SURVIVOR_SELECT_STRATEGY_CHAMPION =
    SURVIVOR_SELECT_STRATEGY_TOURNAMENT;
  public static final int SURVIVOR_SELECT_STRATEGY_SERIAL_NUMBER =
    SURVIVOR_SELECT_STRATEGY_PAIRWISE_GREEDY;
  public static final int SURVIVOR_SELECT_STRATEGY_RANK_BASED =
    SURVIVOR_SELECT_STRATEGY_RANK_WEIGHTED;

  /** Default tournament size for champion selection */
  public static final int DEFAULT_TOURNAMENT_SIZE = 2;

  private AlgorithmConstants() {}

  /**
   * Map an update-ratio gene to the coarse update scope used at runtime.
   * Code 0 starts from full-population update, the rest are individual-subset modes.
   */
  public static int getUpdateRatioOperationLevel(int updateRatioCode) {
    int normalizedCode = normalizeUpdatePolicyCode(updateRatioCode);
    if (normalizedCode < 0 || normalizedCode >= UPDATE_RATIO_CODE_COUNT) {
      throw new IllegalArgumentException("Invalid updateRatioCode: " + updateRatioCode);
    }
    return normalizedCode == UPDATE_RATIO_CODE_FULL
      ? OPERATION_LEVEL_POPULATION
      : OPERATION_LEVEL_INDIVIDUAL;
  }

  /**
   * Validate the current 7-policy G2/G3 update-policy space.
   */
  public static int normalizeUpdatePolicyCode(int updatePolicyCode) {
    switch (updatePolicyCode) {
      case UPDATE_RATIO_CODE_FULL:
      case UPDATE_RATIO_CODE_TOP_SUBSET:
      case UPDATE_RATIO_CODE_MIXED_SUBSET:
      case UPDATE_RATIO_CODE_RANDOM_DECAY:
      case UPDATE_RATIO_CODE_ABOVE_MEAN:
      case UPDATE_RATIO_CODE_LOCAL_SEARCH:
      case UPDATE_RATIO_CODE_WORST_REPAIR:
        return updatePolicyCode;
      default:
        throw new IllegalArgumentException("Invalid updateRatioCode: " + updatePolicyCode);
    }
  }

  /**
   * Map a G4 operation-order code to the runtime execution mode used by HybCec.
   */
  public static int getOperationOrderMode(int operationOrderCode) {
    switch (operationOrderCode) {
      case OPERATION_ORDER_SEQUENTIAL:
      case OPERATION_ORDER_PERIODIC_SWITCH:
      case OPERATION_ORDER_STAGE_SWITCH:
      case OPERATION_ORDER_BEST_NO_IMPROVEMENT_SWITCH:
      case OPERATION_ORDER_PROCESS_NO_IMPROVEMENT_SWITCH:
      case OPERATION_ORDER_DIVERSITY_SWITCH:
        return OPERATION_ORDER_REAL_SERIAL;
      case OPERATION_ORDER_PARALLEL:
        return OPERATION_ORDER_REAL_PARALLEL;
      case OPERATION_ORDER_ADAPTIVE_CREDIT:
        return OPERATION_ORDER_REAL_ADAPTIVE;
      default:
        throw new IllegalArgumentException("Invalid operationOrderCode: " + operationOrderCode);
    }
  }

  public static int[] createOperationOrderFlags() {
    return new int[] {
      OPERATION_ORDER_REAL_SERIAL,
      OPERATION_ORDER_REAL_SERIAL,
      OPERATION_ORDER_REAL_SERIAL,
      OPERATION_ORDER_REAL_SERIAL,
      OPERATION_ORDER_REAL_SERIAL,
      OPERATION_ORDER_REAL_SERIAL,
      OPERATION_ORDER_REAL_PARALLEL,
      OPERATION_ORDER_REAL_ADAPTIVE
    };
  }

  public static boolean isDeParentAlgorithm(int parentAlgorithmIndex) {
    switch (parentAlgorithmIndex) {
      case ALGORITHM_INDEX_DE_RAND_1:
      case ALGORITHM_INDEX_DE_BEST_1:
      case ALGORITHM_INDEX_DE_CURRENT_TO_RAND:
      case ALGORITHM_INDEX_LSHADE:
      case ALGORITHM_INDEX_DE_RAND_2:
      case ALGORITHM_INDEX_DE_BEST_2:
      case ALGORITHM_INDEX_DE_CURRENT_TO_BEST:
      case ALGORITHM_INDEX_DE_RAND_TO_BEST:
      case ALGORITHM_INDEX_SHADE:
        return true;
      default:
        return false;
    }
  }

  public static int getBaseSurvivorSelectStrategy(int survivorStrategyCode) {
    switch (survivorStrategyCode) {
      case SURVIVOR_SELECT_STRATEGY_ELITIST_BEST_N:
        return SURVIVOR_SELECT_STRATEGY_ELITIST_BEST_N;
      case SURVIVOR_SELECT_STRATEGY_TOURNAMENT:
        return SURVIVOR_SELECT_STRATEGY_TOURNAMENT;
      case SURVIVOR_SELECT_STRATEGY_PAIRWISE_GREEDY:
        return SURVIVOR_SELECT_STRATEGY_PAIRWISE_GREEDY;
      case SURVIVOR_SELECT_STRATEGY_RANK_WEIGHTED:
        return SURVIVOR_SELECT_STRATEGY_RANK_WEIGHTED;
      default:
        throw new IllegalArgumentException("Invalid survivorStrategyCode: " + survivorStrategyCode);
    }
  }

  public static int[] normalizeHybridConfig(int[] config) {
    if (config == null) {
      return null;
    }
    if (config.length != HYBRID_CONFIG_DIMENSION) {
      throw new IllegalArgumentException(
        "Hybrid config must contain 6 genes, got: " + config.length
      );
    }
    int[] normalized = config.clone();
    normalized[2] = normalizeUpdatePolicyCode(normalized[2]);
    normalized[3] = normalizeUpdatePolicyCode(normalized[3]);
    return normalized;
  }
}
