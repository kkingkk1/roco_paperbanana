package method.rkhma.legacy.main;

import method.rkhma.legacy.common.SearchDeadline;
import method.rkhma.legacy.model.*;
import method.rkhma.legacy.core.HybCec;
import method.rkhma.legacy.utils.*;
import method.rkhma.legacy.common.*;
import method.rkhma.legacy.operator.assignment.*;
import method.rkhma.legacy.operator.insertion.InsertionMode;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
public class TaskAllocationCoordinator {
  public record PopulationContext(Satellite satellite, List<Task> tasks, List<double[]> population,
      long seed, Random random, int maxFes, boolean commitState, SearchDeadline deadline,
      SchedulingPlan anchorPlan, java.util.function.ToDoubleFunction<double[]> evaluate) { }

  public record PopulationResult(HybCec.HybResult result, SchedulingPlan plan) { }

  @FunctionalInterface public interface PopulationSearch {
    PopulationResult search(PopulationContext context, HybCec original) throws Exception;
  }

  public record RefinementContext(Satellite satellite, List<Task> previousTasks, List<Task> tasks,
      SchedulingPlan anchorPlan, InsertionMode mode, int iteration) { }

  @FunctionalInterface public interface LocalRefinement {
    SchedulingPlan refine(RefinementContext context, java.util.function.Supplier<SchedulingPlan> original);
  }

  public record MoveContext(Map<Integer, Integer> owners, Map<Integer, SchedulingPlan> plans,
      int attempt, String previousOutcome) { }

  public record PolicyMove(Map<Integer, Integer> edits, Set<Integer> repairSatellites, boolean fullRecalibration) { }

  /** 一次A策略调用内由协调器签发的完整候选。 */
  public record PolicyTrial(PolicyMove move, Map<Integer, Integer> owners,
                            Map<Integer, SchedulingPlan> plans, Double profit) { }

  @FunctionalInterface public interface PolicyEvaluator {
    /** base为null表示当前接受状态，否则必须为本次调用签发的可行候选。 */
    PolicyTrial evaluate(PolicyTrial base, PolicyMove move) throws Exception;
    default void checkpoint(PolicyTrial trial) { }
  }

  @FunctionalInterface public interface AssignmentPolicy {
    PolicyTrial search(MoveContext context, PolicyEvaluator evaluator) throws Exception;
    default void onAccepted(PolicyTrial trial) { }
    default boolean acceptsNonImprovingResult() { return false; }
  }

  @FunctionalInterface public interface UnifiedCandidateEvaluator {
    Map<Integer, SchedulingPlan> evaluate(Map<Integer, Integer> before, Map<Integer, Integer> after,
        Set<Integer> affected, Map<Integer, SchedulingPlan> basePlans) throws Exception;
  }

  public record UnifiedReassignment(Map<Integer, Integer> owners, Map<Integer, SchedulingPlan> plans) { }

  @FunctionalInterface public interface UnifiedCalibrationEvaluator {
    Map<Integer, SchedulingPlan> evaluate(Map<Integer, Integer> owners, Map<Integer, SchedulingPlan> currentPlans)
        throws Exception;
  }

  private UnifiedCalibrationEvaluator unifiedCalibrationEvaluator;
  private java.util.function.BiConsumer<Map<Integer, Integer>, Map<Integer, SchedulingPlan>> unifiedCheckpoint;
  private Map<Integer, SatelliteState> unifiedCalibrationSnapshot;
  private java.util.function.BiConsumer<Map<Integer, Integer>, Map<Integer, SchedulingPlan>> calibrationStart;

  public void setCalibrationStart(
      java.util.function.BiConsumer<Map<Integer, Integer>, Map<Integer, SchedulingPlan>> start) {
    calibrationStart = Objects.requireNonNull(start);
  }

  private AssignmentPolicy assignmentPolicy;
  private java.util.function.Predicate<Map<Integer, SchedulingPlan>> planValidator = plans -> true;

  public void setAssignmentPolicy(AssignmentPolicy policy) {
    assignmentPolicy = Objects.requireNonNull(policy);
  }

  public void useRkhmaReassignment() { assignmentPolicy = null; }

  public void setPlanValidator(java.util.function.Predicate<Map<Integer, SchedulingPlan>> validator) {
    planValidator = Objects.requireNonNull(validator);
  }

  private PopulationSearch populationSearch;
  private volatile Map<Integer, SchedulingPlan> currentPlanViews = Collections.emptyMap();

  public void setPopulationSearch(PopulationSearch search) {
    populationSearch = Objects.requireNonNull(search);
  }

  public void useRkhmaPopulationSearch() { populationSearch = null; }

  public void setLocalRefinement(LocalRefinement refinement) {
    deltaFastRefiner.setLocalRefinement(Objects.requireNonNull(refinement));
  }

  public void useRkhmaLocalRefinement() { deltaFastRefiner.setLocalRefinement(null); }

  /** Optional fail-closed research instrumentation; production default is disabled. */
  @FunctionalInterface public interface ResearchBudgetObserver {
    void observe(int satelliteId, int ensembleIndex, int population, int maxFes, long seed, boolean commitState);
  }
  private static volatile ResearchBudgetObserver researchBudgetObserver;
  public static void setResearchBudgetObserver(ResearchBudgetObserver observer) { researchBudgetObserver = observer; }
  static void observeResearchBudget(int satelliteId, int ensembleIndex, int population, int maxFes, long seed, boolean commitState) {
    ResearchBudgetObserver observer = researchBudgetObserver;
    if (observer != null) observer.observe(satelliteId, ensembleIndex, population, maxFes, seed, commitState);
  }
  /** Dedicated ForkJoinPool for satellite-level parallelism; avoids nesting with HybCec's common pool */
  private static final ForkJoinPool SATELLITE_POOL = new ForkJoinPool(
      resolveSatellitePoolSize(),
      pool -> {
          ForkJoinWorkerThread t = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
          t.setDaemon(true);
          t.setName("satellite-pool-" + t.getPoolIndex());
          return t;
      },
      null, false);
  private static final long GLOBAL_KEY_SEED_SALT = 0x9E3779B97F4A7C15L;
  private static final long DEFAULT_ASSIGNMENT_SEED_SALT = 0xC2B2AE3D27D4EB4FL;
  private static final int REASSIGN_STAGNATION_THRESHOLD = TuningConfig.getInt("coord.reassignStagnationThreshold", TuningDefaults.COORD_REASSIGN_STAGNATION_THRESHOLD);
  private static final int ZERO_MIGRATION_EARLY_STOP_THRESHOLD = TuningConfig.getInt("coord.zeroMigrationEarlyStopThreshold", TuningDefaults.COORD_ZERO_MIGRATION_EARLY_STOP_THRESHOLD);
  private static final int MAX_SECOND_CHANCES = TuningConfig.getInt("coord.maxSecondChances", TuningDefaults.COORD_MAX_SECOND_CHANCES);
  private static final int REASSIGN_PRIORITY_TASK_TOP_K = TuningConfig.getInt("coord.reassignPriorityTaskTopK", TuningDefaults.COORD_REASSIGN_PRIORITY_TASK_TOP_K);
  private static final double REASSIGN_MIGRATION_RATIO = TuningConfig.getDouble("coord.reassignMigrationRatio", TuningDefaults.COORD_REASSIGN_MIGRATION_RATIO);
  private static final int REASSIGN_MIGRATION_MAX_CAP = TuningConfig.getInt("coord.reassignMigrationMaxCap", TuningDefaults.COORD_REASSIGN_MIGRATION_MAX_CAP);
  /** Number of mini-ensemble runs per satellite during initial full evaluation (active when no inherited state) */
  private static final int CONFIGURED_INITIAL_ENSEMBLE_SIZE = TuningConfig.getInt("coord.initialEnsembleSize", TuningDefaults.COORD_INITIAL_ENSEMBLE_SIZE);
  private static final String ADAPTIVE_ENSEMBLE_PROPERTY = "autohyb.coord.adaptiveEnsemble";
  private static final double ADAPTIVE_ENSEMBLE_TOP_RATIO = 0.35;
  /** Seed offset between different mini-ensemble runs */
  private static final long ENSEMBLE_SEED_OFFSET = 0x6C62272E07BB0142L;
  private static final double BASELINE_TIE_BREAK_SCALE = TuningConfig.getDouble("coord.baselineTieBreakScale", TuningDefaults.COORD_BASELINE_TIE_BREAK_SCALE);
  private static final int CROSS_SATELLITE_TEMPLATE_SLOT = 3;
  private static final int CROSS_SATELLITE_TEMPLATE_MIN_SHARED_TASKS = 2;
  private static final double TASK_KEY_PRIORITY_WEIGHT = TuningConfig.getDouble("coord.taskKeyPriorityWeight", TuningDefaults.COORD_TASK_KEY_PRIORITY_WEIGHT);
  private static final double TASK_KEY_FEASIBILITY_WEIGHT = TuningConfig.getDouble("coord.taskKeyFeasibilityWeight", TuningDefaults.COORD_TASK_KEY_FEASIBILITY_WEIGHT);
  private static final double TASK_KEY_RANDOM_WEIGHT = TuningConfig.getDouble("coord.taskKeyRandomWeight", TuningDefaults.COORD_TASK_KEY_RANDOM_WEIGHT);
  private static final double TASK_SAT_WINDOW_COUNT_WEIGHT = TuningConfig.getDouble("coord.taskSatWindowCountWeight", TuningDefaults.COORD_TASK_SAT_WINDOW_COUNT_WEIGHT);
  private static final double TASK_SAT_WINDOW_LENGTH_WEIGHT = TuningConfig.getDouble("coord.taskSatWindowLengthWeight", TuningDefaults.COORD_TASK_SAT_WINDOW_LENGTH_WEIGHT);
  private static final double TASK_SAT_EARLIEST_START_WEIGHT = TuningConfig.getDouble("coord.taskSatEarliestStartWeight", TuningDefaults.COORD_TASK_SAT_EARLIEST_START_WEIGHT);
  private static final double TASK_SAT_RANDOM_WEIGHT = TuningConfig.getDouble("coord.taskSatRandomWeight", TuningDefaults.COORD_TASK_SAT_RANDOM_WEIGHT);
  private static final double ORDERED_TASK_INITIAL_SATELLITE_BONUS = TuningConfig.getDouble("coord.orderedTaskInitialSatelliteBonus", TuningDefaults.COORD_ORDERED_TASK_INITIAL_SATELLITE_BONUS);
  private static final double ORDERED_TASK_KEY_WEIGHT = TuningConfig.getDouble("coord.orderedTaskKeyWeight", TuningDefaults.COORD_ORDERED_TASK_KEY_WEIGHT);
  private static final double ORDERED_TASK_SAT_KEY_WEIGHT = TuningConfig.getDouble("coord.orderedTaskSatKeyWeight", TuningDefaults.COORD_ORDERED_TASK_SAT_KEY_WEIGHT);
  private static final double ORDERED_TASK_INITIAL_ORDER_WEIGHT = TuningConfig.getDouble("coord.orderedTaskInitialOrderWeight", TuningDefaults.COORD_ORDERED_TASK_INITIAL_ORDER_WEIGHT);
  private static final double FAILED_TASK_PRIORITY_BOOST = TuningConfig.getDouble("coord.failedTaskPriorityBoost", TuningDefaults.COORD_FAILED_TASK_PRIORITY_BOOST);
  private static final double TASK_SAT_SUCCESS_BOOST = TuningConfig.getDouble("coord.taskSatSuccessBoost", TuningDefaults.COORD_TASK_SAT_SUCCESS_BOOST);
  private static final double TASK_SAT_FAILURE_PENALTY = TuningConfig.getDouble("coord.taskSatFailurePenalty", TuningDefaults.COORD_TASK_SAT_FAILURE_PENALTY);
  private static final String LOCAL_REFINE_HOT_START_BUDGET_PROPERTY = "taskcoord.localRefineHotStartBudget.enabled";
  private static final double LOCAL_REFINE_BUDGET_RATIO = TuningConfig.getDouble("coord.localRefineBudgetRatio", TuningDefaults.COORD_LOCAL_REFINE_BUDGET_RATIO);
  /** Budget reduction ratio for full evaluation with inherited state (relative to maxFes) */
  private static final double FULL_REFINE_BUDGET_RATIO = TuningConfig.getDouble("coord.fullRefineBudgetRatio", TuningDefaults.COORD_FULL_REFINE_BUDGET_RATIO);
  /** Minimum guaranteed generations for full budget reduction */
  private static final int FULL_REFINE_MIN_GENERATIONS = TuningConfig.getInt("coord.fullRefineMinGenerations", TuningDefaults.COORD_FULL_REFINE_MIN_GENERATIONS);
  private static final int LOCAL_REFINE_MIN_FES_PER_INDIVIDUAL = TuningConfig.getInt("coord.localRefineMinFesPerIndividual", TuningDefaults.COORD_LOCAL_REFINE_MIN_FES_PER_INDIVIDUAL);
  private static final String CALIBRATION_RACING_PROPERTY = "autohyb.coord.calibrationRacing";
  private static final double CALIBRATION_RACING_MIN_GAIN = 0.0;
  private static final String SATELLITE_STATE_POOL_PROPERTY = "autohyb.satState.pool";
  private static final String STANDARD_MOVE_KEY = "standard";
  private static final String REPLACEMENT_MOVE_KEY = "replacement";
  private static final String SWAP_MOVE_KEY = "swap";
  private static final String BUNDLE_MOVE_KEY = "bundle";
  public static final String INIT_KNN_CACHE_PROPERTY = "autohyb.init.knnCache";
  public static final String SATELLITE_POOL_SIZE_PROPERTY = "autohyb.parallel.satellitePoolSize";
  private final Map<Integer, Double> globalTaskKeys;
  private final Map<Integer, Map<Integer, Double>> taskSatelliteBaseScores;
  private final Map<Integer, Map<Integer, Double>> taskSatelliteFeedbackScores;
  private final Map<Integer, SatelliteState> satelliteStates;
  private final Map<Integer, Integer> taskAssignment;
  private final Map<Integer, List<Task>> satelliteTaskCache;
  private Map<Integer, Integer> cachedAssignmentRef;
  private final Map<Integer, List<Task>> satelliteTaskCachePrev;
  private Map<Integer, Integer> cachedAssignmentRefPrev;
  private final Map<Integer, Integer> bestTaskAssignmentSnapshot;
  private final Map<Integer, Integer> initialTaskAssignmentSnapshot;
  private final Map<Integer, Integer> initialTaskOrderById;
  private final int maxTaskPriority;
  private final Map<Integer, Double> cachedInitialOrderScores;
  private final Map<Integer, Integer> failureCount;
  private final List<Satellite> satellites;
  private final List<Task> allTasks;
  private final AssignmentStrategy reassignmentStrategy;
  private final Map<String, AssignmentStrategy> strategyPool;
  private final List<String> strategyHistory;
  private final List<Integer> unscheduledTaskCountHistory;
  private final List<Integer> migrationCountHistory;
  private final List<Double> totalProfitHistory;
  private final Map<String, Integer> acceptedReassignmentMoveCounts;
  private final Map<String, Double> acceptedReassignmentMoveGains;
  private final List<ConvergenceTrace> convergenceTraces;
  private int convergenceTraceCounter;
  private final int maxReassignIterations;
  private final int keyPerturbThreshold;
  private final double keyPerturbStrength;
  private final boolean isMultiSatellite;
  private final TargetSatelliteRanker targetSatelliteRanker;
  private final TaskSatelliteKnnCache taskSatelliteKnnCache;
  private final TargetSatelliteSelector targetSatelliteSelector;
  private final DeltaFastRefiner deltaFastRefiner;
  private final AssignmentDeltaEvaluator assignmentDeltaEvaluator;
  private final ReassignmentMoveEvaluator reassignmentMoveEvaluator;
  private final ReassignmentOrchestrator reassignmentOrchestrator;
  private Random keyRandom;
  private int currentIteration;

