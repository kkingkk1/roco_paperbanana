package method.rkhma.legacy.common;

/**
 * Centralized hard-coded default values for tunable parameters.
 * When no config file is specified, callers use these constants as fallback.
 * Any parameter can be overridden via TuningConfig with a config file.
 *
 * Dependencies: none
 */
public final class TuningDefaults {

    private TuningDefaults() {
        throw new UnsupportedOperationException("A utility class cannot be instantiated.");
    }

    // =====================================================
    // A. Run control
    // =====================================================

    public static final double ITERATION_COEFFICIENT = 100.0;

    // =====================================================
    // B. Reassignment loop control
    // =====================================================

    public static final int EVAL_MAX_REASSIGN_ITERATIONS = 25;
    public static final int EVAL_KEY_PERTURB_THRESHOLD = 1;
    public static final double EVAL_KEY_PERTURB_STRENGTH = 0.02;

    // =====================================================
    // C. Migration strategy
    // =====================================================

    public static final int COORD_REASSIGN_STAGNATION_THRESHOLD = 1;
    public static final int COORD_ZERO_MIGRATION_EARLY_STOP_THRESHOLD = 5;
    public static final int COORD_MAX_SECOND_CHANCES = 2;
    public static final double COORD_REASSIGN_MIGRATION_RATIO = 0.20;
    public static final int COORD_REASSIGN_MIGRATION_MAX_CAP = 12;

    // =====================================================
    // D. Candidate target satellite ranking
    // =====================================================

    public static final int COORD_REASSIGN_PRIORITY_TASK_TOP_K = 5;

    // =====================================================
    // E. k_task weights
    // =====================================================

    public static final double COORD_TASK_KEY_PRIORITY_WEIGHT = 0.55;
    public static final double COORD_TASK_KEY_FEASIBILITY_WEIGHT = 0.30;
    public static final double COORD_TASK_KEY_RANDOM_WEIGHT = 0.15;

    // =====================================================
    // E. k_sat weights
    // =====================================================

    public static final double COORD_TASK_SAT_WINDOW_COUNT_WEIGHT = 0.40;
    public static final double COORD_TASK_SAT_WINDOW_LENGTH_WEIGHT = 0.30;
    public static final double COORD_TASK_SAT_EARLIEST_START_WEIGHT = 0.20;
    public static final double COORD_TASK_SAT_RANDOM_WEIGHT = 0.10;

    // =====================================================
    // E. Intra-satellite sorting weights
    // =====================================================

    public static final double COORD_ORDERED_TASK_INITIAL_SATELLITE_BONUS = 1.50;
    public static final double COORD_ORDERED_TASK_KEY_WEIGHT = 1.00;
    public static final double COORD_ORDERED_TASK_SAT_KEY_WEIGHT = 0.65;
    public static final double COORD_ORDERED_TASK_INITIAL_ORDER_WEIGHT = 0.001;

    // =====================================================
    // E. High-value task sorting weights
    // =====================================================

    public static final double COORD_PRIORITIZED_PRIORITY_WEIGHT = 5.0;
    public static final double COORD_PRIORITIZED_CANDIDATE_WEIGHT = 2.0;
    public static final double COORD_PRIORITIZED_WINDOW_WEIGHT = 1.0;
    public static final double COORD_PRIORITIZED_TASK_KEY_WEIGHT = 3.0;
    public static final double COORD_FAILED_TASK_PRIORITY_BOOST = 0.25;

    // =====================================================
    // E. Fast Refine sorting weights
    // =====================================================

    public static final double COORD_FAST_REFINE_TASK_KEY_WEIGHT = 4.0;
    public static final double COORD_FAST_REFINE_SAT_KEY_WEIGHT = 2.5;
    public static final double COORD_FAST_REFINE_PRIORITY_WEIGHT = 3.0;
    public static final double COORD_FAST_REFINE_CONFLICT_WEIGHT = 2.0;

    // =====================================================
    // E. k_sat feedback
    // =====================================================

    public static final double COORD_TASK_SAT_SUCCESS_BOOST = 0.08;
    public static final double COORD_TASK_SAT_FAILURE_PENALTY = 0.01;
    public static final double COORD_BASELINE_TIE_BREAK_SCALE = 0.000001;

    // =====================================================
    // F. Warm start and budget
    // =====================================================

    public static final double COORD_LOCAL_REFINE_BUDGET_RATIO = 0.2;
    public static final double COORD_FULL_REFINE_BUDGET_RATIO = 0.5;
    public static final int COORD_FULL_REFINE_MIN_GENERATIONS = 20;
    public static final int COORD_LOCAL_REFINE_MIN_FES_PER_INDIVIDUAL = 5;

    // =====================================================
    // G. Delta Fast Refine
    // =====================================================

    public static final int COORD_DELTA_FAST_REFINE_MAX_CANDIDATE_POSITIONS = 4;
    public static final boolean COORD_DELTA_FAST_REFINE_WINDOW_PREFERENCE = true;
    public static final boolean COORD_FAST_REFINE_ADJACENT_SWAP_ENABLED = true;
    public static final int COORD_FAST_REFINE_SWAP_RADIUS = 5;

    // =====================================================
    // H. Replacement / Swap / Bundle
    // =====================================================

    public static final int COORD_REPLACEMENT_MAX_TRIALS_PER_SAT = 3;
    public static final int COORD_MAX_BATCH_REMOVAL = 3;
    public static final double COORD_REPLACEMENT_NEARBY_GAP_SECONDS = 60.0;
    public static final int COORD_SWAP_MAX_CANDIDATES_PER_SAT = 3;
    public static final int COORD_SWAP_MAX_RECEIVER_SATS = 2;
    public static final int COORD_BUNDLE_MAX_SIZE = 3;
    public static final double COORD_BUNDLE_TIME_PROXIMITY_THRESHOLD = 120.0;
    public static final int COORD_BUNDLE_MAX_ATTEMPTS_PER_ROUND = 5;
    public static final int COORD_INITIAL_ENSEMBLE_SIZE = 2;

    // =====================================================
    // I. TargetSatelliteRanker weights — folded pair score
    // =====================================================

    public static final double RANKER_CHEAP_PAIR_WINDOW_COUNT_WEIGHT = 0.346;
    public static final double RANKER_CHEAP_PAIR_WINDOW_LENGTH_WEIGHT = 0.274;
    public static final double RANKER_CHEAP_PAIR_EARLIEST_START_WEIGHT = 0.18;
    public static final double RANKER_CHEAP_PAIR_TASK_SAT_KEY_WEIGHT = 0.20;
    public static final int RANKER_ORDINARY_TOP_K = 2;
    public static final int RANKER_PRIORITIZED_TOP_K = 4;

    // =====================================================
    // J. CompositeStrategy assignment weights
    // =====================================================

    public static final double ASSIGN_COMPOSITE_WINDOW_LENGTH_WEIGHT = 0.37;
    public static final double ASSIGN_COMPOSITE_WINDOW_COUNT_WEIGHT = 0.20;
    public static final double ASSIGN_COMPOSITE_LOAD_BALANCE_WEIGHT = 0.25;
    public static final double ASSIGN_COMPOSITE_CONFLICT_WEIGHT = 0.25;
    public static final double ASSIGN_COMPOSITE_SCARCITY_BOOST = 0.5;
    public static final int ASSIGN_LOW_LOAD_FEASIBLE_THRESHOLD = 5;
}