  private static int resolveSatellitePoolSize() {
    int totalCores = Runtime.getRuntime().availableProcessors();
    Integer configured = Integer.getInteger(SATELLITE_POOL_SIZE_PROPERTY);
    if (configured == null) {
      return Math.max(2, totalCores);
    }
    int requested = Math.max(1, configured);
    int threadBudget = Math.max(1, (int) Math.floor(totalCores * 1.5));
    int reservedHybThreads = ParallelPolicy.hybThreadsForSingleRun(totalCores, 2);
    int reservedEvalThreads = ParallelPolicy.configuredLevel() == ParallelPolicy.Level.RUN ? 1 : 0;
    int maxSatelliteThreads = Math.max(1, threadBudget - reservedHybThreads - reservedEvalThreads);
    return Math.min(requested, maxSatelliteThreads);
  }

  private static int mapCapacity(int expectedSize) {
    return Math.max(16, (int) (expectedSize / 0.75f) + 1);
  }

  private <V> HashMap<Integer, V> newTaskIdMap() {
    return new HashMap<>(mapCapacity(allTasks.size()));
  }

  private <V> HashMap<Integer, V> newSatelliteIdMap() {
    return new HashMap<>(mapCapacity(satellites.size()));
  }

  private HashMap<Integer, Integer> newAssignmentSnapshot() {
    return newTaskIdMap();
  }

  public TaskAllocationCoordinator(List<Task> allTasks, List<Satellite> satellites, AssignmentStrategy reassignmentStrategy, int maxReassignIterations, int keyPerturbThreshold, double keyPerturbStrength) {
    this.allTasks = allTasks;
    this.satellites = satellites;
    this.reassignmentStrategy = reassignmentStrategy;
    this.maxReassignIterations = maxReassignIterations;
    this.keyPerturbThreshold = keyPerturbThreshold;
    this.keyPerturbStrength = keyPerturbStrength;
    this.isMultiSatellite = (satellites.size() > 1);
    this.globalTaskKeys = newTaskIdMap();
    this.taskSatelliteBaseScores = newTaskIdMap();
    this.taskSatelliteFeedbackScores = newTaskIdMap();
    this.satelliteStates = new ConcurrentHashMap<>();
    this.taskAssignment = newAssignmentSnapshot();
    this.satelliteTaskCache = newSatelliteIdMap();
    this.satelliteTaskCachePrev = newSatelliteIdMap();
    this.bestTaskAssignmentSnapshot = newAssignmentSnapshot();
    this.initialTaskAssignmentSnapshot = newAssignmentSnapshot();
    this.initialTaskOrderById = newTaskIdMap();
    this.failureCount = newTaskIdMap();
    this.strategyPool = new LinkedHashMap<>();
    AssignmentStrategy defaultStrategy = reassignmentStrategy != null ? reassignmentStrategy : new MaxWindowCountStrategy();
    this.strategyPool.put(defaultStrategy.getName(), defaultStrategy);
    this.strategyPool.put("MaxWindowLength", new MaxWindowLengthStrategy());
    this.strategyPool.put("MinTaskCount", new MinTaskCountStrategy());
    this.strategyPool.put("Random", new RandomStrategy(RandomProvider.newWithSeed(AlgorithmConstants.FIXED_RANDOM_SEED_LONG)));
    this.strategyHistory = new ArrayList<>();
    this.unscheduledTaskCountHistory = new ArrayList<>();
    this.migrationCountHistory = new ArrayList<>();
    this.totalProfitHistory = new ArrayList<>();
    this.acceptedReassignmentMoveCounts = new LinkedHashMap<>();
    this.acceptedReassignmentMoveGains = new LinkedHashMap<>();
    resetAcceptedReassignmentMoveStats();
    this.convergenceTraces = Collections.synchronizedList(new ArrayList<>());
    this.convergenceTraceCounter = 0;
    this.targetSatelliteRanker = new TargetSatelliteRanker();
    this.taskSatelliteKnnCache = Boolean.getBoolean(INIT_KNN_CACHE_PROPERTY)
      ? new TaskSatelliteKnnCache(allTasks, satellites)
      : null;
    this.targetSatelliteSelector = createTargetSatelliteSelector();
    this.deltaFastRefiner = createDeltaFastRefiner();
    this.assignmentDeltaEvaluator = createAssignmentDeltaEvaluator();
    this.reassignmentMoveEvaluator = createReassignmentMoveEvaluator();
    this.reassignmentOrchestrator = createReassignmentOrchestrator();
    this.keyRandom = RandomProvider.get();
    this.maxTaskPriority = allTasks.stream().mapToInt(Task::getPriority).max().orElse(1);
    this.cachedInitialOrderScores = newTaskIdMap();
  }

  private TargetSatelliteSelector createTargetSatelliteSelector() {
    return new TargetSatelliteSelector(
      satellites,
      targetSatelliteRanker,
      createTargetSatelliteSelectorContext()
    );
  }

  private TargetSatelliteSelector.Context createTargetSatelliteSelectorContext() {
    return new TargetSatelliteSelector.Context() {
      @Override
      public double getTaskSatelliteKeyOrDefault(int taskId, int satelliteId) {
        return TaskAllocationCoordinator.this.getTaskSatelliteKeyOrDefault(taskId, satelliteId);
      }

      @Override
      public int countCandidateSatellites(Task task) {
        return TaskAllocationCoordinator.this.countCandidateSatellites(task);
      }

      @Override
      public int countTotalWindows(Task task) {
        return TaskAllocationCoordinator.this.countTotalWindows(task);
      }

      @Override
      public int getMaxTaskPriority() {
        return TaskAllocationCoordinator.this.maxTaskPriority;
      }

      @Override
      public int getKeyPerturbThreshold() {
        return TaskAllocationCoordinator.this.keyPerturbThreshold;
      }

      @Override
      public Map<Integer, Integer> getFailureCount() {
        return TaskAllocationCoordinator.this.failureCount;
      }

      @Override
      public TaskSatelliteKnnCache.Feature getTaskSatelliteFeature(Task task, int satelliteId) {
        return TaskAllocationCoordinator.this.getTaskSatelliteFeature(task, satelliteId);
      }
    };
  }

  private TaskSatelliteKnnCache.Feature getTaskSatelliteFeature(Task task, int satelliteId) {
    return taskSatelliteKnnCache == null || task == null
      ? null
      : taskSatelliteKnnCache.get(task.getId(), satelliteId);
  }

  private DeltaFastRefiner createDeltaFastRefiner() {
    return new DeltaFastRefiner(createDeltaFastRefinerContext());
  }

  private DeltaFastRefiner.Context createDeltaFastRefinerContext() {
    return new DeltaFastRefiner.Context() {
      @Override
      public double computeFastRefineRepairScore(Task task, int satelliteId) {
        return TaskAllocationCoordinator.this.computeFastRefineRepairScore(task, satelliteId);
      }

      @Override
      public void materializePlanSchedule(SchedulingPlan plan, Satellite satellite, InsertionMode mode) {
        TaskAllocationCoordinator.this.materializePlanSchedule(plan, satellite, mode);
      }
    };
  }

  private AssignmentDeltaEvaluator createAssignmentDeltaEvaluator() {
    return new AssignmentDeltaEvaluator(
      allTasks,
      satellites,
      satelliteStates,
      deltaFastRefiner,
      createAssignmentDeltaEvaluatorContext()
    );
  }

  private AssignmentDeltaEvaluator.Context createAssignmentDeltaEvaluatorContext() {
    return new AssignmentDeltaEvaluator.Context() {
      @Override
      public void checkBudget() { searchDeadline.check(); }

      @Override
      public void syncSatelliteTaskViews(Map<Integer, Integer> assignmentSnapshot) {
        TaskAllocationCoordinator.this.syncSatelliteTaskViews(assignmentSnapshot);
      }

      @Override
      public List<Task> getOrderedTasksForSatellite(
        int satelliteId,
        List<Task> preGroupedTasks,
        Map<Integer, Integer> assignmentSnapshot
      ) {
        return TaskAllocationCoordinator.this.getOrderedTasksForSatellite(
          satelliteId,
          preGroupedTasks,
          assignmentSnapshot
        );
      }

      @Override
      public List<Task> getOrderedTasksForSatellite(int satelliteId, Map<Integer, Integer> assignmentSnapshot) {
        return TaskAllocationCoordinator.this.getOrderedTasksForSatellite(satelliteId, assignmentSnapshot);
      }

      @Override
      public SchedulingPlan scheduleSingleSatellite(
        Satellite satellite,
        List<Task> orderedTasks,
        HybCecConfig config,
        boolean commitState,
        boolean refreshAnchorOnFallback,
        Map<Integer, SatelliteState> stateStore
      ) throws Exception {
        return TaskAllocationCoordinator.this.scheduleSingleSatellite(
          satellite,
          orderedTasks,
          config,
          commitState,
          refreshAnchorOnFallback,
          stateStore
        );
      }

      @Override
      public void materializePlanSchedule(SchedulingPlan plan, Satellite satellite, InsertionMode mode) {
        TaskAllocationCoordinator.this.materializePlanSchedule(plan, satellite, mode);
      }

      @Override
      public int getCurrentIteration() {
        return TaskAllocationCoordinator.this.currentIteration;
      }
    };
  }

  private ReassignmentMoveEvaluator createReassignmentMoveEvaluator() {
    return new ReassignmentMoveEvaluator(
      satellites,
      globalTaskKeys,
      assignmentDeltaEvaluator,
      createReassignmentMoveEvaluatorContext()
    );
  }

  private ReassignmentMoveEvaluator.Context createReassignmentMoveEvaluatorContext() {
    return new ReassignmentMoveEvaluator.Context() {
      @Override
      public SatelliteCandidateSelection selectTopTargetSatellites(
        Task task,
        Integer currentSatelliteId,
        int currentIter,
        Map<Integer, Integer> assignmentSnapshot,
        boolean prioritizedTask
      ) {
        return TaskAllocationCoordinator.this.selectTopTargetSatellites(
          task,
          currentSatelliteId,
          currentIter,
          assignmentSnapshot,
          prioritizedTask
        );
      }

      @Override
      public void applyTaskSatelliteFeedback(int taskId, int satelliteId, boolean success) {
        TaskAllocationCoordinator.this.applyTaskSatelliteFeedback(taskId, satelliteId, success);
      }
    };
  }

  private ReassignmentOrchestrator createReassignmentOrchestrator() {
    return new ReassignmentOrchestrator(
      REASSIGN_PRIORITY_TASK_TOP_K,
      reassignmentMoveEvaluator,
      createReassignmentOrchestratorContext()
    );
  }

  private ReassignmentOrchestrator.Context createReassignmentOrchestratorContext() {
    return new ReassignmentOrchestrator.Context() {
      @Override
      public void checkpoint(Map<Integer, Integer> assignment, Map<Integer, SchedulingPlan> plans) {
        saveTimedCheckpoint(assignment, plans);
        if (unifiedCheckpoint != null) unifiedCheckpoint.accept(assignment, plans);
        searchDeadline.check();
      }

      @Override
      public SatelliteCandidateSelection selectTopTargetSatellites(
        Task task,
        Integer currentSatelliteId,
        int currentIter,
        Map<Integer, Integer> assignmentSnapshot,
        boolean prioritizedTask
      ) {
        return TaskAllocationCoordinator.this.selectTopTargetSatellites(
          task,
          currentSatelliteId,
          currentIter,
          assignmentSnapshot,
          prioritizedTask
        );
      }

      @Override
      public TrialMoveResult evaluateTrialMove(
        Task task,
        Integer sourceSatelliteId,
        int targetSatelliteId,
        Map<Integer, Integer> baseAssignment,
        Map<Integer, SchedulingPlan> basePlans,
        double baseProfit,
        HybCecConfig config
      ) throws Exception {
        return TaskAllocationCoordinator.this.evaluateTrialMove(
          task,
          sourceSatelliteId,
          targetSatelliteId,
          baseAssignment,
          basePlans,
          baseProfit,
          config
        );
      }

      @Override
      public Satellite getSatelliteById(int satelliteId) {
        return TaskAllocationCoordinator.this.getSatelliteById(satelliteId);
      }

      @Override
      public void applyTaskSatelliteFeedback(int taskId, int satelliteId, boolean success) {
        TaskAllocationCoordinator.this.applyTaskSatelliteFeedback(taskId, satelliteId, success);
      }
    };
  }

  public static class HybCecConfig {
    public final int[] parentsIndex;
    public final int[] oL;
    public final int oLRand;
    public final int oO;
    public final int strSurvive;
    public final boolean singleAlgorithmMode;
    public final int nPop;
    public final int maxFes;
    public final InsertionMode insertionMode;
    public final long seed;

    public HybCecConfig(int[] parentsIndex, int[] oL, int oLRand, int oO, int strSurvive, int nPop, int maxFes, InsertionMode insertionMode, long seed) {
      this(
        parentsIndex,
        oL,
        oLRand,
        oO,
        strSurvive,
        nPop,
        maxFes,
        insertionMode,
        seed,
        false
      );
    }

    public HybCecConfig(int[] parentsIndex, int[] oL, int oLRand, int oO, int strSurvive, int nPop, int maxFes, InsertionMode insertionMode, long seed, boolean singleAlgorithmMode) {
      this.parentsIndex = parentsIndex;
      this.oL = oL;
      this.oLRand = oLRand;
      this.oO = oO;
      this.strSurvive = strSurvive;
      this.singleAlgorithmMode = singleAlgorithmMode;
      this.nPop = nPop;
      this.maxFes = maxFes;
      this.insertionMode = insertionMode;
      this.seed = seed;
    }
  }

  public static class ConvergenceTrace {
    public final int traceIndex;
    public final int satelliteId;
    public final int ensembleIndex;
    public final int maxFes;
    public final int genUsed;
    public final boolean commitState;
    public final List<Double> bestFitnessByGeneration;
    public final List<Integer> evalsByGeneration;

    public ConvergenceTrace(
      int traceIndex,
      int satelliteId,
      int ensembleIndex,
      int maxFes,
      int genUsed,
      boolean commitState,
      List<Double> bestFitnessByGeneration,
      List<Integer> evalsByGeneration
    ) {
      this.traceIndex = traceIndex;
      this.satelliteId = satelliteId;
      this.ensembleIndex = ensembleIndex;
      this.maxFes = maxFes;
      this.genUsed = genUsed;
      this.commitState = commitState;
      this.bestFitnessByGeneration = new ArrayList<>(bestFitnessByGeneration);
      this.evalsByGeneration = new ArrayList<>(evalsByGeneration);
    }
  }

  private SearchDeadline searchDeadline = SearchDeadline.unlimited();
  private Map<Integer, SchedulingPlan> timedBestPlans;
  private Map<Integer, Integer> timedBestAssignment;
  private int timedBestProfit;
  private InsertionMode timedInsertionMode;

  /** 完整来源路径的显式预算入口。只返回完整物化的全局检查点；未完成候选不发布。 */
  public Map<Integer, SchedulingPlan> scheduleWithReassignment(HybCecConfig config,
      List<Satellite> runSatellites, Map<Integer, Integer> assignment, SearchDeadline deadline) throws Exception {
    Objects.requireNonNull(deadline);
    if (!deadline.limited()) { return scheduleWithReassignment(config, runSatellites, assignment); }
    if (config.singleAlgorithmMode) { throw new IllegalArgumentException("预算入口目前只支持完整混合RKHMA路径"); }
    if (searchDeadline.limited()) { throw new IllegalStateException("同一协调器不能并发运行"); }
    searchDeadline = deadline;
    timedInsertionMode = config.insertionMode;
    timedBestProfit = 0;
    timedBestAssignment = new HashMap<>();
    timedBestPlans = new HashMap<>();
    for (Satellite satellite : runSatellites) {
      SchedulingPlan empty = new SchedulingPlan(new ArrayList<>());
      empty.setFitness(0.0);
      timedBestPlans.put(satellite.getId(), empty);
    }
    try {
      deadline.check();
      var result = scheduleWithReassignment(config, runSatellites, assignment);
      saveTimedCheckpoint(bestTaskAssignmentSnapshot, result, true);
    } catch (Exception error) {
      if (!SearchDeadline.causedExpiry(error)) { throw error; }
    } finally {
      searchDeadline = SearchDeadline.unlimited();
    }
    taskAssignment.clear();
    replaceAssignmentSnapshot(taskAssignment, timedBestAssignment);
    bestTaskAssignmentSnapshot.clear();
    replaceAssignmentSnapshot(bestTaskAssignmentSnapshot, timedBestAssignment);
    syncSatelliteTaskViews(timedBestAssignment);
    Map<Integer, SchedulingPlan> result = new HashMap<>();
    timedBestPlans.forEach((id, plan) -> result.put(id, plan.copy()));
    return result;
  }

  private void saveTimedCheckpoint(Map<Integer, Integer> assignment, Map<Integer, SchedulingPlan> plans) {
    saveTimedCheckpoint(assignment, plans, false);
  }

  private void saveTimedCheckpoint(Map<Integer, Integer> assignment, Map<Integer, SchedulingPlan> plans,
      boolean replaceEqual) {
    if (!searchDeadline.limited()) { return; }
    if (searchDeadline.expired()) { return; }
    double proposedProfit = calculateTotalProfit(plans);
    if (proposedProfit < timedBestProfit || (!replaceEqual && proposedProfit == timedBestProfit)) { return; }
    // A checkpoint carries the entire task pool, not only executed observations.
    // A stale order paired with a newer assignment cannot be safely resumed.
    Set<Integer> knownTasks = new HashSet<>();
    for (Task task : allTasks) { knownTasks.add(task.getId()); }
    Map<Integer, Set<Integer>> expectedOrderIds = new HashMap<>();
    for (Satellite satellite : satellites) { expectedOrderIds.put(satellite.getId(), new HashSet<>()); }
    for (var entry : assignment.entrySet()) {
      Set<Integer> expected = expectedOrderIds.get(entry.getValue());
      if (expected == null || !knownTasks.contains(entry.getKey())) { return; }
      expected.add(entry.getKey());
    }
    var materialized = assignmentDeltaEvaluator.materializePlans(plans, assignment, timedInsertionMode);
    Set<Integer> seen = new HashSet<>();
    int profit = 0;
    for (Satellite satellite : satellites) {
      SchedulingPlan plan = materialized.get(satellite.getId());
      if (plan == null || !PlanUtils.checkRepeatedTasks(plan) || !PlanUtils.checkTransitionTimeConstraints(plan)) {
        throw new IllegalStateException("限时RKHMA全局检查点不完整或不可行");
      }
      Set<Integer> orderIds = new HashSet<>();
      for (Task task : plan.getTaskSequence()) {
        if (task == null || !orderIds.add(task.getId())) { return; }
      }
      if (!orderIds.equals(expectedOrderIds.get(satellite.getId()))) { return; }
      for (var task : plan.getSchedule()) {
        if (!seen.add(task.getTaskId()) || !Objects.equals(assignment.get(task.getTaskId()), satellite.getId())) {
          throw new IllegalStateException("限时RKHMA全局检查点重复任务或归属不一致");
        }
        var window = task.getTimeWindow();
        var records = window.getAttitudeRecords();
        int first = records.indexOf(task.getStartRecord()), last = records.indexOf(task.getEndRecord());
        if (task.getSatelliteId() != satellite.getId() || window.getSatelliteId() != satellite.getId()
            || window.getTaskId() != task.getTaskId() || !task.getTask().getTimeWindowsForSatellite(satellite.getId()).contains(window)
            || first < 1 || last >= records.size() - 1 || last <= first
            || task.getStartTime() < window.getStartTime() || task.getEndTime() > window.getEndTime()
            || Math.abs(task.getEndTime() - task.getStartTime() - task.getTask().getImagingDuration()) > 1e-6) {
          throw new IllegalStateException("限时RKHMA检查点的窗口／姿态／成像时长不合法");
        }
      }
      profit += plan.getTotalProfit();
    }
    if (profit > timedBestProfit || replaceEqual && profit == timedBestProfit) {
      Map<Integer, SchedulingPlan> nextPlans = new HashMap<>();
      materialized.forEach((id, plan) -> nextPlans.put(id, plan.copy()));
      Map<Integer, Integer> nextAssignment = copyAssignmentSnapshot(assignment);
      if (searchDeadline.expired()) { return; }
      timedBestPlans = nextPlans;
      timedBestAssignment = nextAssignment;
      timedBestProfit = profit;
    }
  }

  /** 提前物化来源算法首个基准个体，仅在截止时作为检查点。 */
  private void saveInitialPopulationCheckpoint(HybCecConfig config) {
    Map<Integer, SchedulingPlan> baseline = newSatelliteIdMap();
    for (Satellite satellite : satellites) {
      searchDeadline.check();
      List<Task> ordered = getOrderedTasksForSatellite(satellite.getId(), taskAssignment);
      SchedulingPlan plan = new SchedulingPlan(buildRealBaselineSequence(satellite.getId(), ordered, taskAssignment));
      materializePlanSchedule(plan, satellite, config.insertionMode);
      baseline.put(satellite.getId(), plan);
    }
    saveTimedCheckpoint(taskAssignment, baseline);
  }

  /** 为通用B接口准备来源单星搜索所需的初始状态。 */
  public void prepareUnifiedSingleSearch(HybCecConfig config, Map<Integer, Integer> initialAssignment,
      SearchDeadline deadline) {
    Objects.requireNonNull(config);
    Objects.requireNonNull(initialAssignment);
    searchDeadline = Objects.requireNonNull(deadline);
    currentPlanViews = Collections.emptyMap();
    failureCount.clear();
    bestTaskAssignmentSnapshot.clear();
    initialTaskAssignmentSnapshot.clear();
    initialTaskOrderById.clear();
    satelliteStates.clear();
    convergenceTraces.clear();
    convergenceTraceCounter = 0;
    initializeGlobalKeys(config.seed);
    applyInitialAssignment(initialAssignment);
    for (Satellite satellite : satellites) satellite.clearTasks();
    for (var entry : initialAssignment.entrySet()) {
      if (entry.getValue() != null) getSatelliteById(entry.getValue()).addTask(allTasks.get(entry.getKey()));
    }
    replaceAssignmentSnapshot(initialTaskAssignmentSnapshot, taskAssignment);
    captureInitialTaskOrder();
    syncSatelliteTaskViews(taskAssignment);
  }

  /** 使用来源种群构造和HybCec执行一次单星搜索。 */
  public SchedulingPlan searchUnifiedSingleSatellite(int satelliteId, HybCecConfig config) throws Exception {
    Satellite satellite = Objects.requireNonNull(getSatelliteById(satelliteId));
    List<Task> ordered = getOrderedTasksForSatellite(satelliteId, taskAssignment);
    return evaluateSingleSatellite(satellite, ordered, config, true, false, satelliteStates, false).plan;
  }

  /** 全星校准读取相同的改前单星状态，按卫星次序提交结果。 */
  public void beginUnifiedCalibration() {
    unifiedCalibrationSnapshot = copySatelliteStates(satelliteStates);
  }

  public SchedulingPlan searchUnifiedCalibrationSatellite(int satelliteId, HybCecConfig config) throws Exception {
    if (unifiedCalibrationSnapshot == null) throw new IllegalStateException("calibration not started");
    Satellite satellite = Objects.requireNonNull(getSatelliteById(satelliteId));
    satellite.setReassignmentPhase(true);
    List<Task> ordered = getOrderedTasksForSatellite(satelliteId, taskAssignment);
    SatelliteEvaluationResult result = evaluateSingleSatellite(
        satellite, ordered, config, true, false, unifiedCalibrationSnapshot, true);
    updateSatelliteState(satelliteId, result.state, satelliteStates);
    synchronized (convergenceTraces) {
      for (ConvergenceTrace trace : result.traces) appendConvergenceTrace(trace);
    }
    return result.plan;
  }

  /** 对同一颗星运行来源增量修补；修补不可用时执行来源种群搜索。 */
  public SchedulingPlan refineUnifiedSingleSatellite(int satelliteId, Map<Integer, Integer> before,
      Map<Integer, Integer> after, HybCecConfig config, int iteration) throws Exception {
    Satellite satellite = Objects.requireNonNull(getSatelliteById(satelliteId));
    satellite.setReassignmentPhase(true);
    syncSatelliteTaskViews(after);
    List<Task> previous = getOrderedTasksForSatellite(satelliteId, before);
    List<Task> current = getOrderedTasksForSatellite(satelliteId, after);
    SchedulingPlan result = deltaFastRefiner.refine(satellite, previous, current, after,
        config.insertionMode, satelliteStates, iteration, null);
    return result != null ? result
        : evaluateSingleSatellite(satellite, current, config, false, false, satelliteStates, false).plan;
  }

  /** 仅在完整移动被运行时接受后推进来源的归属快照。 */
  public void acceptUnifiedAssignment(Map<Integer, Integer> assignment) {
    replaceAssignmentSnapshot(taskAssignment, assignment);
    syncSatelliteTaskViews(taskAssignment);
  }

  /** 复用来源完整重分配控制流程，候选和全星校准均交由统一运行时试评。 */
  public UnifiedReassignment runUnifiedReassignment(Map<Integer, Integer> owners,
      Map<Integer, SchedulingPlan> initialPlans, HybCecConfig config,
      UnifiedCandidateEvaluator candidateEvaluator, UnifiedCalibrationEvaluator calibrationEvaluator,
      java.util.function.BiConsumer<Map<Integer, Integer>, Map<Integer, SchedulingPlan>> checkpoint) throws Exception {
    acceptUnifiedAssignment(owners);
    assignmentDeltaEvaluator.setUnifiedCandidateEvaluator(Objects.requireNonNull(candidateEvaluator));
    unifiedCalibrationEvaluator = Objects.requireNonNull(calibrationEvaluator);
    unifiedCheckpoint = Objects.requireNonNull(checkpoint);
    try {
      Map<Integer, SchedulingPlan> result = scheduleMultiSatelliteMode(config, initialPlans);
      return new UnifiedReassignment(copyAssignmentSnapshot(bestTaskAssignmentSnapshot), result);
    } finally {
      unifiedCalibrationEvaluator = null;
      unifiedCheckpoint = null;
      assignmentDeltaEvaluator.setUnifiedCandidateEvaluator(null);
    }
  }

  public Map<Integer, SchedulingPlan> scheduleWithReassignment(HybCecConfig hybCecConfig, List<Satellite> satellites, Map<Integer, Integer> initialAssignment) throws Exception {
    currentPlanViews = Collections.emptyMap();
    failureCount.clear();
    bestTaskAssignmentSnapshot.clear();
    initialTaskAssignmentSnapshot.clear();
    initialTaskOrderById.clear();
    satelliteStates.clear();
    strategyHistory.clear();
    unscheduledTaskCountHistory.clear();
    migrationCountHistory.clear();
    totalProfitHistory.clear();
    resetAcceptedReassignmentMoveStats();
    convergenceTraces.clear();
    convergenceTraceCounter = 0;
    initializeGlobalKeys(hybCecConfig.seed);
    if (initialAssignment != null) {
      applyInitialAssignment(initialAssignment);
    } else {
      applyDefaultAssignment(hybCecConfig.seed);
    }
    replaceAssignmentSnapshot(initialTaskAssignmentSnapshot, taskAssignment);
    captureInitialTaskOrder();
    syncSatelliteTaskViews(taskAssignment);
    if (searchDeadline.limited() && populationSearch == null) {
      saveInitialPopulationCheckpoint(hybCecConfig);
    }
    Map<Integer, SchedulingPlan> scheduledPlans;
    if (isMultiSatellite) {
      scheduledPlans = scheduleMultiSatelliteMode(hybCecConfig);
    } else {
      scheduledPlans = scheduleSingleSatelliteMode(hybCecConfig);
    }
    Map<Integer, Integer> finalAssignmentSnapshot = bestTaskAssignmentSnapshot.isEmpty()
      ? copyAssignmentSnapshot(taskAssignment)
      : copyAssignmentSnapshot(bestTaskAssignmentSnapshot);
    return assignmentDeltaEvaluator.materializePlans(scheduledPlans, finalAssignmentSnapshot, hybCecConfig.insertionMode);
  }

  public Map<Integer, Integer> getTaskAssignmentSnapshot() {
    return copyAssignmentSnapshot(taskAssignment);
  }

  public Map<Integer, Integer> getBestTaskAssignmentSnapshot() {
    return copyAssignmentSnapshot(bestTaskAssignmentSnapshot);
  }

  public List<String> getStrategyHistorySnapshot() {
    return new ArrayList<>(strategyHistory);
  }

  public List<Integer> getUnscheduledTaskCountHistorySnapshot() {
    return new ArrayList<>(unscheduledTaskCountHistory);
  }

  public List<Integer> getMigrationCountHistorySnapshot() {
    return new ArrayList<>(migrationCountHistory);
  }

  public List<Double> getTotalProfitHistorySnapshot() {
    return new ArrayList<>(totalProfitHistory);
  }

  Map<String, Integer> getAcceptedReassignmentMoveCountsSnapshot() {
    return new LinkedHashMap<>(acceptedReassignmentMoveCounts);
  }

  Map<String, Double> getAcceptedReassignmentMoveGainsSnapshot() {
    return new LinkedHashMap<>(acceptedReassignmentMoveGains);
  }

  private void resetAcceptedReassignmentMoveStats() {
    acceptedReassignmentMoveCounts.clear();
    acceptedReassignmentMoveGains.clear();
    acceptedReassignmentMoveCounts.put(STANDARD_MOVE_KEY, 0);
    acceptedReassignmentMoveCounts.put(REPLACEMENT_MOVE_KEY, 0);
    acceptedReassignmentMoveCounts.put(SWAP_MOVE_KEY, 0);
    acceptedReassignmentMoveCounts.put(BUNDLE_MOVE_KEY, 0);
    acceptedReassignmentMoveGains.put(STANDARD_MOVE_KEY, 0.0);
    acceptedReassignmentMoveGains.put(REPLACEMENT_MOVE_KEY, 0.0);
    acceptedReassignmentMoveGains.put(SWAP_MOVE_KEY, 0.0);
    acceptedReassignmentMoveGains.put(BUNDLE_MOVE_KEY, 0.0);
  }

  private void accumulateAcceptedReassignmentMoves(ReassignmentStats stats) {
    addAcceptedReassignmentMove(STANDARD_MOVE_KEY, stats.standardAcceptedMoveCount, stats.standardAcceptedGain);
    addAcceptedReassignmentMove(REPLACEMENT_MOVE_KEY, stats.replacementAcceptedMoveCount, stats.replacementAcceptedGain);
    addAcceptedReassignmentMove(SWAP_MOVE_KEY, stats.swapAcceptedMoveCount, stats.swapAcceptedGain);
    addAcceptedReassignmentMove(BUNDLE_MOVE_KEY, stats.bundleAcceptedMoveCount, stats.bundleAcceptedGain);
  }

  private void addAcceptedReassignmentMove(String strategyKey, int count, double gain) {
    acceptedReassignmentMoveCounts.merge(strategyKey, count, Integer::sum);
    acceptedReassignmentMoveGains.merge(strategyKey, gain, Double::sum);
  }

  public List<ConvergenceTrace> getConvergenceTracesSnapshot() {
    synchronized (convergenceTraces) {
      return new ArrayList<>(convergenceTraces);
    }
  }

  List<double[]> buildPopulationWithInheritanceForTest(
    Satellite satellite,
    List<Task> orderedTasks,
    HybCecConfig config,
    long seed
  ) {
    return buildPopulationWithInheritance(satellite, orderedTasks, config, RandomProvider.newWithSeed(seed), satelliteStates, 0, true);
  }

  List<double[]> buildPopulationWithInheritanceForTest(
    Satellite satellite,
    List<Task> orderedTasks,
    HybCecConfig config,
    long seed,
    boolean allowCrossSatelliteTemplate
  ) {
    return buildPopulationWithInheritance(
      satellite,
      orderedTasks,
      config,
      RandomProvider.newWithSeed(seed),
      satelliteStates,
      0,
      allowCrossSatelliteTemplate
    );
  }

  void updateSatelliteStateForTest(int satelliteId, double[] bestSolution, List<Task> orderedTasks, SchedulingPlan bestPlan) {
    updateSatelliteState(satelliteId, bestSolution, orderedTasks, bestPlan);
  }

  void applyInitialAssignmentForTest(Map<Integer, Integer> assignment) {
    applyInitialAssignment(assignment);
  }

  void captureInitialAssignmentSnapshotForTest() {
    initialTaskAssignmentSnapshot.clear();
    replaceAssignmentSnapshot(initialTaskAssignmentSnapshot, taskAssignment);
  }

  double[] buildCrossSatelliteMigrationTemplateForTest(int satelliteId, List<Task> orderedTasks) {
    return buildCrossSatelliteMigrationTemplate(satelliteId, orderedTasks, satelliteStates);
  }

  Map<Integer, SatelliteState> copySatelliteStatesForTest() {
    return copySatelliteStates(satelliteStates);
  }

  private void initializeGlobalKeys(long seed) {
    if (!globalTaskKeys.isEmpty()) {
      return;
    }
    keyRandom = RandomProvider.newWithSeed(seed ^ GLOBAL_KEY_SEED_SALT);
    List<Task> sortedTasks = new ArrayList<>(allTasks);
    sortedTasks.sort(Comparator.comparingInt(Task::getId));
    int maxPriority = sortedTasks.stream().mapToInt(Task::getPriority).max().orElse(1);
    int maxCandidateCount = Math.max(1, sortedTasks.stream().mapToInt(this::countCandidateSatellites).max().orElse(1));
    int maxWindowCount = Math.max(1, sortedTasks.stream().mapToInt(this::countTotalWindows).max().orElse(1));
    for (Task task : sortedTasks) {
      double priorityComponent = TaskAllocationScoring.normalize(task.getPriority(), maxPriority);
      double candidateComponent = TaskAllocationScoring.normalize(countCandidateSatellites(task), maxCandidateCount);
      double windowComponent = TaskAllocationScoring.normalize(countTotalWindows(task), maxWindowCount);
      double feasibilityComponent = (candidateComponent + windowComponent) / 2.0;
      double randomComponent = keyRandom.nextDouble();
      double taskKey = TaskAllocationScoring.clip01(
        TASK_KEY_PRIORITY_WEIGHT * priorityComponent
          + TASK_KEY_FEASIBILITY_WEIGHT * feasibilityComponent
          + TASK_KEY_RANDOM_WEIGHT * randomComponent
      );
      globalTaskKeys.putIfAbsent(task.getId(), taskKey);
      initializeTaskSatelliteScores(task, keyRandom);
    }
  }

  private void initializeTaskSatelliteScores(Task task, Random random) {
    if (task == null) {
      return;
    }
    Map<Integer, Double> baseScores = taskSatelliteBaseScores.computeIfAbsent(task.getId(), key -> new HashMap<>());
    Map<Integer, Double> feedbackScores = taskSatelliteFeedbackScores.computeIfAbsent(task.getId(), key -> new HashMap<>());
    int maxWindowCount = 0;
    double maxWindowLength = 0.0;
    double minEarliestStart = Double.POSITIVE_INFINITY;
    for (Satellite satellite : satellites) {
      List<TimeWindow> windows = task.getTimeWindowsForSatellite(satellite.getId());
      if (windows == null || windows.isEmpty()) {
        continue;
      }
      int windowCount = windows.size();
      double totalWindowLength = 0.0;
      double earliestStart = Double.POSITIVE_INFINITY;
      for (TimeWindow window : windows) {
        if (window == null) {
          continue;
        }
        totalWindowLength += Math.max(0.0, window.getEndTime() - window.getStartTime());
        earliestStart = Math.min(earliestStart, window.getStartTime());
      }
      maxWindowCount = Math.max(maxWindowCount, windowCount);
      maxWindowLength = Math.max(maxWindowLength, totalWindowLength);
      minEarliestStart = Math.min(minEarliestStart, earliestStart);
    }
    int finalMaxWindowCount = Math.max(1, maxWindowCount);
    double finalMaxWindowLength = Math.max(1.0, maxWindowLength);
    double finalMinEarliestStart = Double.isFinite(minEarliestStart) ? minEarliestStart : 0.0;
    for (Satellite satellite : satellites) {
      int satelliteId = satellite.getId();
      List<TimeWindow> windows = task.getTimeWindowsForSatellite(satelliteId);
      if (windows == null || windows.isEmpty()) {
        continue;
      }
      int windowCount = windows.size();
      double totalWindowLength = 0.0;
      double earliestStart = Double.POSITIVE_INFINITY;
      for (TimeWindow window : windows) {
        if (window == null) {
          continue;
        }
        totalWindowLength += Math.max(0.0, window.getEndTime() - window.getStartTime());
        earliestStart = Math.min(earliestStart, window.getStartTime());
      }
      double windowCountScore = TaskAllocationScoring.normalize(windowCount, finalMaxWindowCount);
      double windowLengthScore = totalWindowLength / finalMaxWindowLength;
      double earliestStartScore = Double.isFinite(earliestStart)
        ? 1.0 / (1.0 + Math.max(0.0, earliestStart - finalMinEarliestStart))
        : 0.0;
      double randomScore = random.nextDouble();
      double baseScore = TaskAllocationScoring.clip01(
        TASK_SAT_WINDOW_COUNT_WEIGHT * windowCountScore
          + TASK_SAT_WINDOW_LENGTH_WEIGHT * windowLengthScore
          + TASK_SAT_EARLIEST_START_WEIGHT * earliestStartScore
          + TASK_SAT_RANDOM_WEIGHT * randomScore
      );
      baseScores.putIfAbsent(satelliteId, baseScore);
      feedbackScores.putIfAbsent(satelliteId, 0.0);
    }
  }

  private double getTaskSatelliteBaseScoreOrDefault(int taskId, int satelliteId) {
    Map<Integer, Double> baseScores = taskSatelliteBaseScores.get(taskId);
    if (baseScores == null) {
      return 0.0;
    }
    return baseScores.getOrDefault(satelliteId, 0.0);
  }

  private Double getTaskSatelliteFeedbackScore(int taskId, int satelliteId) {
    Map<Integer, Double> feedbackScores = taskSatelliteFeedbackScores.get(taskId);
    if (feedbackScores == null) {
      return null;
    }
    return feedbackScores.get(satelliteId);
  }

  private void captureInitialTaskOrder() {
    for (Satellite satellite : satellites) {
      List<Task> assignedTasks = satellite.getAssignedTasks();
      if (assignedTasks == null) {
        continue;
      }
      for (int i = 0; i < assignedTasks.size(); i++) {
        Task task = assignedTasks.get(i);
        if (task != null) {
          int taskId = task.getId();
          if (initialTaskOrderById.putIfAbsent(taskId, i) == null) {
            double score = ORDERED_TASK_INITIAL_ORDER_WEIGHT / (1.0 + i);
            cachedInitialOrderScores.put(taskId, score);
          }
        }
      }
    }
  }

  private Map<Integer, List<Task>> groupTasksBySatellite(Map<Integer, Integer> assignmentSnapshot) {
    Map<Integer, List<Task>> grouped = new HashMap<>();
    for (Task task : allTasks) {
      Integer satId = assignmentSnapshot.get(task.getId());
      if (satId != null) {
        grouped.computeIfAbsent(satId, k -> new ArrayList<>()).add(task);
      }
    }
    return grouped;
  }

  private List<Task> getOrderedTasksForSatellite(int satelliteId, Map<Integer, Integer> assignmentSnapshot) {
    List<Task> tasks = new ArrayList<>();
    if (assignmentSnapshot == cachedAssignmentRef) {
      tasks.addAll(satelliteTaskCache.getOrDefault(satelliteId, Collections.emptyList()));
    } else if (assignmentSnapshot == cachedAssignmentRefPrev) {
      tasks.addAll(satelliteTaskCachePrev.getOrDefault(satelliteId, Collections.emptyList()));
    } else {
      for (Task task : allTasks) {
        Integer assignedSatelliteId = assignmentSnapshot.get(task.getId());
        if (assignedSatelliteId != null && assignedSatelliteId == satelliteId) {
          tasks.add(task);
        }
      }
    }
    return sortTasksByScore(tasks, satelliteId, assignmentSnapshot);
  }

  private List<Task> getOrderedTasksForSatellite(int satelliteId, List<Task> preGroupedTasks, Map<Integer, Integer> assignmentSnapshot) {
    List<Task> tasks = new ArrayList<>(preGroupedTasks);
    return sortTasksByScore(tasks, satelliteId, assignmentSnapshot);
  }

  private List<Task> sortTasksByScore(List<Task> tasks, int satelliteId, Map<Integer, Integer> assignmentSnapshot) {
    int n = tasks.size();
    if (n <= 1) {
      return tasks;
    }
    double[] scores = new double[n];
    for (int i = 0; i < n; i++) {
      scores[i] = computeOrderedTaskScore(tasks.get(i), satelliteId, assignmentSnapshot);
    }
    Integer[] indices = new Integer[n];
    for (int i = 0; i < n; i++) {
      indices[i] = i;
    }
    Arrays.sort(indices, (a, b) -> {
      int cmp = Double.compare(scores[b], scores[a]);
      if (cmp != 0) return cmp;
      return Integer.compare(tasks.get(a).getId(), tasks.get(b).getId());
    });
    List<Task> sorted = new ArrayList<>(n);
    for (int idx : indices) {
      sorted.add(tasks.get(idx));
    }
    tasks.clear();
    tasks.addAll(sorted);
    return tasks;
  }

  private double computeOrderedTaskScore(Task task, int satelliteId, Map<Integer, Integer> assignmentSnapshot) {
    if (task == null) {
      return Double.NEGATIVE_INFINITY;
    }
    double initialSatelliteBonus = isTaskInInitialSatellite(task.getId(), satelliteId, assignmentSnapshot)
      ? ORDERED_TASK_INITIAL_SATELLITE_BONUS
      : 0.0;
    double initialOrderScore = cachedInitialOrderScores.getOrDefault(task.getId(), 0.0);
    return initialSatelliteBonus
      + ORDERED_TASK_KEY_WEIGHT * globalTaskKeys.getOrDefault(task.getId(), 0.0)
      + ORDERED_TASK_SAT_KEY_WEIGHT * getTaskSatelliteKeyOrDefault(task.getId(), satelliteId)
      + initialOrderScore;
  }

  private void perturbKeysForFailedTasks() {
    if (keyPerturbThreshold <= 0 || keyPerturbStrength <= 0.0) {
      return;
    }
    for (Map.Entry<Integer, Integer> entry : failureCount.entrySet()) {
      int taskId = entry.getKey();
      int failures = entry.getValue();
      if (failures >= keyPerturbThreshold) {
        double oldKey = globalTaskKeys.getOrDefault(taskId, 0.0);
        double directionalBoost = FAILED_TASK_PRIORITY_BOOST * (failures / (double) keyPerturbThreshold) * keyPerturbStrength;
        double perturbation = keyRandom.nextGaussian() * keyPerturbStrength * 0.25;
        double newKey = TaskAllocationScoring.clip01(oldKey + directionalBoost + perturbation);
        globalTaskKeys.put(taskId, newKey);
        failureCount.put(taskId, 0);
      }
    }
  }

  private boolean shouldTriggerEarlyStopSecondChance(int iter, List<Double> recentBestProfits, List<Integer> recentUnscheduledCounts,
      int diagWindowSize, double bestTotalProfit, int iterationUnscheduledCount, int migrationCount, int consecutiveZeroMigrationCount) {
    recentBestProfits.add(bestTotalProfit);
    recentUnscheduledCounts.add(iterationUnscheduledCount);
    if (recentBestProfits.size() > diagWindowSize) {
      recentBestProfits.remove(0);
      recentUnscheduledCounts.remove(0);
    }
    if (recentBestProfits.size() >= 2) {
      double profitGain = bestTotalProfit - recentBestProfits.get(recentBestProfits.size() - 2);
      double relImprove = profitGain / (recentBestProfits.get(recentBestProfits.size() - 2) + 1e-9) * 100.0;
      double minProfit = Collections.min(recentBestProfits);
      double maxProfit = Collections.max(recentBestProfits);
      double profitRange = maxProfit - minProfit;
      int minUnscheduled = Collections.min(recentUnscheduledCounts);
      int maxUnscheduled = Collections.max(recentUnscheduledCounts);
      double migrationEff = migrationCount > 0 ? profitGain / migrationCount : 0.0;
      List<String> signals = new ArrayList<>();
      boolean profitConv = profitRange / (maxProfit + 1e-9) < 0.001 && recentBestProfits.size() >= diagWindowSize;
      boolean unschedStable = maxUnscheduled - minUnscheduled <= 2 && recentUnscheduledCounts.size() >= diagWindowSize;
      boolean zeroMigNear = consecutiveZeroMigrationCount >= ZERO_MIGRATION_EARLY_STOP_THRESHOLD - 1;
      if (profitConv) signals.add("profitConverged");
      if (unschedStable) signals.add("unscheduledStable");
      if (zeroMigNear) signals.add("zeroMigrationNear");
      return profitConv && unschedStable && zeroMigNear;
    }
    return false;
  }

  private Map<Integer, SchedulingPlan> scheduleMultiSatelliteMode(HybCecConfig config) throws Exception {
    return scheduleMultiSatelliteMode(config, null);
  }

  private Map<Integer, SchedulingPlan> scheduleMultiSatelliteMode(HybCecConfig config,
      Map<Integer, SchedulingPlan> unifiedInitialPlans) throws Exception {
    Map<Integer, Integer> baselineAssignmentSnapshot = copyAssignmentSnapshot(initialTaskAssignmentSnapshot.isEmpty() ? taskAssignment : initialTaskAssignmentSnapshot);
    Map<Integer, SchedulingPlan> bestPlans = unifiedInitialPlans == null
        ? evaluateAssignmentSnapshot(baselineAssignmentSnapshot, config) : unifiedInitialPlans;
    currentPlanViews = bestPlans;
    for (Satellite satellite : satellites) satellite.setReassignmentPhase(true);
    Map<Integer, SchedulingPlan> currentPlans = bestPlans;
    Map<Integer, Integer> currentPlansAssignmentSnapshot = baselineAssignmentSnapshot;
    double bestTotalProfit = calculateTotalProfit(bestPlans);
    double currentPlansProfit = bestTotalProfit;
    double previousIterationProfit = bestTotalProfit;
    int noImproveStreak = 0;
    int consecutiveZeroMigrationCount = 0;
    int secondChanceCount = 0;
    bestTaskAssignmentSnapshot.clear();
    replaceAssignmentSnapshot(bestTaskAssignmentSnapshot, baselineAssignmentSnapshot);
    totalProfitHistory.add(bestTotalProfit);
    int totalIterations = Math.max(1, maxReassignIterations);
    List<Double> recentBestProfits = new ArrayList<>();
    List<Integer> recentUnscheduledCounts = new ArrayList<>();
    int diagWindowSize = 5;
    boolean skippedCalibration = false;
    for (int iter = 0; iter < totalIterations; iter++) {
      searchDeadline.check();
      currentIteration = iter;
      Map<Integer, Integer> workingAssignment = null;
      Map<Integer, Integer> workingAssignmentSnapshot;
      Map<Integer, SchedulingPlan> workingPlans;
      double workingProfit;
      if (sameAssignment(taskAssignment, currentPlansAssignmentSnapshot)) {
        workingPlans = currentPlans;
        workingProfit = currentPlansProfit;
        workingAssignmentSnapshot = currentPlansAssignmentSnapshot;
      } else {
        workingAssignment = copyAssignmentSnapshot(taskAssignment);
        workingPlans = evaluateAssignmentSnapshot(workingAssignment, config);
        workingProfit = calculateTotalProfit(workingPlans);
        currentPlans = workingPlans;
        currentPlanViews = workingPlans;
        currentPlansAssignmentSnapshot = workingAssignment;
        workingAssignmentSnapshot = workingAssignment;
        currentPlansProfit = workingProfit;
        totalProfitHistory.add(workingProfit);
      }
      SchedulingStats schedulingStats = collectSchedulingStats(workingPlans);
      Set<Task> unscheduledTasks = schedulingStats.unscheduledTasks;
      if (workingProfit > bestTotalProfit) {
        bestTotalProfit = workingProfit;
        bestPlans = copyPlans(workingPlans);
        bestTaskAssignmentSnapshot.clear();
        replaceAssignmentSnapshot(bestTaskAssignmentSnapshot, workingAssignmentSnapshot);
      }
      if (iter > 0 && workingProfit <= previousIterationProfit) {
        noImproveStreak++;
      } else {
        noImproveStreak = 0;
      }
      previousIterationProfit = workingProfit;
      if (unscheduledTasks.isEmpty()) {
        strategyHistory.add("NoReassignNeeded");
        unscheduledTaskCountHistory.add(0);
        migrationCountHistory.add(0);
        break;
      }
      boolean shouldReassign = noImproveStreak >= REASSIGN_STAGNATION_THRESHOLD;
      if (!shouldReassign) {
        strategyHistory.add("HoldInitialAssignment");
        unscheduledTaskCountHistory.add(unscheduledTasks.size());
        migrationCountHistory.add(0);
        continue;
      }
      perturbKeysForFailedTasks();
      if (workingAssignment == null) {
        workingAssignment = copyAssignmentSnapshot(workingAssignmentSnapshot);
      }
      ReassignmentStats stats = reassignUnscheduledTasks(unscheduledTasks, iter, config, workingAssignment, workingPlans, workingProfit);
      double iterationProfit = stats.migrationCount > 0 ? stats.resultingProfit : workingProfit;
      int iterationUnscheduledCount = stats.migrationCount > 0 ? stats.resultingUnscheduledCount : unscheduledTasks.size();
      if (stats.migrationCount > 0) {
        currentPlans = stats.resultingPlans;
        currentPlanViews = currentPlans;
        currentPlansAssignmentSnapshot = copyAssignmentSnapshot(taskAssignment);
        currentPlansProfit = iterationProfit;
        if (iterationProfit > bestTotalProfit) {
          Map<Integer, Integer> calibrationAssignment = copyAssignmentSnapshot(taskAssignment);
          boolean runFullCalibration = shouldRunFullCalibrationAfterCheapRacing(
            calibrationAssignment,
            config,
            bestTotalProfit,
            unscheduledTasks.size(),
            iterationUnscheduledCount
          );
          if (runFullCalibration) {
            bestPlans = evaluateAssignmentSnapshot(calibrationAssignment, config);
            bestTotalProfit = calculateTotalProfit(bestPlans);
            currentPlans = bestPlans;
            currentPlanViews = bestPlans;
            currentPlansAssignmentSnapshot = calibrationAssignment;
            currentPlansProfit = bestTotalProfit;
            iterationProfit = currentPlansProfit;
            iterationUnscheduledCount = collectSchedulingStats(bestPlans).unscheduledCount;
          } else {
            skippedCalibration = true;
            bestTotalProfit = iterationProfit;
          }
          bestTaskAssignmentSnapshot.clear();
          replaceAssignmentSnapshot(bestTaskAssignmentSnapshot, taskAssignment);
        }
        totalProfitHistory.add(currentPlansProfit);
        previousIterationProfit = currentPlansProfit;
      }
      String appliedStrategy = stats.migrationCount > 0 ? selectStrategyName(iter) : "ReassignRejected";
      strategyHistory.add(appliedStrategy);
      unscheduledTaskCountHistory.add(iterationUnscheduledCount);
      migrationCountHistory.add(stats.migrationCount);
      if (stats.migrationCount == 0) {
        consecutiveZeroMigrationCount++;
        noImproveStreak = Math.max(noImproveStreak, REASSIGN_STAGNATION_THRESHOLD);
      } else {
        consecutiveZeroMigrationCount = 0;
      }
      boolean tripleSignal = shouldTriggerEarlyStopSecondChance(iter, recentBestProfits, recentUnscheduledCounts, diagWindowSize, bestTotalProfit, iterationUnscheduledCount, stats.migrationCount, consecutiveZeroMigrationCount);
      if (tripleSignal) {
        if (secondChanceCount < MAX_SECOND_CHANCES) {
          secondChanceCount++;
          bestPlans = evaluateAssignmentSnapshot(copyAssignmentSnapshot(taskAssignment), config);
          bestTotalProfit = calculateTotalProfit(bestPlans);
          currentPlans = bestPlans;
          currentPlanViews = bestPlans;
          currentPlansAssignmentSnapshot = copyAssignmentSnapshot(taskAssignment);
          currentPlansProfit = bestTotalProfit;
          continue;
        }
        break;
      }
    }
    // 可选的来源racing跳过校准时，结束前仍需校准最优归属。
    if (skippedCalibration) {
      bestPlans = evaluateAssignmentSnapshot(
        bestTaskAssignmentSnapshot.isEmpty() ? baselineAssignmentSnapshot : copyAssignmentSnapshot(bestTaskAssignmentSnapshot), config);
      bestTotalProfit = calculateTotalProfit(bestPlans);
    }
    syncSatelliteTaskViews(bestTaskAssignmentSnapshot.isEmpty() ? baselineAssignmentSnapshot : bestTaskAssignmentSnapshot);
    return bestPlans;
  }

  private boolean shouldRunFullCalibrationAfterCheapRacing(
    Map<Integer, Integer> assignmentSnapshot,
    HybCecConfig config,
    double previousBestProfit,
    int previousUnscheduledCount,
    int iterationUnscheduledCount
  ) {
    if (!Boolean.getBoolean(CALIBRATION_RACING_PROPERTY)) {
      return true;
    }
    if (previousUnscheduledCount != iterationUnscheduledCount) {
      return true;
    }
    try {
      double cheapProfit = estimateInheritedVectorProfit(assignmentSnapshot, config);
      return cheapProfit >= previousBestProfit + CALIBRATION_RACING_MIN_GAIN;
    } catch (RuntimeException e) {
      return true;
    }
  }

  private double estimateInheritedVectorProfit(Map<Integer, Integer> assignmentSnapshot, HybCecConfig config) {
    double totalProfit = 0.0;
    DecodeWorkspace workspace = new DecodeWorkspace();
    for (Satellite satellite : satellites) {
      List<Task> orderedTasks = getOrderedTasksForSatellite(satellite.getId(), assignmentSnapshot);
      if (orderedTasks.isEmpty()) {
        continue;
      }
      SatelliteState state = satelliteStates.get(satellite.getId());
      if (state == null || state.bestSolution == null || state.orderedTasksSnapshot == null) {
        throw new IllegalStateException("Missing inherited state for cheap calibration");
      }
      double[] inheritedSolution = buildInheritedSolution(
        satellite.getId(),
        state.bestSolution,
        state.orderedTasksSnapshot,
        orderedTasks
      );
      totalProfit += PlanUtils.evaluateVector(
        inheritedSolution,
        orderedTasks,
        satellite,
        config.insertionMode,
        workspace
      );
    }
    return totalProfit;
  }

  private Map<Integer, SchedulingPlan> scheduleSingleSatelliteMode(HybCecConfig config) throws Exception {
    Satellite satellite = satellites.get(0);
    Map<Integer, Integer> assignmentSnapshot = copyAssignmentSnapshot(taskAssignment);
    syncSatelliteTaskViews(assignmentSnapshot);
    List<Task> orderedTasks = getOrderedTasksForSatellite(satellite.getId(), assignmentSnapshot);
    SchedulingPlan plan = scheduleSingleSatellite(satellite, orderedTasks, config);
    Map<Integer, SchedulingPlan> plans = newSatelliteIdMap();
    plans.put(satellite.getId(), plan);
    bestTaskAssignmentSnapshot.clear();
    replaceAssignmentSnapshot(bestTaskAssignmentSnapshot, assignmentSnapshot);
    strategyHistory.add("SingleSatellite");
    SchedulingStats schedulingStats = collectSchedulingStats(plans);
    unscheduledTaskCountHistory.add(schedulingStats.unscheduledCount);
    migrationCountHistory.add(0);
    totalProfitHistory.add(calculateTotalProfit(plans));
    return plans;
  }

  private SchedulingPlan scheduleSingleSatellite(Satellite satellite, List<Task> orderedTasks, HybCecConfig config) throws Exception {
    return scheduleSingleSatellite(satellite, orderedTasks, config, true);
  }

  private SchedulingPlan scheduleSingleSatellite(
    Satellite satellite,
    List<Task> orderedTasks,
    HybCecConfig config,
    boolean commitState
  ) throws Exception {
    return scheduleSingleSatellite(satellite, orderedTasks, config, commitState, false, satelliteStates);
  }

  private SchedulingPlan scheduleSingleSatellite(
    Satellite satellite,
    List<Task> orderedTasks,
    HybCecConfig config,
    boolean commitState,
    boolean refreshAnchorOnFallback,
    Map<Integer, SatelliteState> stateStore
  ) throws Exception {
    return evaluateSingleSatellite(
      satellite,
      orderedTasks,
      config,
      commitState,
      refreshAnchorOnFallback,
      stateStore,
      false
    ).plan;
  }

  private SatelliteEvaluationResult evaluateSingleSatellite(
    Satellite satellite,
    List<Task> orderedTasks,
    HybCecConfig config,
    boolean commitState,
    boolean refreshAnchorOnFallback,
    Map<Integer, SatelliteState> stateStore,
    boolean deferStateCommit
  ) throws Exception {
    searchDeadline.check();
    int dimension = orderedTasks.size();
    if (dimension <= 0) {
      SchedulingPlan emptyPlan = new SchedulingPlan(new ArrayList<>());
      emptyPlan.setFitness(0.0);
      return new SatelliteEvaluationResult(satellite.getId(), emptyPlan, null, Collections.emptyList());
    }
    // Mini-ensemble: run multiple times during initial full eval (commitState=true, no inherited state) and pick best
    int initialEnsembleSize = populationSearch == null ? resolveInitialEnsembleSize(orderedTasks.size()) : 1;
    boolean useEnsemble = commitState && initialEnsembleSize > 1
        && stateStore.get(satellite.getId()) == null;
    int ensembleRuns = useEnsemble ? initialEnsembleSize : 1;
    double bestEnsembleFitness = Double.NEGATIVE_INFINITY;
    double[] bestEnsembleSolution = null;
    SchedulingPlan bestEnsemblePlan = null;
    boolean bestIsCustomPlan = false;
    List<ConvergenceTrace> localTraces = deferStateCommit ? new ArrayList<>() : Collections.emptyList();
    for (int ensembleIdx = 0; ensembleIdx < ensembleRuns; ensembleIdx++) {
      searchDeadline.check();
      long ensembleSeed = config.seed + satellite.getId() + ensembleIdx * ENSEMBLE_SEED_OFFSET;
      Random rand = RandomProvider.newWithSeed(ensembleSeed);
      List<double[]> popIni = buildPopulationWithInheritance(
        satellite,
        orderedTasks,
        config,
        rand,
        stateStore,
        ensembleIdx,
        !commitState
      );
      int effectiveMaxFes = computeEffectiveMaxFes(satellite.getId(), config, commitState, stateStore);
      observeResearchBudget(satellite.getId(), ensembleIdx, config.nPop, effectiveMaxFes, ensembleSeed, commitState);
      double[] solution;
      ConvergenceTrace trace;
      SchedulingPlan customPlan = null;
      if (config.singleAlgorithmMode) {
        SingleAlgorithmOptimizer.Result result = SingleAlgorithmOptimizer.optimize(
          config.parentsIndex[0],
          dimension,
          config.nPop,
          effectiveMaxFes,
          satellite,
          orderedTasks,
          popIni,
          rand,
          config.insertionMode
        );
        solution = result.getBestSolution();
        trace = buildConvergenceTrace(
          -1,
          satellite.getId(),
          ensembleIdx,
          effectiveMaxFes,
          commitState,
          result
        );
      } else {
        HybCec hybCec = new HybCec(
          config.parentsIndex[0],
          config.parentsIndex[1],
          dimension,
          config.nPop,
          config.oL,
          config.oLRand,
          config.oO,
          config.strSurvive,
          effectiveMaxFes,
          satellite,
          popIni,
          rand,
          config.insertionMode
        );
        hybCec.setEnableGenerationLog(
          Boolean.getBoolean("autohyb.generationLog")
            || Boolean.getBoolean("autohyb.generationLog.enabled"));
        hybCec.setGenerationLogInterval(Integer.getInteger("autohyb.logInterval", 1));
        hybCec.setEnableInitialPopLog(useEnsemble);
        hybCec.setAllTasks(new ArrayList<>(orderedTasks));
        hybCec.setSearchDeadline(searchDeadline);
        HybCec.HybResult result;
        if (populationSearch == null) {
          result = hybCec.run();
        } else {
          PopulationContext searchContext = new PopulationContext(satellite, List.copyOf(orderedTasks),
            popIni.stream().map(double[]::clone).toList(), ensembleSeed, rand, effectiveMaxFes, commitState,
            searchDeadline, !satellite.isReassignmentPhase() || currentPlanViews.get(satellite.getId()) == null
                ? null : currentPlanViews.get(satellite.getId()).copy(), vector -> {
              searchDeadline.check();
              return PlanUtils.evaluateVector(vector, orderedTasks, satellite, config.insertionMode, new DecodeWorkspace());
            });
          PopulationResult selected = Objects.requireNonNull(populationSearch.search(searchContext, hybCec));
          result = Objects.requireNonNull(selected.result());
          customPlan = selected.plan();
        }
        solution = result.getGbestSolution();
        trace = buildConvergenceTrace(
          -1,
          satellite.getId(),
          ensembleIdx,
          effectiveMaxFes,
          commitState,
          result
        );
      }
      if (trace != null) {
        if (deferStateCommit) {
          localTraces.add(trace);
        } else {
          synchronized (convergenceTraces) {
            appendConvergenceTrace(trace);
          }
        }
      }
      if (solution == null) {
        continue;
      }
      SchedulingPlan decodedPlan = customPlan == null ? PlanUtils.decode(solution, orderedTasks) : customPlan;
      if (customPlan == null) materializePlanSchedule(decodedPlan, satellite, config.insertionMode);
      double fitness = decodedPlan.getFitness();
      if (fitness > bestEnsembleFitness) {
        bestEnsembleFitness = fitness;
        bestEnsembleSolution = solution;
        bestEnsemblePlan = decodedPlan;
        bestIsCustomPlan = customPlan != null;
      }
    }
    if (bestEnsembleSolution == null) {
      SchedulingPlan emptyPlan = new SchedulingPlan(new ArrayList<>());
      emptyPlan.setFitness(0.0);
      return new SatelliteEvaluationResult(satellite.getId(), emptyPlan, null, localTraces);
    }
    if (useEnsemble && !bestIsCustomPlan) {
      // Re-materialize best plan to ensure satellite state consistency
      materializePlanSchedule(bestEnsemblePlan, satellite, config.insertionMode);
    }
    if (commitState || refreshAnchorOnFallback) {
      SatelliteState newState = new SatelliteState(bestEnsembleSolution, orderedTasks, bestEnsemblePlan);
      if (deferStateCommit) {
        return new SatelliteEvaluationResult(satellite.getId(), bestEnsemblePlan, newState, localTraces);
      }
      updateSatelliteState(satellite.getId(), newState, stateStore);
    }
    return new SatelliteEvaluationResult(satellite.getId(), bestEnsemblePlan, null, localTraces);
  }

  private ConvergenceTrace buildConvergenceTrace(
    int traceIndex,
    int satelliteId,
    int ensembleIndex,
    int maxFes,
    boolean commitState,
    HybCec.HybResult result
  ) {
    if (!commitState || result == null || result.getBestFitRecord() == null) {
      return null;
    }
    return new ConvergenceTrace(
      traceIndex,
      satelliteId,
      ensembleIndex,
      maxFes,
      result.getGen(),
      commitState,
      result.getBestFitRecord(),
      result.getEvalRecord()
    );
  }

  private ConvergenceTrace buildConvergenceTrace(
    int traceIndex,
    int satelliteId,
    int ensembleIndex,
    int maxFes,
    boolean commitState,
    SingleAlgorithmOptimizer.Result result
  ) {
    if (!commitState || result == null) {
      return null;
    }
    return new ConvergenceTrace(
      traceIndex,
      satelliteId,
      ensembleIndex,
      maxFes,
      result.getGenerations(),
      commitState,
      result.getBestFitnessByGeneration(),
      result.getEvalsByGeneration()
    );
  }

  private void appendConvergenceTrace(ConvergenceTrace trace) {
    convergenceTraces.add(new ConvergenceTrace(
      convergenceTraceCounter++,
      trace.satelliteId,
      trace.ensembleIndex,
      trace.maxFes,
      trace.genUsed,
      trace.commitState,
      trace.bestFitnessByGeneration,
      trace.evalsByGeneration
    ));
  }

  private int resolveInitialEnsembleSize(int assignedTaskCount) {
    // 所有规模沿用来源不超过500任务时的初始单星搜索次数。
    int legacySize = CONFIGURED_INITIAL_ENSEMBLE_SIZE;
    if (!Boolean.getBoolean(ADAPTIVE_ENSEMBLE_PROPERTY) || legacySize <= 1 || satellites.size() <= 1) {
      return legacySize;
    }
    Map<Integer, List<Task>> grouped = groupTasksBySatellite(taskAssignment);
    int[] assignedCounts = new int[satellites.size()];
    for (int i = 0; i < satellites.size(); i++) {
      assignedCounts[i] = grouped.getOrDefault(satellites.get(i).getId(), Collections.emptyList()).size();
    }
    Arrays.sort(assignedCounts);
    int highLoadCount = Math.max(1, (int) Math.ceil(satellites.size() * ADAPTIVE_ENSEMBLE_TOP_RATIO));
    int thresholdIndex = Math.max(0, assignedCounts.length - highLoadCount);
    int highLoadThreshold = assignedCounts[thresholdIndex];
    return assignedTaskCount >= highLoadThreshold ? legacySize : 1;
  }

  private void materializePlanSchedule(SchedulingPlan plan, Satellite satellite, InsertionMode mode) {
    if (plan == null || satellite == null) {
      return;
    }
    List<ScheduledTask> schedule = PlanUtils.generateSchedule(plan, satellite, mode);
    plan.setFitness((double) PlanUtils.calculateProfit(schedule));
  }

  private int computeEffectiveMaxFes(int satelliteId, HybCecConfig config, boolean commitState) {
    return computeEffectiveMaxFes(satelliteId, config, commitState, satelliteStates);
  }

  private int computeEffectiveMaxFes(
    int satelliteId,
    HybCecConfig config,
    boolean commitState,
    Map<Integer, SatelliteState> stateStore
  ) {
    if (!isLocalRefineHotStartBudgetEnabled()) {
      return config.maxFes;
    }
    SatelliteState state = stateStore.get(satelliteId);
    if (state == null) {
      return config.maxFes;
    }
    if (commitState) {
      // Full eval: reduce to 60% when inherited state exists, floor = nPop * FULL_REFINE_MIN_GENERATIONS
      int reducedMaxFes = (int) Math.round(config.maxFes * FULL_REFINE_BUDGET_RATIO);
      int minFullFes = Math.max(1, config.nPop * FULL_REFINE_MIN_GENERATIONS);
      return Math.min(config.maxFes, Math.max(minFullFes, reducedMaxFes));
    }
    // Delta eval: reduce to 20%
    int reducedMaxFes = (int) Math.round(config.maxFes * LOCAL_REFINE_BUDGET_RATIO);
    int minLocalFes = Math.max(1, config.nPop * LOCAL_REFINE_MIN_FES_PER_INDIVIDUAL);
    return Math.min(config.maxFes, Math.max(minLocalFes, reducedMaxFes));
  }

  private boolean isLocalRefineHotStartBudgetEnabled() {
    return Boolean.parseBoolean(System.getProperty(LOCAL_REFINE_HOT_START_BUDGET_PROPERTY, "true"));
  }

  private List<double[]> buildPopulationWithInheritance(
    Satellite satellite,
    List<Task> orderedTasks,
    HybCecConfig config,
    Random rand
  ) {
    return buildPopulationWithInheritance(satellite, orderedTasks, config, rand, satelliteStates, 0, false);
  }

  private List<double[]> buildPopulationWithInheritance(
    Satellite satellite,
    List<Task> orderedTasks,
    HybCecConfig config,
    Random rand,
    Map<Integer, SatelliteState> stateStore,
    int ensembleIdx,
    boolean allowCrossSatelliteTemplate
  ) {
    int dimension = orderedTasks.size();
    List<double[]> popIni = ListUtils.initializeRandomPopulation(
      config.nPop,
      dimension,
      -AlgorithmConstants.DEFAULT_BOUND,
      AlgorithmConstants.DEFAULT_BOUND,
      rand
    );
    injectRealBaselineIndividual(popIni, satellite.getId(), orderedTasks, taskAssignment);
    injectHeuristicBaselineIndividual(popIni, orderedTasks, ensembleIdx);

    SatelliteState state = stateStore.get(satellite.getId());
    if (state != null && popIni.size() > 2) {
      double[] inheritedSolution = buildInheritedSolution(
        satellite.getId(),
        state.bestSolution,
        state.orderedTasksSnapshot,
        orderedTasks
      );
      popIni.set(2, inheritedSolution);
    }
    injectCrossSatelliteTemplateIndividual(popIni, satellite.getId(), orderedTasks, stateStore, allowCrossSatelliteTemplate);
    return popIni;
  }

  private void injectCrossSatelliteTemplateIndividual(
    List<double[]> popIni,
    int satelliteId,
    List<Task> orderedTasks,
    Map<Integer, SatelliteState> stateStore,
    boolean allowCrossSatelliteTemplate
  ) {
    if (!allowCrossSatelliteTemplate || popIni == null || popIni.size() <= CROSS_SATELLITE_TEMPLATE_SLOT) {
      return;
    }
    double[] template = buildCrossSatelliteMigrationTemplate(satelliteId, orderedTasks, stateStore);
    if (template == null) {
      return;
    }
    popIni.set(CROSS_SATELLITE_TEMPLATE_SLOT, template);
  }

  private double[] buildCrossSatelliteMigrationTemplate(
    int satelliteId,
    List<Task> orderedTasks,
    Map<Integer, SatelliteState> stateStore
  ) {
    if (orderedTasks == null || orderedTasks.size() < CROSS_SATELLITE_TEMPLATE_MIN_SHARED_TASKS
      || stateStore == null || stateStore.isEmpty()) {
      return null;
    }

    Map<Integer, Task> currentTasksById = new HashMap<>();
    for (Task task : orderedTasks) {
      currentTasksById.put(task.getId(), task);
    }

    List<Task> bestSharedTemplate = null;
    int bestSharedCount = 0;
    List<Integer> donorSatelliteIds = new ArrayList<>(stateStore.keySet());
    Collections.sort(donorSatelliteIds);
    for (Integer donorSatelliteId : donorSatelliteIds) {
      SatelliteState donorState = stateStore.get(donorSatelliteId);
      if (donorSatelliteId == satelliteId || donorState == null || donorState.orderedTasksSnapshot == null) {
        continue;
      }
      List<Task> sharedTemplate = new ArrayList<>();
      Set<Integer> seenSharedTaskIds = new LinkedHashSet<>();
      for (Task donorTask : donorState.orderedTasksSnapshot) {
        Task mappedTask = currentTasksById.get(donorTask.getId());
        if (mappedTask != null && seenSharedTaskIds.add(mappedTask.getId())) {
          sharedTemplate.add(mappedTask);
        }
      }
      if (sharedTemplate.size() > bestSharedCount) {
        bestSharedCount = sharedTemplate.size();
        bestSharedTemplate = sharedTemplate;
      }
    }

    if (bestSharedTemplate == null || bestSharedTemplate.size() < CROSS_SATELLITE_TEMPLATE_MIN_SHARED_TASKS) {
      return null;
    }

    Set<Integer> sharedTaskIds = new LinkedHashSet<>();
    List<Task> migratedSequence = new ArrayList<>(orderedTasks.size());
    for (Task task : bestSharedTemplate) {
      if (sharedTaskIds.add(task.getId())) {
        migratedSequence.add(task);
      }
    }
    for (Task task : orderedTasks) {
      if (sharedTaskIds.add(task.getId())) {
        migratedSequence.add(task);
      }
    }

    if (migratedSequence.size() != orderedTasks.size()) {
      return null;
    }
    return PlanUtils.encodePlan(new SchedulingPlan(migratedSequence), orderedTasks);
  }

  private double[] buildInheritedSolution(int satelliteId, double[] oldSolution, List<Task> oldTasks, List<Task> newTasks) {
    validateOldStateConsistency(satelliteId, oldSolution, oldTasks);
    validateUniqueTaskIds(satelliteId, "oldTasks", oldTasks);
    validateUniqueTaskIds(satelliteId, "newTasks", newTasks);

    Map<Integer, Integer> oldTaskIndexById = new HashMap<>();
    for (int i = 0; i < oldTasks.size(); i++) {
      oldTaskIndexById.put(oldTasks.get(i).getId(), i);
    }

    double[] inheritedSolution = new double[newTasks.size()];
    for (int i = 0; i < newTasks.size(); i++) {
      Task task = newTasks.get(i);
      Integer oldIndex = oldTaskIndexById.get(task.getId());
      inheritedSolution[i] = oldIndex != null
        ? oldSolution[oldIndex]
        : computeBaselineKeyForTask(task);
    }

    validateInheritedSolutionConsistency(satelliteId, inheritedSolution, newTasks);
    return inheritedSolution;
  }

  private void validateOldStateConsistency(int satelliteId, double[] oldSolution, List<Task> oldTasks) {
    if (oldSolution == null || oldTasks == null) {
      throw new IllegalStateException(
        String.format(
          "Inherited solution state is missing for satellite %d: oldSolutionNull=%s, oldTasksNull=%s",
          satelliteId,
          oldSolution == null,
          oldTasks == null
        )
      );
    }
    if (oldSolution.length != oldTasks.size()) {
      throw new IllegalStateException(
        String.format(
          "Inherited solution state is inconsistent for satellite %d: oldSolutionLength=%d, oldTaskCount=%d",
          satelliteId,
          oldSolution.length,
          oldTasks.size()
        )
      );
    }
  }

  private void validateUniqueTaskIds(int satelliteId, String phaseLabel, List<Task> tasks) {
    Set<Integer> seenTaskIds = new HashSet<>();
    for (Task task : tasks) {
      if (task == null) {
        throw new IllegalStateException(
          String.format("Null task detected in %s for satellite %d", phaseLabel, satelliteId)
        );
      }
      if (!seenTaskIds.add(task.getId())) {
        throw new IllegalStateException(
          String.format("Duplicate taskId detected in %s for satellite %d: taskId=%d", phaseLabel, satelliteId, task.getId())
        );
      }
    }
  }

  private void validateInheritedSolutionConsistency(int satelliteId, double[] inheritedSolution, List<Task> newTasks) {
    if (inheritedSolution == null || inheritedSolution.length != newTasks.size()) {
      throw new IllegalStateException(
        String.format(
          "Inherited solution dimension mismatch for satellite %d: newSolutionLength=%d, newTaskCount=%d",
          satelliteId,
          inheritedSolution == null ? -1 : inheritedSolution.length,
          newTasks.size()
        )
      );
    }
  }

  private void updateSatelliteState(int satelliteId, double[] bestSolution, List<Task> orderedTasks, SchedulingPlan bestPlan) {
    updateSatelliteState(satelliteId, new SatelliteState(bestSolution, orderedTasks, bestPlan), satelliteStates);
  }

  private void updateSatelliteState(int satelliteId, SatelliteState state, Map<Integer, SatelliteState> stateStore) {
    if (state == null) {
      return;
    }
    if (Boolean.getBoolean(SATELLITE_STATE_POOL_PROPERTY)) {
      SatelliteState existing = stateStore.get(satelliteId);
      if (existing != null) {
        existing.reset(state.bestSolution, state.orderedTasksSnapshot, state.bestPlan);
        return;
      }
    }
    stateStore.put(satelliteId, state.copy());
  }

  private void updateSatelliteState(
    int satelliteId,
    double[] bestSolution,
    List<Task> orderedTasks,
    SchedulingPlan bestPlan,
    Map<Integer, SatelliteState> stateStore
  ) {
    updateSatelliteState(satelliteId, new SatelliteState(bestSolution, orderedTasks, bestPlan), stateStore);
  }

  private void injectRealBaselineIndividual(
    List<double[]> popIni,
    int satelliteId,
    List<Task> orderedTasks,
    Map<Integer, Integer> assignmentSnapshot
  ) {
    if (popIni == null || popIni.isEmpty() || orderedTasks == null || orderedTasks.isEmpty()) {
      return;
    }
    popIni.set(0, buildRealBaselineChromosome(satelliteId, orderedTasks, assignmentSnapshot));
  }

  private double[] buildRealBaselineChromosome(
    int satelliteId,
    List<Task> orderedTasks,
    Map<Integer, Integer> assignmentSnapshot
  ) {
    SchedulingPlan baselinePlan = new SchedulingPlan(buildRealBaselineSequence(satelliteId, orderedTasks, assignmentSnapshot));
    return PlanUtils.encodePlan(baselinePlan, orderedTasks);
  }

  private List<Task> buildRealBaselineSequence(
    int satelliteId,
    List<Task> orderedTasks,
    Map<Integer, Integer> assignmentSnapshot
  ) {
    List<Task> baselineSequence = new ArrayList<>(orderedTasks);
    baselineSequence.sort(Comparator
      .comparingInt((Task task) -> isTaskInInitialSatellite(task.getId(), satelliteId, assignmentSnapshot) ? 0 : 1)
      .thenComparingInt(task -> initialTaskOrderById.getOrDefault(task.getId(), Integer.MAX_VALUE))
      .thenComparingDouble(task -> globalTaskKeys.getOrDefault(task.getId(), 0.0))
      .thenComparingInt(Task::getId));
    return baselineSequence;
  }

  private void injectHeuristicBaselineIndividual(List<double[]> popIni, List<Task> orderedTasks, int ensembleIdx) {
    if (popIni == null || popIni.size() < 2 || orderedTasks == null || orderedTasks.isEmpty()) {
      return;
    }
    double[] baselineIndividual = new double[orderedTasks.size()];
    for (int i = 0; i < orderedTasks.size(); i++) {
      baselineIndividual[i] = computeBaselineKeyForTask(orderedTasks.get(i));
    }
    popIni.set(1, baselineIndividual);
  }

  private double computeBaselineKeyForTask(Task task) {
    if (task == null) {
      throw new IllegalArgumentException("Task cannot be null when computing baseline key");
    }
    return TaskAllocationScoring.clip01(globalTaskKeys.getOrDefault(task.getId(), 0.0) + computeStableTieBreak(task.getId()));
  }

  private double computeStableTieBreak(int taskId) {
    double globalKey = globalTaskKeys.getOrDefault(taskId, 0.0);
    return globalKey * BASELINE_TIE_BREAK_SCALE;
  }

  private double getTaskSatelliteKeyOrDefault(int taskId, int satelliteId) {
    return getEffectiveTaskSatelliteFeedbackScore(taskId, satelliteId);
  }

  private double getTaskSatelliteFeedbackScoreOrDefault(int taskId, int satelliteId) {
    Double feedbackScore = getTaskSatelliteFeedbackScore(taskId, satelliteId);
    return feedbackScore == null ? 0.0 : feedbackScore;
  }

  private double getEffectiveTaskSatelliteFeedbackScore(int taskId, int satelliteId) {
    return TaskAllocationScoring.clip01(
      getTaskSatelliteBaseScoreOrDefault(taskId, satelliteId)
        + getTaskSatelliteFeedbackScoreOrDefault(taskId, satelliteId)
    );
  }

  private void updateTaskSatelliteFeedbackScore(int taskId, int satelliteId, double delta) {
    Map<Integer, Double> feedbackScores = taskSatelliteFeedbackScores.computeIfAbsent(taskId, key -> new HashMap<>());
    double baseScore = getTaskSatelliteBaseScoreOrDefault(taskId, satelliteId);
    double currentDelta = feedbackScores.getOrDefault(satelliteId, 0.0);
    double updatedEffectiveScore = TaskAllocationScoring.clip01(baseScore + currentDelta + delta);
    feedbackScores.put(satelliteId, updatedEffectiveScore - baseScore);
  }

  private void applyTaskSatelliteFeedback(int taskId, int satelliteId, boolean success) {
    double delta = success ? TASK_SAT_SUCCESS_BOOST : -TASK_SAT_FAILURE_PENALTY;
    updateTaskSatelliteFeedbackScore(taskId, satelliteId, delta);
  }


  private double computeFastRefineRepairScore(Task task, int satelliteId) {
    if (task == null) {
      return Double.NEGATIVE_INFINITY;
    }
    return TaskAllocationScoring.computeFastRefineRepairScore(
      task,
      satelliteId,
      maxTaskPriority,
      globalTaskKeys,
      taskSatelliteBaseScores,
      taskSatelliteFeedbackScores
    );
  }

  private Map<Integer, SchedulingPlan> evaluateAssignmentSnapshot(Map<Integer, Integer> assignmentSnapshot, HybCecConfig config) throws Exception {
    syncSatelliteTaskViews(assignmentSnapshot);
    if (unifiedCalibrationEvaluator != null) {
      return unifiedCalibrationEvaluator.evaluate(copyAssignmentSnapshot(assignmentSnapshot), currentPlanViews);
    }
    if (calibrationStart != null && satellites.stream().anyMatch(Satellite::isReassignmentPhase)) {
      calibrationStart.accept(copyAssignmentSnapshot(assignmentSnapshot), currentPlanViews);
    }
    Map<Integer, SchedulingPlan> plans;
    if (satellites.size() <= 1) {
      // Single satellite: no parallelism needed
      plans = new HashMap<>();
      for (Satellite sat : satellites) {
        List<Task> orderedTasks = getOrderedTasksForSatellite(sat.getId(), assignmentSnapshot);
        SchedulingPlan plan = scheduleSingleSatellite(sat, orderedTasks, config);
        plans.put(sat.getId(), plan);
      }
    } else {
      // Pre-compute ordered tasks sequentially (reads shared globalTaskKeys / task-satellite scores)
      Map<Integer, List<Task>> precomputedOrderedTasks = new LinkedHashMap<>();
      for (Satellite sat : satellites) {
        precomputedOrderedTasks.put(sat.getId(),
            getOrderedTasksForSatellite(sat.getId(), assignmentSnapshot));
      }
      // 来源的正式运行入口默认启用确定性提交；允许调用方显式关闭。
      boolean deterministic = Boolean.parseBoolean(System.getProperty("autohyb.deterministic", "true"));
      Map<Integer, SatelliteState> readOnlyStateSnapshot = deterministic
        ? copySatelliteStates(satelliteStates)
        : satelliteStates;
      // Parallel satellite evaluation: each satellite's HybCec is independent.
      // Uses dedicated SATELLITE_POOL to avoid thread-pool starvation
      // with HybCec's internal parallelStream (common ForkJoinPool).
      Map<Integer, SchedulingPlan> parallelPlans = new ConcurrentHashMap<>();
      Map<Integer, SatelliteEvaluationResult> deterministicResults = deterministic
        ? new ConcurrentHashMap<>()
        : Collections.emptyMap();
      CompletableFuture<?>[] futures = new CompletableFuture[satellites.size()];
      for (int i = 0; i < satellites.size(); i++) {
        Satellite sat = satellites.get(i);
        List<Task> orderedTasks = precomputedOrderedTasks.get(sat.getId());
        futures[i] = CompletableFuture.runAsync(() -> {
          try {
            if (deterministic) {
              SatelliteEvaluationResult result = evaluateSingleSatellite(
                sat,
                orderedTasks,
                config,
                true,
                false,
                readOnlyStateSnapshot,
                true
              );
              deterministicResults.put(sat.getId(), result);
              parallelPlans.put(sat.getId(), result.plan);
            } else {
              SchedulingPlan plan = scheduleSingleSatellite(sat, orderedTasks, config);
              parallelPlans.put(sat.getId(), plan);
            }
          } catch (Exception e) {
            throw new CompletionException(e);
          }
        }, SATELLITE_POOL);
      }
      try {
        CompletableFuture.allOf(futures).join();
      } catch (CompletionException ce) {
        // allOf已等待全部卫星退出；预算到点不能掩盖另一卫星的真实异常。
        if (searchDeadline.limited()) {
          for (var future : futures) {
            try { future.join(); }
            catch (CompletionException individual) {
              if (!SearchDeadline.causedExpiry(individual)) { throw individual; }
            }
          }
        }
        Throwable cause = ce.getCause();
        if (cause instanceof Exception) {
          throw (Exception) cause;
        }
        throw new RuntimeException(cause);
      }
      if (deterministic) {
        plans = newSatelliteIdMap();
        List<Integer> sortedSatelliteIds = sortedSatelliteIds();
        for (Integer satelliteId : sortedSatelliteIds) {
          SatelliteEvaluationResult result = deterministicResults.get(satelliteId);
          if (result == null) {
            continue;
          }
          plans.put(satelliteId, result.plan);
          updateSatelliteState(satelliteId, result.state, satelliteStates);
          synchronized (convergenceTraces) {
            for (ConvergenceTrace trace : result.traces) {
              appendConvergenceTrace(trace);
            }
          }
        }
      } else {
        plans = parallelPlans;
      }
    }
    var materialized = assignmentDeltaEvaluator.materializePlans(plans, assignmentSnapshot, config.insertionMode);
    saveTimedCheckpoint(assignmentSnapshot, materialized);
    searchDeadline.check();
    return materialized;
  }

  private Map<Integer, SatelliteState> copySatelliteStates(Map<Integer, SatelliteState> source) {
    Map<Integer, SatelliteState> snapshot = newSatelliteIdMap();
    List<Integer> satelliteIds = new ArrayList<>(source.keySet());
    Collections.sort(satelliteIds);
    for (Integer satelliteId : satelliteIds) {
      SatelliteState state = source.get(satelliteId);
      if (state != null) {
        snapshot.put(satelliteId, state.copy());
      }
    }
    return snapshot;
  }

  private List<Integer> sortedSatelliteIds() {
    List<Integer> satelliteIds = new ArrayList<>(satellites.size());
    for (Satellite satellite : satellites) {
      satelliteIds.add(satellite.getId());
    }
    Collections.sort(satelliteIds);
    return satelliteIds;
  }

  private TrialMoveResult evaluateTrialMove(
    Task task,
    Integer sourceSatelliteId,
    int targetSatelliteId,
    Map<Integer, Integer> baseAssignment,
    Map<Integer, SchedulingPlan> basePlans,
    double baseProfit,
    HybCecConfig config
  ) throws Exception {
    return assignmentDeltaEvaluator.evaluateTrialMove(
      task,
      sourceSatelliteId,
      targetSatelliteId,
      baseAssignment,
      basePlans,
      baseProfit,
      config
    );
  }

  private void validatePlanCoverage(Map<Integer, SchedulingPlan> plans, String label) {
    assignmentDeltaEvaluator.validatePlanCoverage(plans, label);
  }

  private SchedulingStats collectSchedulingStats(Map<Integer, SchedulingPlan> currentPlans) {
    Set<Integer> scheduledTaskIds = new LinkedHashSet<>();
    for (int i = 0; i < satellites.size(); i++) {
      SchedulingPlan plan = currentPlans.get(satellites.get(i).getId());
      scheduledTaskIds.addAll(getScheduledTaskIds(plan));
    }
    Set<Task> unscheduledTasks = new LinkedHashSet<>();
    for (Task task : allTasks) {
      if (scheduledTaskIds.contains(task.getId())) {
        resetFailureCount(task.getId());
      } else {
        unscheduledTasks.add(task);
        updateFailureCount(task.getId());
      }
    }
    return new SchedulingStats(scheduledTaskIds, unscheduledTasks);
  }

  private boolean isTaskInInitialSatellite(int taskId, int satelliteId, Map<Integer, Integer> assignmentSnapshot) {
    Integer initialSatelliteId = initialTaskAssignmentSnapshot.get(taskId);
    Integer currentSatelliteId = assignmentSnapshot.get(taskId);
    return initialSatelliteId != null && currentSatelliteId != null && initialSatelliteId == satelliteId && currentSatelliteId == satelliteId;
  }

  private boolean sameAssignment(Map<Integer, Integer> left, Map<Integer, Integer> right) {
    if (left == right) {
      return true;
    }
    if (left == null || right == null || left.size() != right.size()) {
      return false;
    }
    for (int i = 0; i < allTasks.size(); i++) {
      int taskId = allTasks.get(i).getId();
      if (!Objects.equals(left.get(taskId), right.get(taskId))) {
        return false;
      }
    }
    return true;
  }

  private Map<Integer, Integer> copyAssignmentSnapshot(Map<Integer, Integer> source) {
    Map<Integer, Integer> snapshot = newAssignmentSnapshot();
    copyAssignmentSnapshotInto(snapshot, source);
    return snapshot;
  }

  private void replaceAssignmentSnapshot(Map<Integer, Integer> target, Map<Integer, Integer> source) {
    if (target == source) {
      return;
    }
    target.clear();
    copyAssignmentSnapshotInto(target, source);
  }

  private void copyAssignmentSnapshotInto(Map<Integer, Integer> target, Map<Integer, Integer> source) {
    for (int i = 0; i < allTasks.size(); i++) {
      int taskId = allTasks.get(i).getId();
      Integer satelliteId = source.get(taskId);
      if (satelliteId != null) {
        target.put(taskId, satelliteId);
      }
    }
  }

  private void applyDefaultAssignment(long seed) {
    taskAssignment.clear();
    List<Task> taskCopies = copyTasks(allTasks);
    List<Satellite> satelliteCopies = copySatellites(satellites);
    List<Integer> candidateTaskSequence = new ArrayList<>();
    for (int i = 0; i < taskCopies.size(); i++) {
      candidateTaskSequence.add(i);
    }
    AssignTaskOperator operator = new AssignTaskOperator(taskCopies, satelliteCopies, candidateTaskSequence);
    List<List<Integer>> assignedPairs = assignWithStrategy(operator, reassignmentStrategy);
    AssignTaskOperator.assignTaskToSatellites(assignedPairs, taskCopies, satelliteCopies);
    for (Satellite satellite : satelliteCopies) {
      for (Task task : satellite.getAssignedTasks()) {
        taskAssignment.put(task.getId(), satellite.getId());
      }
    }
  }

  private void applyInitialAssignment(Map<Integer, Integer> assignment) {
    taskAssignment.clear();
    replaceAssignmentSnapshot(taskAssignment, assignment);
  }

  private List<List<Integer>> assignWithStrategy(AssignTaskOperator operator, AssignmentStrategy strategy) {
    AssignmentStrategy effectiveStrategy = strategy != null ? strategy : new MaxWindowCountStrategy();
    if (effectiveStrategy instanceof RandomStrategy) {
      return operator.assignRandomly();
    }
    if (effectiveStrategy instanceof MinTaskCountStrategy) {
      return operator.assignBasedTaskNums();
    }
    if (effectiveStrategy instanceof MinRollAngleStrategy) {
      return operator.assignBasedMinRollAngle();
    }
    if (effectiveStrategy instanceof EarliestTimeStrategy) {
      return operator.assignBasedEarliestTime();
    }
    if (effectiveStrategy instanceof MaxWindowLengthStrategy) {
      return operator.assignBasedLongestWindow();
    }
    if (effectiveStrategy instanceof MinPriorityStrategy) {
      return operator.assignBasedMinPriority();
    }
    return operator.assignBasedWindowNums();
  }

  private ReassignmentStats reassignUnscheduledTasks(
    Set<Task> unscheduledTasks,
    int currentIter,
    HybCecConfig config,
    Map<Integer, Integer> workingAssignment,
    Map<Integer, SchedulingPlan> workingPlans,
    double workingProfit
  ) throws Exception {
    if (assignmentPolicy != null) {
      return reassignWithPolicy(config, workingAssignment, workingPlans, workingProfit);
    }
    ReassignmentStats stats = new ReassignmentStats();
    stats.resultingProfit = workingProfit;
    stats.resultingUnscheduledCount = unscheduledTasks.size();
    stats.resultingPlans = workingPlans;
    if (unscheduledTasks.isEmpty() || satellites.isEmpty()) {
      return stats;
    }
    validatePlanCoverage(workingPlans, "workingPlans");
    int maxMigrationsThisIter = Math.max(
      1,
      Math.min(
        REASSIGN_MIGRATION_MAX_CAP,
        (int) Math.ceil(unscheduledTasks.size() * REASSIGN_MIGRATION_RATIO)
      )
    );
    Map<Integer, Integer> originalAssignment = copyAssignmentSnapshot(workingAssignment);
    ReassignmentWorkingState workingState = new ReassignmentWorkingState(
      copyAssignmentSnapshot(workingAssignment),
      workingPlans,
      workingProfit
    );
    List<Task> prioritizedUnscheduledTasks = buildPrioritizedUnscheduledTasks(unscheduledTasks);
    int prioritizedTaskCount = Math.min(REASSIGN_PRIORITY_TASK_TOP_K, prioritizedUnscheduledTasks.size());
    stats.prioritizedTaskCount = prioritizedTaskCount;
    reassignmentOrchestrator.runPrioritizedMigrationLoop(
      prioritizedUnscheduledTasks,
      prioritizedTaskCount,
      maxMigrationsThisIter,
      currentIter,
      config,
      workingState,
      stats
    );
    reassignmentOrchestrator.tryBundleMoves(
      prioritizedUnscheduledTasks,
      currentIter,
      config,
      workingState,
      stats
    );
    accumulateAcceptedReassignmentMoves(stats);
    if (stats.migrationCount > 0) {
      taskAssignment.clear();
      replaceAssignmentSnapshot(taskAssignment, workingState.candidateAssignment);
      stats.resultingProfit = workingState.candidateProfit;
      stats.resultingPlans = workingState.candidatePlans;
      stats.resultingUnscheduledCount = collectSchedulingStats(workingState.candidatePlans).unscheduledCount;
    } else {
      taskAssignment.clear();
      replaceAssignmentSnapshot(taskAssignment, originalAssignment);
    }
    syncSatelliteTaskViews(taskAssignment);
    return stats;
  }

  private ReassignmentStats reassignWithPolicy(HybCecConfig config, Map<Integer, Integer> owners,
      Map<Integer, SchedulingPlan> plans, double profit) throws Exception {
    ReassignmentStats stats = new ReassignmentStats();
    Map<Integer, Integer> currentOwners = copyAssignmentSnapshot(owners);
    Map<Integer, SchedulingPlan> currentPlans = plans;
    double currentProfit = profit;
    String previous = "START";
    int attempt = 0;
    while (true) {
      searchDeadline.check();
      Map<Integer, SchedulingPlan> planCopy = AssignmentDeltaEvaluator.copyPlans(currentPlans);
      var context = new MoveContext(Collections.unmodifiableMap(copyAssignmentSnapshot(currentOwners)),
        Collections.unmodifiableMap(planCopy), attempt, previous);
      Map<Integer, Integer> rootOwners = currentOwners;
      Map<Integer, SchedulingPlan> rootPlans = currentPlans;
      Set<PolicyTrial> issued = Collections.newSetFromMap(new IdentityHashMap<>());
      PolicyEvaluator evaluator = new PolicyEvaluator() {
        @Override public PolicyTrial evaluate(PolicyTrial base, PolicyMove move) throws Exception {
          searchDeadline.check();
          if (base != null && (!issued.contains(base) || base.profit() == null)) {
            throw new IllegalArgumentException("assignment trial base was not issued");
          }
          if (move == null || move.edits().isEmpty() && move.repairSatellites().isEmpty()) {
            throw new IllegalArgumentException("empty assignment move");
          }
          if (move.fullRecalibration() && (!move.edits().isEmpty()
              || !move.repairSatellites().equals(new LinkedHashSet<>(sortedSatelliteIds())))) {
            throw new IllegalArgumentException("invalid full recalibration");
          }
          Map<Integer, Integer> baseOwners = base == null ? rootOwners : base.owners();
          Map<Integer, SchedulingPlan> basePlans = base == null ? rootPlans : base.plans();
          Map<Integer, Integer> trialOwners = copyAssignmentSnapshot(baseOwners);
          Set<Integer> affected = new LinkedHashSet<>();
          for (int sat : move.repairSatellites()) {
            if (getSatelliteById(sat) == null) throw new IllegalArgumentException("unknown repair satellite");
            affected.add(sat);
          }
          for (var edit : move.edits().entrySet()) {
            int task = edit.getKey();
            Integer from = baseOwners.get(task);
            Integer to = edit.getValue();
            if (task < 0 || task >= allTasks.size() || Objects.equals(from, to)) {
              throw new IllegalArgumentException("unknown task or unchanged owner");
            }
            if (to != null) {
              Satellite destination = getSatelliteById(to);
              if (destination == null || allTasks.get(task).getTimeWindowsForSatellite(to).stream()
                  .noneMatch(window -> window.canAccommodateTask(allTasks.get(task).getImagingDuration()))) {
                throw new IllegalArgumentException("invalid destination");
              }
              trialOwners.put(task, to);
              affected.add(to);
            } else {
              trialOwners.remove(task);
            }
            if (from != null) affected.add(from);
          }
          syncSatelliteTaskViews(baseOwners);
          Map<Integer, SchedulingPlan> trialPlans = move.fullRecalibration()
              ? evaluateAssignmentSnapshot(trialOwners, config)
              : assignmentDeltaEvaluator.buildDeltaPlans(basePlans, baseOwners, trialOwners, affected, config);
          searchDeadline.check();
          boolean feasible = planValidator.test(trialPlans);
          PolicyTrial trial = new PolicyTrial(move, feasible ? trialOwners : null, feasible ? trialPlans : null,
              feasible ? calculateTotalProfit(trialPlans) : null);
          issued.add(trial);
          return trial;
        }
        @Override public void checkpoint(PolicyTrial trial) {
          if (!issued.contains(trial) || trial.profit() == null) {
            throw new IllegalArgumentException("assignment checkpoint was not issued");
          }
          saveTimedCheckpoint(trial.owners(), trial.plans());
        }
      };
      PolicyTrial selected = assignmentPolicy.search(context, evaluator);
      if (selected == null) break;
      if (!issued.contains(selected)) throw new IllegalArgumentException("assignment trial was not issued");
      if (selected.profit() != null
          && (selected.profit() > currentProfit || assignmentPolicy.acceptsNonImprovingResult())) {
        currentOwners = selected.owners();
        currentPlans = selected.plans();
        currentProfit = selected.profit();
        stats.migrationCount++;
        previous = "ACCEPTED";
        attempt = 0;
        assignmentPolicy.onAccepted(selected);
      } else {
        syncSatelliteTaskViews(currentOwners);
        previous = "NO_IMPROVEMENT";
        attempt++;
      }
    }
    replaceAssignmentSnapshot(taskAssignment, currentOwners);
    syncSatelliteTaskViews(currentOwners);
    stats.resultingPlans = currentPlans;
    stats.resultingProfit = currentProfit;
    stats.resultingUnscheduledCount = collectSchedulingStats(currentPlans).unscheduledCount;
    return stats;
  }

  private List<Task> buildPrioritizedUnscheduledTasks(Set<Task> unscheduledTasks) {
    List<PrioritizedTask> prioritizedTasks = new ArrayList<>();
    if (unscheduledTasks == null || unscheduledTasks.isEmpty()) {
      return Collections.emptyList();
    }
    int maxPriority = Math.max(1, unscheduledTasks.stream()
      .filter(Objects::nonNull)
      .mapToInt(Task::getPriority)
      .max()
      .orElse(1));
    int maxCandidateCount = Math.max(1, unscheduledTasks.stream()
      .filter(Objects::nonNull)
      .mapToInt(this::countCandidateSatellites)
      .max()
      .orElse(1));
    int maxWindowCount = Math.max(1, unscheduledTasks.stream()
      .filter(Objects::nonNull)
      .mapToInt(this::countTotalWindows)
      .max()
      .orElse(1));
    for (Task task : unscheduledTasks) {
      if (task == null) {
        continue;
      }
      prioritizedTasks.add(new PrioritizedTask(
        task,
        countCandidateSatellites(task),
        countTotalWindows(task)
      ));
    }
    prioritizedTasks.sort((left, right) -> {
      double leftScore = TaskAllocationScoring.computePrioritizedTaskScore(
        left, maxPriority, maxCandidateCount, maxWindowCount, globalTaskKeys);
      double rightScore = TaskAllocationScoring.computePrioritizedTaskScore(
        right, maxPriority, maxCandidateCount, maxWindowCount, globalTaskKeys);
      int scoreCompare = Double.compare(rightScore, leftScore);
      if (scoreCompare != 0) {
        return scoreCompare;
      }
      return Integer.compare(left.task.getId(), right.task.getId());
    });
    List<Task> orderedTasks = new ArrayList<>(prioritizedTasks.size());
    for (PrioritizedTask prioritizedTask : prioritizedTasks) {
      orderedTasks.add(prioritizedTask.task);
    }
    return orderedTasks;
  }

  private int countCandidateSatellites(Task task) {
    if (task == null) {
      return 0;
    }
    int count = 0;
    for (Satellite satellite : satellites) {
      List<TimeWindow> windows = task.getTimeWindowsForSatellite(satellite.getId());
      if (windows != null && !windows.isEmpty()) {
        count++;
      }
    }
    return count;
  }

  private int countTotalWindows(Task task) {
    if (task == null) {
      return 0;
    }
    int count = 0;
    for (Satellite satellite : satellites) {
      List<TimeWindow> windows = task.getTimeWindowsForSatellite(satellite.getId());
      if (windows != null) {
        count += windows.size();
      }
    }
    return count;
  }

  private SatelliteCandidateSelection selectTopTargetSatellites(
    Task task,
    Integer currentSatelliteId,
    int currentIter,
    Map<Integer, Integer> assignmentSnapshot,
    boolean prioritizedTask
  ) {
    return targetSatelliteSelector.selectTopTargetSatellites(
      task,
      currentSatelliteId,
      currentIter,
      assignmentSnapshot,
      prioritizedTask
    );
  }

  private int countEligibleTargetSatellites(Task task, Integer currentSatelliteId, int currentIter, ReassignmentStats stats) {
    SatelliteCandidateSelection selection = selectTopTargetSatellites(
      task,
      currentSatelliteId,
      currentIter,
      taskAssignment,
      false
    );
    return selection.eligibleCount;
  }

  private Satellite getSatelliteById(int satelliteId) {
    for (Satellite satellite : satellites) {
      if (satellite.getId() == satelliteId) {
        return satellite;
      }
    }
    return null;
  }

  private void updateFailureCount(int taskId) {
    failureCount.merge(taskId, 1, Integer::sum);
  }

  private void resetFailureCount(int taskId) {
    failureCount.put(taskId, 0);
  }

  private void moveTask(Task task, int fromSatId, int toSatId) {
    if (task == null) {
      return;
    }
    if (fromSatId == toSatId) {
      return;
    }
    taskAssignment.put(task.getId(), toSatId);
    syncSatelliteTaskViews(taskAssignment);
  }

  private void syncSatelliteTaskViews(Map<Integer, Integer> assignmentSnapshot) {
    if (assignmentSnapshot == cachedAssignmentRef) {
      return;
    }
    satelliteTaskCachePrev.clear();
    satelliteTaskCachePrev.putAll(satelliteTaskCache);
    cachedAssignmentRefPrev = cachedAssignmentRef;
    satelliteTaskCache.clear();
    for (Satellite satellite : satellites) {
      satelliteTaskCache.put(satellite.getId(), new ArrayList<>());
    }
    for (Task task : allTasks) {
      Integer satelliteId = assignmentSnapshot.get(task.getId());
      if (satelliteId != null) {
        satelliteTaskCache.get(satelliteId).add(task);
      }
    }
    cachedAssignmentRef = assignmentSnapshot;
    for (Satellite satellite : satellites) {
      List<Task> assigned = satelliteTaskCache.getOrDefault(satellite.getId(), new ArrayList<>());
      satellite.setAssignedTasks(new ArrayList<>(assigned));
    }
  }

  private String selectStrategyName(int iter) {
    if (strategyPool.isEmpty()) {
      return "Unknown";
    }
    List<String> strategyNames = new ArrayList<>(strategyPool.keySet());
    return strategyNames.get(iter % strategyNames.size());
  }

  private double calculateTotalProfit(Map<Integer, SchedulingPlan> plans) {
    double totalProfit = 0.0;
    for (int i = 0; i < satellites.size(); i++) {
      SchedulingPlan plan = plans.get(satellites.get(i).getId());
      if (plan == null) {
        continue;
      }
      if (plan.isFitnessCalculated()) {
        totalProfit += plan.getFitness();
      } else {
        totalProfit += plan.getTotalProfit();
      }
    }
    return totalProfit;
  }

  private Set<Integer> getScheduledTaskIds(SchedulingPlan plan) {
    Set<Integer> scheduledIds = new LinkedHashSet<>();
    if (plan == null || plan.getSchedule() == null) {
      return scheduledIds;
    }
    for (ScheduledTask scheduledTask : plan.getSchedule()) {
      if (scheduledTask != null && scheduledTask.getTask() != null) {
        scheduledIds.add(scheduledTask.getTask().getId());
      }
    }
    return scheduledIds;
  }

  private Map<Integer, SchedulingPlan> copyPlans(Map<Integer, SchedulingPlan> plans) {
    return AssignmentDeltaEvaluator.copyPlans(plans);
  }

  private Satellite copySatelliteShallow(Satellite satellite) {
    Satellite satelliteCopy = new Satellite(satellite.getId());
    satelliteCopy.setTimeWindows(satellite.getTimeWindows());
    if (satellite.getAssignedTasks() != null) {
      satelliteCopy.setAssignedTasks(new ArrayList<>(satellite.getAssignedTasks()));
    }
    return satelliteCopy;
  }

  private List<Satellite> copySatellites(List<Satellite> satellites) {
    List<Satellite> copied = new ArrayList<>();
    for (Satellite satellite : satellites) {
      copied.add(copySatelliteShallow(satellite));
    }
    return copied;
  }

  private List<Task> copyTasks(List<Task> tasks) {
    return new ArrayList<>(tasks);
  }
}
