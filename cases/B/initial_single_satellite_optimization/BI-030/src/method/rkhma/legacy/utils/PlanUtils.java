package method.rkhma.legacy.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.IntStream;

import method.rkhma.legacy.main.ParallelPolicy;
import method.rkhma.legacy.model.AttitudeRecord;
import method.rkhma.legacy.model.Satellite;
import method.rkhma.legacy.model.ScheduledTask;
import method.rkhma.legacy.model.SchedulingPlan;
import method.rkhma.legacy.model.Task;
import method.rkhma.legacy.model.TimeWindow;
import method.rkhma.legacy.operator.insertion.GreedyInsertion;
import method.rkhma.legacy.operator.insertion.InsertionMode;
import method.rkhma.legacy.operator.insertion.InsertionStrategy;
import method.rkhma.legacy.operator.insertion.InsertionStrategyFactory;

/**
 * Scheduling plan utility class
 * Provides scheduling plan generation, evaluation, and validation utilities
 */
public class PlanUtils {

  /** Minimum region width used by Liu et al.'s dichotomy insertion search. */
  private static final double LIU_DICHOTOMY_TOLERANCE_SECONDS = 2.0;
  private static final double LIU_INSERTION_EPSILON = 1.0e-9;

  public static final class WindowPreferenceSummary {
    private long calls;
    private long totalCandidateWindows;
    private long totalTriedWindows;
    private long totalChosenRank;
    private long top1HitCount;
    private long fallbackCount;
    private long maxChosenRank;

    public long getCalls() {
      return calls;
    }

    public long getTotalCandidateWindows() {
      return totalCandidateWindows;
    }

    public long getTotalTriedWindows() {
      return totalTriedWindows;
    }

    public long getTotalChosenRank() {
      return totalChosenRank;
    }

    public long getTop1HitCount() {
      return top1HitCount;
    }

    public long getFallbackCount() {
      return fallbackCount;
    }

    public long getMaxChosenRank() {
      return maxChosenRank;
    }

    public double getAverageCandidateWindows() {
      return calls == 0 ? 0.0 : totalCandidateWindows / (double) calls;
    }

    public double getAverageTriedWindows() {
      return calls == 0 ? 0.0 : totalTriedWindows / (double) calls;
    }

    public double getAverageChosenRank() {
      return calls == 0 ? 0.0 : totalChosenRank / (double) calls;
    }

    public double getTop1HitRate() {
      return calls == 0 ? 0.0 : top1HitCount / (double) calls;
    }

    public double getFallbackRate() {
      return calls == 0 ? 0.0 : fallbackCount / (double) calls;
    }

    public void mergeFrom(GreedyInsertion.WindowPreferenceStats stats) {
      if (stats == null || stats.getCalls() == 0) {
        return;
      }
      calls += stats.getCalls();
      totalCandidateWindows += stats.getTotalCandidateWindows();
      totalTriedWindows += stats.getTotalTriedWindows();
      totalChosenRank += stats.getTotalChosenRank();
      top1HitCount += stats.getTop1HitCount();
      fallbackCount += stats.getFallbackCount();
      maxChosenRank = Math.max(maxChosenRank, stats.getMaxChosenRank());
    }
  }

  private static final ThreadLocal<WindowPreferenceSummary> WINDOW_PREFERENCE_SUMMARY =
    ThreadLocal.withInitial(WindowPreferenceSummary::new);
  private static final boolean WINDOW_PREFERENCE_STATS_ENABLED =
    Boolean.getBoolean("planutils.windowPreferenceStats");

  /**
   * Adaptive parallel threshold: populations larger than this use parallel evaluation.
   * Default is max(availableProcessors / 2, 64); override
   * planutils.parallelEvalThreshold only for benchmarking, and keep serial/parallel
   * fitness bit-exact.
   */
  private static final int PARALLEL_EVAL_THRESHOLD =
  Integer.getInteger("planutils.parallelEvalThreshold",
    Math.max(Runtime.getRuntime().availableProcessors() / 2, 64));

  public static final class TaskIdMarker {
    private static final int MAX_DENSE_TASK_ID =
      Integer.getInteger("planutils.taskIdMarker.maxDenseId", 1_000_000);

    private int[] marks = new int[0];
    private Set<Integer> sparseIds;
    private int stamp = 1;

    public void clear() {
      if (sparseIds != null) {
        sparseIds.clear();
      }
      if (stamp == Integer.MAX_VALUE) {
        Arrays.fill(marks, 0);
        stamp = 1;
      } else {
        stamp++;
      }
    }

    public boolean contains(int taskId) {
      if (usesSparseFallback(taskId)) {
        return sparseIds != null && sparseIds.contains(taskId);
      }
      return taskId >= 0 && taskId < marks.length && marks[taskId] == stamp;
    }

    public boolean add(int taskId) {
      if (usesSparseFallback(taskId)) {
        if (sparseIds == null) {
          sparseIds = new HashSet<>();
        }
        return sparseIds.add(taskId);
      }
      ensureCapacity(taskId);
      if (marks[taskId] == stamp) {
        return false;
      }
      marks[taskId] = stamp;
      return true;
    }

    public void addAll(int[] taskIds) {
      if (taskIds == null) {
        return;
      }
      for (int taskId : taskIds) {
        add(taskId);
      }
    }

    private static boolean usesSparseFallback(int taskId) {
      return taskId < 0 || taskId > MAX_DENSE_TASK_ID;
    }

    private void ensureCapacity(int taskId) {
      if (taskId < marks.length) {
        return;
      }
      int newLength = marks.length == 0 ? 16 : marks.length;
      while (newLength <= taskId) {
        newLength = newLength < 1_073_741_824 ? newLength << 1 : taskId + 1;
      }
      marks = Arrays.copyOf(marks, newLength);
    }
  }

  public static void resetWindowPreferenceSummary() {
    WINDOW_PREFERENCE_SUMMARY.set(new WindowPreferenceSummary());
  }

  public static boolean isWindowPreferenceStatsEnabled() {
    return WINDOW_PREFERENCE_STATS_ENABLED;
  }

  public static WindowPreferenceSummary getWindowPreferenceSummarySnapshot() {
    WindowPreferenceSummary source = WINDOW_PREFERENCE_SUMMARY.get();
    WindowPreferenceSummary copy = new WindowPreferenceSummary();
    copy.calls = source.calls;
    copy.totalCandidateWindows = source.totalCandidateWindows;
    copy.totalTriedWindows = source.totalTriedWindows;
    copy.totalChosenRank = source.totalChosenRank;
    copy.top1HitCount = source.top1HitCount;
    copy.fallbackCount = source.fallbackCount;
    copy.maxChosenRank = source.maxChosenRank;
    return copy;
  }

  /**
   * Plan result wrapper class
   */
  public static class PlanResult {

    private final int profit;
    private final int scheduledTaskNums;

    public PlanResult(int profit, int scheduledTaskNums) {
      this.profit = profit;
      this.scheduledTaskNums = scheduledTaskNums;
    }

    public int getProfit() {
      return profit;
    }

    public int getScheduledTaskNums() {
      return scheduledTaskNums;
    }
  }

  /**
   * Decode a chromosome (continuous vector) into a scheduling plan
   * <p>
   * The decoding process converts a real-valued vector into a task sequence by:
   * <ol>
   * <li>Sorting task indices based on their corresponding values in the vector</li>
   * <li>Creating a task sequence where higher values have higher priority</li>
   * </ol>
   * For example, if vec = [0.7, 0.3, 0.9] and allTasks = [T1, T2, T3],
   * the resulting sequence would be [T3, T1, T2] since 0.9 > 0.7 > 0.3.
   *
   * @param vec The chromosome vector (must be same length as allTasks)
   * @param allTasks The reference task list
   * @return A new SchedulingPlan with tasks ordered by their vector values in descending order
   * @throws IllegalArgumentException If vec or allTasks is null, or if their lengths don't match
   */
  public static SchedulingPlan decode(double[] vec, List<Task> assignTasks) {
    if (vec == null || assignTasks == null) {
      throw new IllegalArgumentException("Vector and task list cannot be null");
    }
    if (vec.length != assignTasks.size()) {
      throw new IllegalArgumentException(
        "Vector length (" + vec.length + ") must match task list size (" + assignTasks.size() + ")");
    }

    int n = vec.length;
    int[] order = sortIndicesByValueDesc(vec);

    List<Task> sequence = new ArrayList<>(n);
    for (int idx : order) {
      sequence.add(assignTasks.get(idx));
    }

    return new SchedulingPlan(sequence);
  }

  public static double evaluateVector(
    double[] vec,
    List<Task> tasks,
    Satellite satellite,
    InsertionMode mode,
    DecodeWorkspace ws
  ) {
    if (vec == null || tasks == null || satellite == null) {
      throw new IllegalArgumentException("Vector, task list, and satellite cannot be null");
    }
    if (vec.length != tasks.size()) {
      throw new IllegalArgumentException(
        "Vector length (" + vec.length + ") must match task list size (" + tasks.size() + ")");
    }
    DecodeWorkspace workspace = ws != null ? ws : new DecodeWorkspace();
    prepareDecodeWorkspace(vec, tasks, workspace);
    List<ScheduledTask> schedule = generateScheduleFromWorkspace(
      workspace,
      vec.length,
      satellite,
      mode,
      null,
      true
    );
    return (double) calculateProfit(schedule) + calculateNormalizedSlackBonus(schedule);
  }

  static int[] decodeTaskIdOrderForTesting(double[] vec, List<Task> tasks, DecodeWorkspace ws) {
    if (vec == null || tasks == null) {
      throw new IllegalArgumentException("Vector and task list cannot be null");
    }
    if (vec.length != tasks.size()) {
      throw new IllegalArgumentException(
        "Vector length (" + vec.length + ") must match task list size (" + tasks.size() + ")");
    }
    DecodeWorkspace workspace = ws != null ? ws : new DecodeWorkspace();
    prepareDecodeWorkspace(vec, tasks, workspace);
    int[] ids = new int[vec.length];
    for (int i = 0; i < vec.length; i++) {
      ids[i] = workspace.sequence[i].getId();
    }
    return ids;
  }

  static int[] sortIndicesByValueDesc(double[] vec) {
    int n = vec.length;
    int[] order = new int[n];
    int[] scratch = new int[n];
    for (int i = 0; i < n; i++) {
      order[i] = i;
    }
    mergeSortIndicesByValueDesc(vec, order, scratch, 0, n);
    return order;
  }

  private static void prepareDecodeWorkspace(double[] vec, List<Task> tasks, DecodeWorkspace ws) {
    int n = vec.length;
    ws.ensureCapacity(n);
    for (int i = 0; i < n; i++) {
      ws.order[i] = i;
    }
    mergeSortIndicesByValueDesc(vec, ws.order, ws.scratch, 0, n);
    for (int i = 0; i < n; i++) {
      ws.sequence[i] = tasks.get(ws.order[i]);
    }
  }

  private static void mergeSortIndicesByValueDesc(double[] vec, int[] order, int[] scratch, int left, int right) {
    int length = right - left;
    if (length <= 1) {
      return;
    }
    int mid = left + (length >>> 1);
    mergeSortIndicesByValueDesc(vec, order, scratch, left, mid);
    mergeSortIndicesByValueDesc(vec, order, scratch, mid, right);
    int i = left;
    int j = mid;
    int k = left;
    while (i < mid && j < right) {
      int leftIndex = order[i];
      int rightIndex = order[j];
      if (compareDecodeOrder(vec, leftIndex, rightIndex) <= 0) {
        scratch[k++] = leftIndex;
        i++;
      } else {
        scratch[k++] = rightIndex;
        j++;
      }
    }
    while (i < mid) {
      scratch[k++] = order[i++];
    }
    while (j < right) {
      scratch[k++] = order[j++];
    }
    System.arraycopy(scratch, left, order, left, length);
  }

  private static int compareDecodeOrder(double[] vec, int leftIndex, int rightIndex) {
    int byValue = Double.compare(vec[rightIndex], vec[leftIndex]);
    if (byValue != 0) {
      return byValue;
    }
    return Integer.compare(leftIndex, rightIndex);
  }

  /**
   * Encode a population of scheduling plans into chromosomes
   *
   * @param plans List of scheduling plans
   * @param allTasksRef Reference task list
   * @return List of encoded chromosomes (same size as input plans)
   * @throws IllegalArgumentException If plans or task list is null or invalid
   */
  public static List<double[]> encodePopulation(List<SchedulingPlan> plans, List<Task> allTasksRef) {
    if (plans == null || allTasksRef == null) {
      throw new IllegalArgumentException("Plans and task list cannot be null");
    }
    if (allTasksRef.isEmpty()) {
      throw new IllegalArgumentException("Task list cannot be empty");
    }

    List<double[]> encodedPopulation = new ArrayList<>(plans.size());
    for (SchedulingPlan plan : plans) {
      encodedPopulation.add(encodePlan(plan, allTasksRef));
    }

    return encodedPopulation;
  }

  public static double[] encodePlan(SchedulingPlan plan, List<Task> allTasksRef) {
    if (plan == null || allTasksRef == null) {
      throw new IllegalArgumentException("Plan and task list cannot be null");
    }
    if (allTasksRef.isEmpty()) {
      throw new IllegalArgumentException("Task list cannot be empty");
    }

    int geneLength = allTasksRef.size();
    Map<Integer, Integer> idToGeneIndexMap = new HashMap<>(geneLength);
    for (int i = 0; i < geneLength; i++) {
      idToGeneIndexMap.put(allTasksRef.get(i).getId(), i);
    }

    List<Task> sequence = plan.getTaskSequence();
    if (sequence == null || sequence.size() != geneLength) {
      throw new IllegalArgumentException(
        "Plan sequence size must match task list size (" + geneLength + ")");
    }

    double[] chromosome = new double[geneLength];
    int size = sequence.size();

    for (int rank = 0; rank < size; rank++) {
      Task task = sequence.get(rank);
      if (task == null) {
        throw new IllegalArgumentException("Task in sequence cannot be null");
      }

      Integer geneIndex = idToGeneIndexMap.get(task.getId());
      if (geneIndex == null) {
        throw new IllegalArgumentException(
          "Task ID " + task.getId() + " not found in reference task list");
      }
      double weight = (double) (size - rank) / size;
      chromosome[geneIndex] = weight;
    }
    return chromosome;
  }

  /**
   * Find the best scheduling plan in a population
   * 
   * @param population Population
   * @param satellite Satellite
   * @param allTasks All tasks
   * @return A copy of the best scheduling plan, or null if population is empty
   * @throws IllegalArgumentException If parameters are invalid
   */
  public static SchedulingPlan findBestInPopulation(
    List<SchedulingPlan> population, Satellite satellite, List<Task> allTasks
  ) {
    if (population == null || population.isEmpty()) {
      return null;
    }
    if (satellite == null || allTasks == null) {
      throw new IllegalArgumentException("Satellite and task list cannot be null");
    }

    SchedulingPlan best = null;
    double bestFitness = Double.NEGATIVE_INFINITY;

    for (SchedulingPlan plan : population) {
      if (plan == null) {
        continue;
      }

      // If fitness is not evaluated (null), evaluate it
      Double fitness = plan.getFitness();
      if (fitness == null) {
        fitness = evaluatePlan(plan, satellite, InsertionMode.GREEDY);
      }

      if (fitness > bestFitness) {
        bestFitness = fitness;
        best = plan;
      }
    }

    return best != null ? best.copy() : null;
  }

  /**
   * Check whether the scheduling plan contains repeated tasks
   * 
   * @param plan Scheduling plan
   * @return true if there are no repeated tasks; otherwise false
   * @throws IllegalArgumentException If plan is null
   */
  public static boolean checkRepeatedTasks(SchedulingPlan plan) {
    if (plan == null) {
      throw new IllegalArgumentException("Plan cannot be null");
    }

    List<ScheduledTask> schedule = plan.getSchedule();
    if (schedule == null || schedule.isEmpty()) {
      return true; // Empty schedule has no repeats
    }

    Set<Integer> taskSet = new HashSet<>(schedule.size());

    for (ScheduledTask task : schedule) {
      if (task == null) {
        continue;
      }
      if (!taskSet.add(task.getTaskId())) {
        return false; // Found a repeated task
      }
    }

    return true; // No repeated tasks
  }

  /**
   * Check whether the scheduling plan satisfies transition time constraints
   *
   * @param plan Scheduling plan
   * @return true if all constraints are satisfied; otherwise false
   * @throws IllegalArgumentException If plan is null
   */
  public static boolean checkTransitionTimeConstraints(SchedulingPlan plan) {
    if (plan == null) {
      throw new IllegalArgumentException("Plan cannot be null");
    }

    List<ScheduledTask> schedule = plan.getSchedule();
    if (schedule == null || schedule.size() <= 1) {
      return true; // Empty schedule or a single task does not need constraint checks
    }

    for (int i = 1; i < schedule.size(); i++) {
      ScheduledTask prevTask = schedule.get(i - 1);
      ScheduledTask currTask = schedule.get(i);

      if (prevTask == null || currTask == null) {
        continue;
      }

      if (!TimeUtils.checkTransformationTime(
        prevTask.getEndRecord(),
        currTask.getStartRecord()
      )) {
        return false; // Constraint violated
      }
    }
    return true; // All constraints satisfied
  }

  /**
   * Sort task sequence by earliest start time
   * 
   * @param taskSequence Task sequence (modified in place)
   * @param satelliteId Satellite ID
   * @throws IllegalArgumentException If task sequence is null
   */
  public static void sortTaskSequenceByEarliestStart(
    List<Task> taskSequence, int satelliteId) {
    if (taskSequence == null) {
      throw new IllegalArgumentException("Task sequence cannot be null");
    }

    // Use Java built-in sort (TimSort O(n log n))
    taskSequence.sort((t1, t2) -> {
        if (t1 == null && t2 == null) return 0;
        if (t1 == null) return 1;
        if (t2 == null) return -1;

        double e1 = earliestStart(t1, satelliteId);
        double e2 = earliestStart(t2, satelliteId);
        return Double.compare(e1, e2);
      });
  }

  /**
   * Get the earliest start time of a task on a given satellite
   *
   * @param task Task
   * @param satelliteId Satellite ID
   * @return Earliest start time, or Double.MAX_VALUE if there are no available windows
   */
  private static double earliestStart(Task task, int satelliteId) {
    if (task == null) {
      return Double.MAX_VALUE;
    }

    List<TimeWindow> windows = task.getTimeWindowsForSatellite(satelliteId);
    if (windows == null || windows.isEmpty()) {
      return Double.MAX_VALUE;
    }

    return windows.stream()
    .mapToDouble(TimeWindow::getStartTime)
    .min()
    .orElse(Double.MAX_VALUE);
  }

  /**
   * Generate schedule from a scheduling plan
   *
   * @param schedulingPlan The scheduling plan
   * @param satellite The satellite
   * @param mode Insertion mode (GREEDY / MIN_TRANSITION / MAX_SLACK / SHORTEST_WINDOW_FIRST / EARLIEST_DEADLINE_FIRST / RANDOM / LONGEST_WINDOW_FIRST / MAX_REMAINING_CAPACITY)
   * @return Generated schedule
   * @throws IllegalArgumentException If parameters are null
   */
  public static List<ScheduledTask> generateSchedule(
    SchedulingPlan schedulingPlan,
    Satellite satellite,
    InsertionMode mode
  ) {
    return generateSchedule(schedulingPlan, satellite, mode, false);
  }

  public static List<ScheduledTask> generateSchedule(
    SchedulingPlan schedulingPlan,
    Satellite satellite,
    InsertionMode mode,
    boolean enableWindowPreferenceForGreedy
  ) {
    return generateSchedule(
      schedulingPlan,
      satellite,
      mode,
      enableWindowPreferenceForGreedy,
      enableWindowPreferenceForGreedy ? collectTaskIds(schedulingPlan.getTaskSequence()) : null
    );
  }

  public static List<ScheduledTask> generateSchedule(
    SchedulingPlan schedulingPlan,
    Satellite satellite,
    InsertionMode mode,
    boolean enableWindowPreferenceForGreedy,
    Set<Integer> windowPreferenceTaskIds
  ) {
    if (schedulingPlan == null || satellite == null) {
      throw new IllegalArgumentException("Parameters cannot be null");
    }
    if (mode == null) {
      mode = InsertionMode.GREEDY;
    }

    int satelliteId = satellite.getId();
    List<Task> taskSequence = schedulingPlan.getTaskSequence();

    if (satellite.getScheduleBuilder() != null) {
      Set<Integer> preferred = enableWindowPreferenceForGreedy && windowPreferenceTaskIds != null
          ? Set.copyOf(windowPreferenceTaskIds) : Set.of();
      var built = new ArrayList<>(satellite.getScheduleBuilder().buildWithPreferences(satellite, List.copyOf(taskSequence), preferred));
      schedulingPlan.setScheduleUnsafe(built);
      return built;
    }

    List<ScheduledTask> currentSchedule = new ArrayList<>();
    TaskIdMarker scheduledTaskIds = new TaskIdMarker();
    GreedyInsertion.ReusableInsertionResult greedyResult =
      mode == InsertionMode.GREEDY ? new GreedyInsertion.ReusableInsertionResult() : null;

    if (taskSequence == null || taskSequence.isEmpty()) {
      return currentSchedule;
    }

    InsertionStrategy strategy = InsertionStrategyFactory.getStrategy(mode, satellite);
    GreedyInsertion greedyInsertion =
      mode == InsertionMode.GREEDY && strategy instanceof GreedyInsertion
        ? (GreedyInsertion) strategy
        : null;

    for (Task task : taskSequence) {
      if (task == null) {
        continue;
      }
      if (scheduledTaskIds.contains(task.getId())) {
        continue;
      }

      List<TimeWindow> windows = task.getTimeWindowsForSatellite(satelliteId);
      if (windows == null || windows.isEmpty()) {
        continue;
      }

      boolean useWindowPreference = greedyInsertion != null
        && enableWindowPreferenceForGreedy
        && windowPreferenceTaskIds != null
        && windowPreferenceTaskIds.contains(task.getId());
      GreedyInsertion.WindowPreferenceStats windowPreferenceStats =
        useWindowPreference && WINDOW_PREFERENCE_STATS_ENABLED
          ? new GreedyInsertion.WindowPreferenceStats()
          : null;
      boolean inserted;
      if (greedyInsertion != null) {
        inserted = useWindowPreference
          ? greedyInsertion.findBestPositionWithWindowPreferenceInto(
            task,
            satelliteId,
            currentSchedule,
            0.0,
            windowPreferenceStats,
            greedyResult
          )
          : greedyInsertion.findBestPositionInto(task, satelliteId, currentSchedule, greedyResult);
      } else {
        InsertionStrategy.InsertionResult result = strategy.findBestPosition(task, satelliteId, currentSchedule);
        inserted = result != null;
        if (inserted) {
          currentSchedule.add(result.getInsertIndex(), result.getTask());
        }
      }
      if (windowPreferenceStats != null) {
        WINDOW_PREFERENCE_SUMMARY.get().mergeFrom(windowPreferenceStats);
      }
      if (greedyInsertion != null && inserted) {
        currentSchedule.add(greedyResult.getInsertIndex(), greedyResult.getTask());
      }
      if (inserted) {
        scheduledTaskIds.add(task.getId());
      }
    }

    schedulingPlan.setScheduleUnsafe(currentSchedule);
    return currentSchedule;
  }

  private static List<ScheduledTask> generateScheduleFromWorkspace(
    DecodeWorkspace ws,
    int sequenceSize,
    Satellite satellite,
    InsertionMode mode,
    Set<Integer> windowPreferenceTaskIds,
    boolean assumeUniqueSequence
  ) {
    if (mode == null) {
      mode = InsertionMode.GREEDY;
    }
    ws.clearScheduleState();
    if (sequenceSize == 0) {
      return ws.schedule;
    }

    if (satellite.getScheduleBuilder() != null) {
      return satellite.getScheduleBuilder().buildWithPreferences(satellite,
          java.util.Arrays.asList(java.util.Arrays.copyOf(ws.sequence, sequenceSize)),
          windowPreferenceTaskIds == null ? Set.of() : Set.copyOf(windowPreferenceTaskIds));
    }

    int satelliteId = satellite.getId();
    InsertionStrategy strategy = InsertionStrategyFactory.getStrategy(mode, satellite);
    GreedyInsertion greedyInsertion =
      mode == InsertionMode.GREEDY && strategy instanceof GreedyInsertion
        ? (GreedyInsertion) strategy
        : null;

    for (int i = 0; i < sequenceSize; i++) {
      Task task = ws.sequence[i];
      if (task == null) {
        continue;
      }
      if (!assumeUniqueSequence && ws.scheduledTaskIds.contains(task.getId())) {
        continue;
      }

      List<TimeWindow> windows = task.getTimeWindowsForSatellite(satelliteId);
      if (windows == null || windows.isEmpty()) {
        continue;
      }

      boolean useWindowPreference = greedyInsertion != null
        && windowPreferenceTaskIds != null
        && windowPreferenceTaskIds.contains(task.getId());
      GreedyInsertion.WindowPreferenceStats windowPreferenceStats =
        useWindowPreference && WINDOW_PREFERENCE_STATS_ENABLED
          ? new GreedyInsertion.WindowPreferenceStats()
          : null;
      boolean inserted;
      if (greedyInsertion != null) {
        inserted = useWindowPreference
          ? greedyInsertion.findBestPositionWithWindowPreferenceInto(
            task,
            satelliteId,
            ws.schedule,
            0.0,
            windowPreferenceStats,
            ws.greedyInsertionResult
          )
          : greedyInsertion.findBestPositionInto(task, satelliteId, ws.schedule, ws.greedyInsertionResult);
      } else {
        InsertionStrategy.InsertionResult result = strategy.findBestPosition(task, satelliteId, ws.schedule);
        inserted = result != null;
        if (inserted) {
          ws.schedule.add(result.getInsertIndex(), result.getTask());
        }
      }
      if (windowPreferenceStats != null) {
        WINDOW_PREFERENCE_SUMMARY.get().mergeFrom(windowPreferenceStats);
      }
      if (greedyInsertion != null && inserted) {
        ws.schedule.add(ws.greedyInsertionResult.getInsertIndex(), ws.greedyInsertionResult.getTask());
      }
      if (inserted) {
        if (!assumeUniqueSequence) {
          ws.scheduledTaskIds.add(task.getId());
        }
      }
    }
    return ws.schedule;
  }

  // ── Adjacent Swap incremental evaluation support ──

  /**
   * Full schedule generation with snapshot recording. Records (currentSchedule, scheduledTaskIds)
   * snapshots at specified positions for reuse in subsequent incremental evaluations.
   */
  public static ScheduleSnapshotResult generateScheduleWithSnapshots(
    List<Task> taskSequence,
    Satellite satellite,
    InsertionMode mode,
    Set<Integer> snapshotPositions
  ) {
    if (taskSequence == null || satellite == null) {
      return new ScheduleSnapshotResult(new ArrayList<>(), new HashMap<>(), new HashMap<>());
    }
    int[] positions = snapshotPositions.stream().mapToInt(Integer::intValue).sorted().toArray();
    return generateScheduleWithSnapshots(taskSequence, satellite, mode, positions, 0, -1);
  }

  /** Records snapshots for an inclusive range without allocating a boxed position set. */
  public static ScheduleSnapshotResult generateScheduleWithSnapshotRange(
    List<Task> taskSequence,
    Satellite satellite,
    InsertionMode mode,
    int firstSnapshotPosition,
    int lastSnapshotPosition
  ) {
    return generateScheduleWithSnapshots(
      taskSequence, satellite, mode, null, firstSnapshotPosition, lastSnapshotPosition);
  }

  private static ScheduleSnapshotResult generateScheduleWithSnapshots(
    List<Task> taskSequence,
    Satellite satellite,
    InsertionMode mode,
    int[] snapshotPositions,
    int firstSnapshotPosition,
    int lastSnapshotPosition
  ) {
    if (taskSequence == null || satellite == null) {
      return new ScheduleSnapshotResult(new ArrayList<>(), new HashMap<>(), new HashMap<>());
    }
    if (mode == null) mode = InsertionMode.GREEDY;
    int satelliteId = satellite.getId();
    InsertionStrategy strategy = InsertionStrategyFactory.getStrategy(mode, satellite);
    GreedyInsertion greedyInsertion =
      mode == InsertionMode.GREEDY && strategy instanceof GreedyInsertion
        ? (GreedyInsertion) strategy
        : null;
    GreedyInsertion.ReusableInsertionResult greedyResult =
      greedyInsertion != null ? new GreedyInsertion.ReusableInsertionResult() : null;

    List<ScheduledTask> currentSchedule = new ArrayList<>();
    TaskIdMarker scheduledTaskIds = new TaskIdMarker();
    int[] scheduledTaskIdBuffer = new int[taskSequence.size()];
    int scheduledTaskIdCount = 0;
    Map<Integer, List<ScheduledTask>> scheduleSnapshots = new HashMap<>();
    Map<Integer, int[]> scheduledIdSnapshots = new HashMap<>();
    int snapshotCursor = 0;
    if (snapshotPositions != null) {
      while (snapshotCursor < snapshotPositions.length && snapshotPositions[snapshotCursor] < 0) {
        snapshotCursor++;
      }
    }

    for (int i = 0; i < taskSequence.size(); i++) {
      Task task = taskSequence.get(i);
      if (task != null && !scheduledTaskIds.contains(task.getId())) {
        List<TimeWindow> windows = task.getTimeWindowsForSatellite(satelliteId);
        if (windows != null && !windows.isEmpty()) {
          boolean inserted;
          if (greedyInsertion != null) {
            inserted = greedyInsertion.findBestPositionInto(
              task, satelliteId, currentSchedule, greedyResult);
          } else {
            InsertionStrategy.InsertionResult result =
              strategy.findBestPosition(task, satelliteId, currentSchedule);
            inserted = result != null;
            if (inserted) {
              currentSchedule.add(result.getInsertIndex(), result.getTask());
            }
          }
          if (greedyInsertion != null && inserted) {
            currentSchedule.add(greedyResult.getInsertIndex(), greedyResult.getTask());
          }
          if (inserted) {
            scheduledTaskIds.add(task.getId());
            scheduledTaskIdBuffer[scheduledTaskIdCount++] = task.getId();
          }
        }
      }
      boolean captureSnapshot = snapshotPositions == null
        ? i >= firstSnapshotPosition && i <= lastSnapshotPosition
        : snapshotCursor < snapshotPositions.length && snapshotPositions[snapshotCursor] == i;
      if (captureSnapshot) {
        scheduleSnapshots.put(i, new ArrayList<>(currentSchedule));
        scheduledIdSnapshots.put(i, Arrays.copyOf(scheduledTaskIdBuffer, scheduledTaskIdCount));
        snapshotCursor++;
      }
    }
    return new ScheduleSnapshotResult(currentSchedule, scheduleSnapshots, scheduledIdSnapshots);
  }

  /**
   * Incremental schedule generation starting from a snapshot position.
   * Reuses prefix state and only processes tasks from startIndex onward.
   */
  public static List<ScheduledTask> generateScheduleFromSnapshot(
    List<Task> taskSequence,
    Satellite satellite,
    InsertionMode mode,
    List<ScheduledTask> scheduleSnapshot,
    int[] scheduledIdsSnapshot,
    int startIndex
  ) {
    return generateScheduleFromSnapshot(
      taskSequence,
      satellite,
      mode,
      scheduleSnapshot,
      scheduledIdsSnapshot,
      startIndex,
      null
    );
  }

  public static List<ScheduledTask> generateScheduleFromSnapshot(
    List<Task> taskSequence,
    Satellite satellite,
    InsertionMode mode,
    List<ScheduledTask> scheduleSnapshot,
    int[] scheduledIdsSnapshot,
    int startIndex,
    TaskIdMarker markerScratch
  ) {
    if (mode == null) mode = InsertionMode.GREEDY;
    int satelliteId = satellite.getId();
    InsertionStrategy strategy = InsertionStrategyFactory.getStrategy(mode, satellite);
    GreedyInsertion greedyInsertion =
      mode == InsertionMode.GREEDY && strategy instanceof GreedyInsertion
        ? (GreedyInsertion) strategy
        : null;
    GreedyInsertion.ReusableInsertionResult greedyResult =
      greedyInsertion != null ? new GreedyInsertion.ReusableInsertionResult() : null;

    List<ScheduledTask> currentSchedule = new ArrayList<>(scheduleSnapshot);
    TaskIdMarker scheduledTaskIds = markerScratch != null ? markerScratch : new TaskIdMarker();
    scheduledTaskIds.clear();
    scheduledTaskIds.addAll(scheduledIdsSnapshot);

    for (int i = startIndex; i < taskSequence.size(); i++) {
      Task task = taskSequence.get(i);
      if (task == null || scheduledTaskIds.contains(task.getId())) continue;
      List<TimeWindow> windows = task.getTimeWindowsForSatellite(satelliteId);
      if (windows == null || windows.isEmpty()) continue;
      boolean inserted;
      if (greedyInsertion != null) {
        inserted = greedyInsertion.findBestPositionInto(
          task, satelliteId, currentSchedule, greedyResult);
      } else {
        InsertionStrategy.InsertionResult result =
          strategy.findBestPosition(task, satelliteId, currentSchedule);
        inserted = result != null;
        if (inserted) {
          currentSchedule.add(result.getInsertIndex(), result.getTask());
        }
      }
      if (greedyInsertion != null && inserted) {
        currentSchedule.add(greedyResult.getInsertIndex(), greedyResult.getTask());
      }
      if (inserted) {
        scheduledTaskIds.add(task.getId());
      }
    }
    return currentSchedule;
  }

  /**
   * Replays [startIndex, endIndex) in the caller-owned prefix list. The caller must
   * rollback in a finally block and copy any accepted schedule before rollback.
   * Existing scheduled elements are read-only; only successful insertions are logged.
   */
  public static List<ScheduledTask> generateScheduleFromSnapshotInPlace(
    List<Task> taskSequence,
    Satellite satellite,
    InsertionMode mode,
    List<ScheduledTask> scheduleSnapshot,
    int[] scheduledIdsSnapshot,
    int startIndex,
    int endIndex,
    SnapshotReplayScratch replayScratch
  ) {
    replayScratch.prepare(taskSequence.size(), scheduledIdsSnapshot);
    return replayScheduleRangeInPlace(
      taskSequence, satellite, mode, scheduleSnapshot, startIndex, endIndex, replayScratch);
  }

  /** Continues a prepared replay, retaining its task-id marker and rollback log. */
  public static List<ScheduledTask> continueScheduleFromSnapshotInPlace(
    List<Task> taskSequence,
    Satellite satellite,
    InsertionMode mode,
    List<ScheduledTask> scheduleSnapshot,
    int startIndex,
    SnapshotReplayScratch replayScratch
  ) {
    return replayScheduleRangeInPlace(
      taskSequence, satellite, mode, scheduleSnapshot, startIndex, taskSequence.size(), replayScratch);
  }

  private static List<ScheduledTask> replayScheduleRangeInPlace(
    List<Task> taskSequence,
    Satellite satellite,
    InsertionMode mode,
    List<ScheduledTask> scheduleSnapshot,
    int startIndex,
    int endIndex,
    SnapshotReplayScratch replayScratch
  ) {
    if (mode == null) mode = InsertionMode.GREEDY;
    int satelliteId = satellite.getId();
    InsertionStrategy strategy = InsertionStrategyFactory.getStrategy(mode, satellite);
    GreedyInsertion greedyInsertion =
      mode == InsertionMode.GREEDY && strategy instanceof GreedyInsertion
        ? (GreedyInsertion) strategy
        : null;
    for (int i = startIndex; i < endIndex; i++) {
      Task task = taskSequence.get(i);
      if (task == null || replayScratch.scheduledTaskIds.contains(task.getId())) continue;
      List<TimeWindow> windows = task.getTimeWindowsForSatellite(satelliteId);
      if (windows == null || windows.isEmpty()) continue;
      int insertIndex;
      ScheduledTask insertedTask;
      if (greedyInsertion != null) {
        if (!greedyInsertion.findBestPositionInto(
          task, satelliteId, scheduleSnapshot, replayScratch.greedyResult)) continue;
        insertIndex = replayScratch.greedyResult.getInsertIndex();
        insertedTask = replayScratch.greedyResult.getTask();
      } else {
        InsertionStrategy.InsertionResult result =
          strategy.findBestPosition(task, satelliteId, scheduleSnapshot);
        if (result == null) continue;
        insertIndex = result.getInsertIndex();
        insertedTask = result.getTask();
      }
      scheduleSnapshot.add(insertIndex, insertedTask);
      replayScratch.insertedIndices[replayScratch.insertedCount++] = insertIndex;
      replayScratch.scheduledTaskIds.add(task.getId());
    }
    return scheduleSnapshot;
  }

  /** Run-owned replay buffers; not shared between concurrent refiners. */
  public static final class SnapshotReplayScratch {
    private final TaskIdMarker scheduledTaskIds = new TaskIdMarker();
    private final GreedyInsertion.ReusableInsertionResult greedyResult =
      new GreedyInsertion.ReusableInsertionResult();
    private int[] insertedIndices = new int[0];
    private int insertedCount;

    private void prepare(int sequenceSize, int[] scheduledIdsSnapshot) {
      if (insertedCount != 0) {
        throw new IllegalStateException("Previous snapshot replay must be rolled back first");
      }
      if (insertedIndices.length < sequenceSize) {
        insertedIndices = new int[sequenceSize];
      }
      scheduledTaskIds.clear();
      scheduledTaskIds.addAll(scheduledIdsSnapshot);
    }

    /** Removes replay insertions in reverse order, restoring the exact original prefix. */
    public void rollback(List<ScheduledTask> scheduleSnapshot) {
      while (insertedCount > 0) {
        scheduleSnapshot.remove(insertedIndices[--insertedCount]);
      }
    }
  }

  /** Schedule generation result with snapshot recording */
  public static class ScheduleSnapshotResult {
    public final List<ScheduledTask> finalSchedule;
    public final Map<Integer, List<ScheduledTask>> scheduleSnapshots;
    public final Map<Integer, int[]> scheduledIdSnapshots;

    ScheduleSnapshotResult(
      List<ScheduledTask> finalSchedule,
      Map<Integer, List<ScheduledTask>> scheduleSnapshots,
      Map<Integer, int[]> scheduledIdSnapshots
    ) {
      this.finalSchedule = finalSchedule;
      this.scheduleSnapshots = scheduleSnapshots;
      this.scheduledIdSnapshots = scheduledIdSnapshots;
    }
  }

  /**
   * Evaluate a scheduling plan with the specified insertion mode.
   *
   * @param plan The scheduling plan
   * @param satellite The satellite
   * @param mode Insertion mode used to build the schedule
   * @return Fitness value based on scheduled profit
   * @throws IllegalArgumentException If parameters are null
   */
  public static double evaluatePlan(SchedulingPlan plan, Satellite satellite, InsertionMode mode) {
    return evaluatePlanWithScheduleOption(plan, satellite, mode, false);
  }

  public static double evaluateTaskSequence(
    List<Task> sequence,
    Satellite satellite,
    InsertionMode mode
  ) {
    return evaluateTaskSequence(sequence, satellite, mode, null);
  }

  public static double evaluateTaskSequence(
    List<Task> sequence,
    Satellite satellite,
    InsertionMode mode,
    DecodeWorkspace ws
  ) {
    if (sequence == null || satellite == null) {
      throw new IllegalArgumentException("Task sequence and satellite cannot be null");
    }
    DecodeWorkspace workspace = ws != null ? ws : new DecodeWorkspace();
    int size = sequence.size();
    workspace.ensureCapacity(size);
    for (int i = 0; i < size; i++) {
      workspace.sequence[i] = sequence.get(i);
    }
    List<ScheduledTask> schedule = generateScheduleFromWorkspace(
      workspace,
      size,
      satellite,
      mode,
      null,
      false
    );
    return (double) calculateProfit(schedule) + calculateNormalizedSlackBonus(schedule);
  }

  public static double evaluateUniqueTaskSequence(
    List<Task> sequence,
    Satellite satellite,
    InsertionMode mode,
    DecodeWorkspace ws
  ) {
    if (sequence == null || satellite == null) {
      throw new IllegalArgumentException("Task sequence and satellite cannot be null");
    }
    DecodeWorkspace workspace = ws != null ? ws : new DecodeWorkspace();
    int size = sequence.size();
    workspace.ensureCapacity(size);
    for (int i = 0; i < size; i++) {
      workspace.sequence[i] = sequence.get(i);
    }
    List<ScheduledTask> schedule = generateScheduleFromWorkspace(
      workspace,
      size,
      satellite,
      mode,
      null,
      true
    );
    return (double) calculateProfit(schedule) + calculateNormalizedSlackBonus(schedule);
  }

  private static double evaluatePlanWithScheduleOption(
    SchedulingPlan plan,
    Satellite satellite,
    InsertionMode mode,
    boolean enableWindowPreferenceForGreedy
  ) {
    return evaluatePlanWithSelectiveWindowPreference(
      plan,
      satellite,
      mode,
      enableWindowPreferenceForGreedy ? collectTaskIds(plan.getTaskSequence()) : null
    );
  }

  private static double evaluatePlanWithSelectiveWindowPreference(
    SchedulingPlan plan,
    Satellite satellite,
    InsertionMode mode,
    Set<Integer> windowPreferenceTaskIds
  ) {
    if (plan == null || satellite == null) {
      throw new IllegalArgumentException("Parameters cannot be null");
    }

    List<ScheduledTask> schedule = generateSchedule(
      plan,
      satellite,
      mode,
      windowPreferenceTaskIds != null && !windowPreferenceTaskIds.isEmpty(),
      windowPreferenceTaskIds
    );

    int profit = calculateProfit(schedule);
    double slackBonus = calculateNormalizedSlackBonus(schedule);
    double fitness = (double) profit + slackBonus;
    plan.setFitness(fitness);
    return fitness;
  }

  public static SchedulingPlan buildPlanByLimitedInsertion(
    List<Task> baseSequence,
    List<Task> addedTasks,
    Satellite satellite,
    InsertionMode mode,
    int maxCandidatePositions
  ) {
    return buildPlanByLimitedInsertion(
      baseSequence, addedTasks, satellite, mode, maxCandidatePositions, false);
  }

  public static SchedulingPlan buildPlanByLimitedInsertion(
    List<Task> baseSequence,
    List<Task> addedTasks,
    Satellite satellite,
    InsertionMode mode,
    int maxCandidatePositions,
    boolean enableWindowPreferenceForGreedy
  ) {
    return buildPlanByInsertion(
      baseSequence,
      addedTasks,
      satellite,
      mode,
      maxCandidatePositions,
      enableWindowPreferenceForGreedy,
      null,
      true
    );
  }

  /**
   * Insert only tasks that are actually scheduled by the candidate plan.
   * Unscheduled tasks remain in the caller's task bank instead of being mixed
   * into the sequence passed to the next destroy step.
   */
  public static SchedulingPlan buildPlanByStrictInsertion(
    List<Task> baseSequence,
    List<Task> addedTasks,
    Satellite satellite,
    InsertionMode mode,
    int maxCandidatePositions
  ) {
    if (baseSequence == null || addedTasks == null || satellite == null) {
      throw new IllegalArgumentException("Base sequence, added tasks, and satellite cannot be null");
    }
    // The argument is retained for source compatibility with the old limited path.
    // Liu's insertion determines the chronological position from the selected OW start
    // time and does not use this legacy sequence-position limit.
    return buildPlanByLiuStrictInsertion(baseSequence, addedTasks, satellite);
  }

  /**
   * Continue strict insertion from an existing chronological schedule.
   * Existing scheduled tasks keep their selected window and observation records;
   * only the added tasks are inserted by Liu's strict insertion rule.
   */
  public static SchedulingPlan buildPlanByStrictInsertion(
    SchedulingPlan basePlan,
    List<Task> addedTasks,
    Satellite satellite,
    InsertionMode mode,
    int maxCandidatePositions
  ) {
    if (basePlan == null || addedTasks == null || satellite == null) {
      throw new IllegalArgumentException("Base plan, added tasks, and satellite cannot be null");
    }
    return buildPlanByLiuStrictInsertion(basePlan, addedTasks, satellite);
  }

  /**
   * Rebuild a strict single-satellite solution with Liu et al.'s fast insertion.
   *
   * <p>The current model stores one-second attitude records but no orbit/track id.
   * Therefore the whole chronological schedule is treated as one propagation segment.
   * For each task, the implementation follows Liu et al.'s order: try the middle of
   * each VTW, move the inserted observation with dichotomy when an adjacent transition
   * fails, then apply the FTS/BTS filter and forward/backward neighbor propagation.
   * Direct candidates are visited in a middle-first dichotomy order and are checked
   * against only the adjacent observations before a full schedule copy is made. A
   * task is added to the returned sequence only after a feasible schedule containing
   * that task has been constructed.</p>
   */
  private static SchedulingPlan buildPlanByLiuStrictInsertion(
    List<Task> baseSequence,
    List<Task> addedTasks,
    Satellite satellite
  ) {
    List<ScheduledTask> schedule = new ArrayList<>();
    Set<Integer> seenTaskIds = new HashSet<>();

    for (Task task : deduplicateTaskSequence(baseSequence)) {
      if (!seenTaskIds.add(task.getId())) {
        continue;
      }
      List<ScheduledTask> inserted = findLiuStrictInsertion(task, schedule, satellite);
      if (inserted != null) {
        schedule = inserted;
      }
    }
    for (Task task : deduplicateTaskSequence(addedTasks)) {
      if (!seenTaskIds.add(task.getId())) {
        continue;
      }
      List<ScheduledTask> inserted = findLiuStrictInsertion(task, schedule, satellite);
      if (inserted != null) {
        schedule = inserted;
      }
    }

    return buildStrictPlan(schedule);
  }

  private static SchedulingPlan buildPlanByLiuStrictInsertion(
    SchedulingPlan basePlan,
    List<Task> addedTasks,
    Satellite satellite
  ) {
    List<ScheduledTask> schedule = new ArrayList<>();
    Set<Integer> seenTaskIds = new HashSet<>();
    for (ScheduledTask scheduledTask : copyScheduledTasks(basePlan.getSchedule())) {
      if (scheduledTask != null
        && scheduledTask.isScheduled()
        && seenTaskIds.add(scheduledTask.getTaskId())) {
        schedule.add(scheduledTask);
      }
    }

    for (Task task : deduplicateTaskSequence(addedTasks)) {
      if (!seenTaskIds.add(task.getId())) {
        continue;
      }
      List<ScheduledTask> inserted = findLiuStrictInsertion(task, schedule, satellite);
      if (inserted != null) {
        schedule = inserted;
      } else {
        seenTaskIds.remove(task.getId());
      }
    }
    return buildStrictPlan(schedule);
  }

  private static SchedulingPlan buildStrictPlan(List<ScheduledTask> schedule) {
    List<Task> sequence = new ArrayList<>(schedule.size());
    for (ScheduledTask scheduledTask : schedule) {
      if (scheduledTask != null && scheduledTask.isScheduled()) {
        sequence.add(scheduledTask.getTask());
      }
    }
    SchedulingPlan plan = new SchedulingPlan(sequence);
    plan.setSchedule(schedule);
    plan.setFitness((double) calculateProfit(schedule));
    return plan;
  }

  /** Execute Steps 1--4 of the Liu fast insertion algorithm for one task. */
  private static List<ScheduledTask> findLiuStrictInsertion(
    Task task,
    List<ScheduledTask> schedule,
    Satellite satellite
  ) {
    if (task == null || satellite == null) {
      return null;
    }
    List<TimeWindow> windows = new ArrayList<>(
      task.getTimeWindowsForSatellite(satellite.getId())
    );
    windows.removeIf(window -> !hasUsableStrictWindow(window, task.getImagingDuration()));
    windows.sort(Comparator
      .comparingDouble(TimeWindow::getStartTime)
      .thenComparingDouble(TimeWindow::getEndTime));

    for (TimeWindow window : windows) {
      int maximumStartIndex = maximumStrictStartIndex(window, task.getImagingDuration());
      int middleStartIndex = middleStrictStartIndex(
        window,
        task.getImagingDuration(),
        maximumStartIndex
      );
      ScheduledTask middle = createStrictScheduledTask(
        task,
        window,
        middleStartIndex
      );
      if (middle == null) {
        continue;
      }

      // Step 1: use the middle of the VTW and insert directly if both
      // neighboring transitions are feasible.
      List<ScheduledTask> direct = tryDirectLiuInsertion(schedule, middle);
      if (direct != null) {
        return direct;
      }

      // Step 2: move only the inserted observation before modifying any
      // existing observation.  The paper's dichotomy is used to visit the
      // discrete attitude records middle-first.  Direct feasibility is checked
      // against only the two adjacent observations; a complete schedule copy
      // is created only after a candidate has passed that test.
      List<ScheduledTask> movedDirect = findDichotomyDirectLiuInsertion(
        task,
        window,
        maximumStartIndex,
        middleStartIndex,
        schedule
      );
      if (movedDirect != null) {
        return movedDirect;
      }

      // If Step 2 found no usable candidate, Step 3 still tests the original
      // middle placement and can propagate its violation to neighboring OWs.
      List<ScheduledTask> middleTrial = insertLiuCandidate(schedule, middle);
      int middleIndex = indexOfTask(middleTrial, task.getId());
      List<ScheduledTask> propagated = tryLiuStrictPropagation(
        middleTrial,
        middleIndex
      );
      if (propagated != null) {
        return propagated;
      }
    }
    return null;
  }

  private static List<ScheduledTask> findDichotomyDirectLiuInsertion(
    Task task,
    TimeWindow window,
    int maximumStartIndex,
    int middleStartIndex,
    List<ScheduledTask> schedule
  ) {
    for (int startIndex : strictDichotomyOrder(maximumStartIndex, middleStartIndex)) {
      ScheduledTask candidate = createStrictScheduledTask(task, window, startIndex);
      if (candidate != null && isDirectLiuInsertionFeasible(schedule, candidate)) {
        return insertLiuCandidate(schedule, candidate);
      }
    }
    return null;
  }

  private static List<ScheduledTask> tryDirectLiuInsertion(
    List<ScheduledTask> schedule,
    ScheduledTask inserted
  ) {
    return isDirectLiuInsertionFeasible(schedule, inserted)
      ? insertLiuCandidate(schedule, inserted)
      : null;
  }

  private static boolean isDirectLiuInsertionFeasible(
    List<ScheduledTask> schedule,
    ScheduledTask inserted
  ) {
    int insertionIndex = strictInsertionIndex(schedule, inserted.getStartTime());
    return (insertionIndex == 0
      || isStrictTransitionFeasible(schedule.get(insertionIndex - 1), inserted))
      && (insertionIndex >= schedule.size()
        || isStrictTransitionFeasible(inserted, schedule.get(insertionIndex)));
  }

  private static List<ScheduledTask> insertLiuCandidate(
    List<ScheduledTask> schedule,
    ScheduledTask inserted
  ) {
    List<ScheduledTask> trial = copyScheduledTasks(schedule);
    int insertionIndex = strictInsertionIndex(trial, inserted.getStartTime());
    trial.add(insertionIndex, inserted);
    return trial;
  }

  private static int indexOfTask(List<ScheduledTask> schedule, int taskId) {
    for (int index = 0; index < schedule.size(); index++) {
      ScheduledTask scheduledTask = schedule.get(index);
      if (scheduledTask != null && scheduledTask.getTaskId() == taskId) {
        return index;
      }
    }
    return -1;
  }

  private static List<Integer> strictDichotomyOrder(
    int maximumStartIndex,
    int preferredStartIndex
  ) {
    if (maximumStartIndex < 1) {
      return Collections.emptyList();
    }
    LinkedHashSet<Integer> order = new LinkedHashSet<>();
    if (preferredStartIndex >= 1 && preferredStartIndex <= maximumStartIndex) {
      order.add(preferredStartIndex);
    }
    addStrictDichotomy(order, 1, maximumStartIndex);
    return new ArrayList<>(order);
  }

  private static void addStrictDichotomy(Set<Integer> order, int low, int high) {
    if (low > high) {
      return;
    }
    int middle = low + ((high - low) >>> 1);
    order.add(middle);
    addStrictDichotomy(order, low, middle - 1);
    addStrictDichotomy(order, middle + 1, high);
  }

  /** Apply the FTS/BTS feasibility filter and the paper's 3.1--3.4 cases. */
  private static List<ScheduledTask> tryLiuStrictPropagation(
    List<ScheduledTask> trial,
    int insertionIndex
  ) {
    boolean previousOK = insertionIndex == 0
      || isStrictTransitionFeasible(trial.get(insertionIndex - 1), trial.get(insertionIndex));
    boolean nextOK = insertionIndex + 1 >= trial.size()
      || isStrictTransitionFeasible(trial.get(insertionIndex), trial.get(insertionIndex + 1));
    if (previousOK && nextOK) {
      return trial;
    }

    double forwardNeed = strictForwardNeed(trial, insertionIndex);
    double backwardNeed = strictBackwardNeed(trial, insertionIndex);
    double forwardSlack = strictForwardSlack(trial, insertionIndex);
    double backwardSlack = strictBackwardSlack(trial, insertionIndex);

    if (previousOK && !nextOK
      && forwardNeed <= forwardSlack + LIU_INSERTION_EPSILON) {
      List<ScheduledTask> repaired = copyScheduledTasks(trial);
      if (propagateLiuForward(repaired, insertionIndex)
        && isStrictScheduleFeasible(repaired)) {
        return repaired;
      }
    }
    if (!previousOK && nextOK
      && backwardNeed <= backwardSlack + LIU_INSERTION_EPSILON) {
      List<ScheduledTask> repaired = copyScheduledTasks(trial);
      if (propagateLiuBackward(repaired, insertionIndex)
        && isStrictScheduleFeasible(repaired)) {
        return repaired;
      }
    }

    // Step 3.4: both adjacent transitions are violated. Liu repeats Step 3.2
    // followed by Step 3.3, so the propagation order is fixed.
    if (!previousOK && !nextOK
      && forwardNeed <= forwardSlack + LIU_INSERTION_EPSILON
      && backwardNeed <= backwardSlack + LIU_INSERTION_EPSILON) {
      List<ScheduledTask> repaired = copyScheduledTasks(trial);
      if (propagateLiuForward(repaired, insertionIndex)
        && propagateLiuBackward(repaired, insertionIndex)
        && isStrictScheduleFeasible(repaired)) {
        return repaired;
      }
    }
    return null;
  }

  /** Move each violated successor forward, propagating to the end of the segment. */
  private static boolean propagateLiuForward(
    List<ScheduledTask> schedule,
    int insertionIndex
  ) {
    for (int index = insertionIndex + 1; index < schedule.size(); index++) {
      ScheduledTask predecessor = schedule.get(index - 1);
      ScheduledTask current = schedule.get(index);
      if (isStrictTransitionFeasible(predecessor, current)) {
        continue;
      }
      ScheduledTask shifted = findLiuShiftedTask(current, predecessor, true);
      if (shifted == null) {
        return false;
      }
      schedule.set(index, shifted);
    }
    return true;
  }

  /** Move each violated predecessor backward, propagating to the start of the segment. */
  private static boolean propagateLiuBackward(
    List<ScheduledTask> schedule,
    int insertionIndex
  ) {
    for (int index = insertionIndex - 1; index >= 0; index--) {
      ScheduledTask current = schedule.get(index);
      ScheduledTask successor = schedule.get(index + 1);
      if (isStrictTransitionFeasible(current, successor)) {
        continue;
      }
      ScheduledTask shifted = findLiuShiftedTask(current, successor, false);
      if (shifted == null) {
        return false;
      }
      schedule.set(index, shifted);
    }
    return true;
  }

  /**
   * Find the least forward or greatest backward shift by dichotomy. The residual
   * two-second region is scanned as a discrete attitude-record fallback; this keeps
   * the paper's tolerance while remaining correct for non-uniform input records.
   */
  private static ScheduledTask findLiuShiftedTask(
    ScheduledTask task,
    ScheduledTask adjacent,
    boolean forward
  ) {
    TimeWindow window = task == null ? null : task.getTimeWindow();
    if (window == null || !task.isScheduled()) {
      return null;
    }
    int maximumStartIndex = maximumStrictStartIndex(
      window,
      task.getTask().getImagingDuration()
    );
    int currentStartIndex = recordIndex(window, task.getStartRecord());
    if (currentStartIndex < 1 || currentStartIndex > maximumStartIndex) {
      return null;
    }

    int low = forward ? currentStartIndex : 1;
    int high = forward ? maximumStartIndex : currentStartIndex;
    ScheduledTask dichotomyCandidate = dichotomyLiuShift(
      task,
      adjacent,
      forward,
      low,
      high
    );
    if (dichotomyCandidate != null) {
      return dichotomyCandidate;
    }

    // Time-dependent attitude transitions need not be monotone. Complete the
    // search when the binary predicate does not have the assumed shape.
    if (forward) {
      for (int index = low; index <= high; index++) {
        ScheduledTask candidate = createStrictScheduledTask(task.getTask(), window, index);
        if (candidate == null) {
          continue;
        }
        if (isStrictNeighborTransitionFeasible(candidate, adjacent, true)) {
          return candidate;
        }
      }
    } else {
      for (int index = high; index >= low; index--) {
        ScheduledTask candidate = createStrictScheduledTask(task.getTask(), window, index);
        if (candidate == null) {
          continue;
        }
        if (isStrictNeighborTransitionFeasible(candidate, adjacent, false)) {
          return candidate;
        }
      }
    }
    return null;
  }

  private static ScheduledTask dichotomyLiuShift(
    ScheduledTask task,
    ScheduledTask adjacent,
    boolean forward,
    int low,
    int high
  ) {
    TimeWindow window = task.getTimeWindow();
    int bestIndex = -1;
    while (low <= high
      && window.getAttitudeRecordTimeSeconds(high)
        - window.getAttitudeRecordTimeSeconds(low) > LIU_DICHOTOMY_TOLERANCE_SECONDS) {
      int middle = low + ((high - low) >>> 1);
      ScheduledTask candidate = createStrictScheduledTask(
        task.getTask(),
        window,
        middle
      );
      if (candidate != null && isStrictNeighborTransitionFeasible(candidate, adjacent, forward)) {
        bestIndex = middle;
        if (forward) {
          high = middle - 1;
        } else {
          low = middle + 1;
        }
      } else if (forward) {
        low = middle + 1;
      } else {
        high = middle - 1;
      }
    }

    // Search the final tolerance region in the direction that minimizes movement.
    if (forward) {
      for (int index = low; index <= high; index++) {
        ScheduledTask candidate = createStrictScheduledTask(task.getTask(), window, index);
        if (candidate == null) {
          continue;
        }
        if (isStrictNeighborTransitionFeasible(candidate, adjacent, true)) {
          bestIndex = index;
          break;
        }
      }
    } else {
      for (int index = high; index >= low; index--) {
        ScheduledTask candidate = createStrictScheduledTask(task.getTask(), window, index);
        if (candidate == null) {
          continue;
        }
        if (isStrictNeighborTransitionFeasible(candidate, adjacent, false)) {
          bestIndex = index;
          break;
        }
      }
    }
    return bestIndex < 0
      ? null
      : createStrictScheduledTask(task.getTask(), window, bestIndex);
  }

  private static boolean isStrictNeighborTransitionFeasible(
    ScheduledTask candidate,
    ScheduledTask adjacent,
    boolean forward
  ) {
    if (candidate == null) {
      return false;
    }
    if (adjacent == null) {
      return true;
    }
    return forward
      ? isStrictTransitionFeasible(adjacent, candidate)
      : isStrictTransitionFeasible(candidate, adjacent);
  }

  /** Required additional interval for the inserted task's successor transition. */
  private static double strictForwardNeed(
    List<ScheduledTask> schedule,
    int insertionIndex
  ) {
    if (insertionIndex + 1 >= schedule.size()) {
      return 0.0;
    }
    ScheduledTask current = schedule.get(insertionIndex);
    ScheduledTask successor = schedule.get(insertionIndex + 1);
    return strictTransitionDeficit(current, successor);
  }

  /** Required additional interval for the inserted task's predecessor transition. */
  private static double strictBackwardNeed(
    List<ScheduledTask> schedule,
    int insertionIndex
  ) {
    if (insertionIndex == 0) {
      return 0.0;
    }
    return strictTransitionDeficit(
      schedule.get(insertionIndex - 1),
      schedule.get(insertionIndex)
    );
  }

  /** Accumulated FTS over all successors in the current no-orbit segment. */
  private static double strictForwardSlack(
    List<ScheduledTask> schedule,
    int insertionIndex
  ) {
    double slack = 0.0;
    for (int index = insertionIndex + 1; index < schedule.size(); index++) {
      ScheduledTask successor = index + 1 < schedule.size()
        ? schedule.get(index + 1)
        : null;
      slack += strictForwardTimeSlack(schedule.get(index), successor);
    }
    return slack;
  }

  /** Accumulated BTS over all predecessors in the current no-orbit segment. */
  private static double strictBackwardSlack(
    List<ScheduledTask> schedule,
    int insertionIndex
  ) {
    double slack = 0.0;
    for (int index = insertionIndex - 1; index >= 0; index--) {
      ScheduledTask predecessor = index > 0 ? schedule.get(index - 1) : null;
      slack += strictBackwardTimeSlack(schedule.get(index), predecessor);
    }
    return slack;
  }

  private static double strictForwardTimeSlack(
    ScheduledTask task,
    ScheduledTask successor
  ) {
    if (task == null || !task.isScheduled() || task.getTimeWindow() == null) {
      return 0.0;
    }
    TimeWindow window = task.getTimeWindow();
    int duration = task.getTask().getImagingDuration();
    int currentIndex = recordIndex(window, task.getStartRecord());
    int maximumStartIndex = maximumStrictStartIndex(window, duration);
    if (currentIndex < 1 || currentIndex > maximumStartIndex) {
      return 0.0;
    }

    double latestStart = task.getStartTime();
    for (int index = currentIndex; index <= maximumStartIndex; index++) {
      ScheduledTask shifted = createStrictScheduledTask(task.getTask(), window, index);
      if (shifted == null) {
        continue;
      }
      if (successor == null || isStrictTransitionFeasible(shifted, successor)) {
        latestStart = Math.max(latestStart, shifted.getStartTime());
      }
    }
    return Math.max(0.0, latestStart - task.getStartTime());
  }

  private static double strictBackwardTimeSlack(
    ScheduledTask task,
    ScheduledTask predecessor
  ) {
    if (task == null || !task.isScheduled() || task.getTimeWindow() == null) {
      return 0.0;
    }
    TimeWindow window = task.getTimeWindow();
    int duration = task.getTask().getImagingDuration();
    int currentIndex = recordIndex(window, task.getStartRecord());
    int maximumStartIndex = maximumStrictStartIndex(window, duration);
    if (currentIndex < 1 || currentIndex > maximumStartIndex) {
      return 0.0;
    }

    double earliestStart = task.getStartTime();
    for (int index = 1; index <= currentIndex; index++) {
      ScheduledTask shifted = createStrictScheduledTask(task.getTask(), window, index);
      if (shifted == null) {
        continue;
      }
      if (predecessor == null || isStrictTransitionFeasible(predecessor, shifted)) {
        earliestStart = Math.min(earliestStart, shifted.getStartTime());
      }
    }
    return Math.max(0.0, task.getStartTime() - earliestStart);
  }

  private static double strictTransitionDeficit(
    ScheduledTask previous,
    ScheduledTask next
  ) {
    if (previous == null || next == null
      || previous.getEndRecord() == null || next.getStartRecord() == null) {
      return 0.0;
    }
    double interval = next.getStartTime() - previous.getEndTime();
    double required = TimeUtils.calculateTransformationTime(
      previous.getEndRecord(),
      next.getStartRecord()
    );
    return Math.max(0.0, required - interval);
  }

  private static boolean isStrictLocalFeasible(
    List<ScheduledTask> schedule,
    int insertionIndex
  ) {
    return (insertionIndex == 0
      || isStrictTransitionFeasible(schedule.get(insertionIndex - 1), schedule.get(insertionIndex)))
      && (insertionIndex + 1 >= schedule.size()
        || isStrictTransitionFeasible(schedule.get(insertionIndex), schedule.get(insertionIndex + 1)));
  }

  private static boolean isStrictScheduleFeasible(List<ScheduledTask> schedule) {
    for (int index = 1; index < schedule.size(); index++) {
      if (!isStrictTransitionFeasible(schedule.get(index - 1), schedule.get(index))) {
        return false;
      }
    }
    return true;
  }

  private static boolean isStrictTransitionFeasible(
    ScheduledTask previous,
    ScheduledTask next
  ) {
    if (previous == null || next == null) {
      return true;
    }
    if (!previous.isScheduled() || !next.isScheduled()) {
      return false;
    }
    return TimeUtils.checkTransformationTime(
      previous.getEndRecord(),
      next.getStartRecord()
    );
  }

  private static List<ScheduledTask> copyScheduledTasks(List<ScheduledTask> schedule) {
    List<ScheduledTask> copy = new ArrayList<>(schedule.size());
    for (ScheduledTask scheduledTask : schedule) {
      if (scheduledTask == null) {
        copy.add(null);
        continue;
      }
      ScheduledTask copiedTask = new ScheduledTask(scheduledTask.getTask());
      if (scheduledTask.isScheduled()) {
        copiedTask.setSchedule(
          scheduledTask.getTimeWindow(),
          scheduledTask.getStartRecord(),
          scheduledTask.getEndRecord(),
          scheduledTask.getSatelliteId()
        );
      } else {
        copiedTask.setSatelliteId(scheduledTask.getSatelliteId());
      }
      copy.add(copiedTask);
    }
    return copy;
  }

  private static ScheduledTask createStrictScheduledTask(
    Task task,
    TimeWindow window,
    int startIndex
  ) {
    int duration = task.getImagingDuration();
    if (!isStrictRecordPairWithinWindow(window, startIndex, duration)) {
      return null;
    }
    AttitudeRecord startRecord = window.getAttitudeRecord(startIndex);
    AttitudeRecord endRecord = window.getAttitudeRecord(startIndex + duration);
    ScheduledTask scheduledTask = new ScheduledTask(task);
    scheduledTask.setSchedule(window, startRecord, endRecord);
    return scheduledTask;
  }

  private static int maximumStrictStartIndex(TimeWindow window, int duration) {
    int maximum = window.getAttitudeRecordCount() - 2 - duration;
    for (int index = maximum; index >= 1; index--) {
      if (isStrictRecordPairWithinWindow(window, index, duration)) {
        return index;
      }
    }
    return 0;
  }

  private static boolean isStrictRecordPairWithinWindow(
    TimeWindow window,
    int startIndex,
    int duration
  ) {
    if (window == null || duration < 0 || startIndex < 1
      || startIndex + duration >= window.getAttitudeRecordCount()) {
      return false;
    }
    AttitudeRecord startRecord = window.getAttitudeRecord(startIndex);
    AttitudeRecord endRecord = window.getAttitudeRecord(startIndex + duration);
    return window.canAccommodate(startRecord.getTotalSeconds(), duration)
      && endRecord.getTotalSeconds() <= window.getEndTime() + LIU_INSERTION_EPSILON;
  }

  private static boolean hasUsableStrictWindow(TimeWindow window, int duration) {
    if (window == null || !window.canAccommodateTask(duration)) {
      return false;
    }
    int maximumStartIndex = maximumStrictStartIndex(window, duration);
    return maximumStartIndex >= 1;
  }

  private static int middleStrictStartIndex(
    TimeWindow window,
    int duration,
    int maximumStartIndex
  ) {
    if (maximumStartIndex < 1) {
      return -1;
    }
    double target = window.getStartTime()
      + (window.getEndTime() - window.getStartTime() - duration) / 2.0;
    int bestIndex = 1;
    double bestDistance = Double.POSITIVE_INFINITY;
    for (int index = 1; index <= maximumStartIndex; index++) {
      double distance = Math.abs(window.getAttitudeRecordTimeSeconds(index) - target);
      if (distance < bestDistance) {
        bestIndex = index;
        bestDistance = distance;
      }
    }
    return bestIndex;
  }

  private static int strictInsertionIndex(List<ScheduledTask> schedule, double startTime) {
    int low = 0;
    int high = schedule.size();
    while (low < high) {
      int middle = low + ((high - low) >>> 1);
      if (schedule.get(middle).getStartTime() <= startTime) {
        low = middle + 1;
      } else {
        high = middle;
      }
    }
    return low;
  }

  private static int recordIndex(TimeWindow window, AttitudeRecord record) {
    if (record == null) {
      return -1;
    }
    List<AttitudeRecord> records = window.getAttitudeRecords();
    for (int index = 0; index < records.size(); index++) {
      if (records.get(index) == record) {
        return index;
      }
    }
    double target = record.getTotalSeconds();
    int nearest = -1;
    double bestDistance = Double.POSITIVE_INFINITY;
    for (int index = 0; index < records.size(); index++) {
      double distance = Math.abs(records.get(index).getTotalSeconds() - target);
      if (distance < bestDistance) {
        nearest = index;
        bestDistance = distance;
      }
    }
    return nearest;
  }

  /**
   * Limited candidate insertion with anchor positions. In replacement move scenarios,
   * anchorPositions are the original positions of removed tasks in baseSequence;
   * insertion densely samples near these positions to improve hit rate.
   */
  public static SchedulingPlan buildPlanByLimitedInsertion(
    List<Task> baseSequence,
    List<Task> addedTasks,
    Satellite satellite,
    InsertionMode mode,
    int maxCandidatePositions,
    boolean enableWindowPreferenceForGreedy,
    List<Integer> anchorPositions
  ) {
    return buildPlanByInsertion(
      baseSequence,
      addedTasks,
      satellite,
      mode,
      maxCandidatePositions,
      enableWindowPreferenceForGreedy,
      anchorPositions,
      true
    );
  }

  private static SchedulingPlan buildPlanByInsertion(
    List<Task> baseSequence,
    List<Task> addedTasks,
    Satellite satellite,
    InsertionMode mode,
    int maxCandidatePositions,
    boolean enableWindowPreferenceForGreedy,
    List<Integer> anchorPositions,
    boolean retainUnscheduledAddedTasks
  ) {
    if (baseSequence == null || addedTasks == null || satellite == null) {
      throw new IllegalArgumentException("Base sequence, added tasks, and satellite cannot be null");
    }
    int candidateLimit = Math.max(1, maxCandidatePositions);
    Set<Integer> windowPreferenceTaskIds = enableWindowPreferenceForGreedy
      ? collectTaskIds(addedTasks)
      : null;
    List<Task> workingSequence = deduplicateTaskSequence(baseSequence);
    for (Task task : addedTasks) {
      if (task == null || containsTaskId(workingSequence, task.getId())) {
        continue;
      }
      Set<Integer> taskWindowPref =
        windowPreferenceTaskIds != null && windowPreferenceTaskIds.contains(task.getId())
          ? Collections.singleton(task.getId())
          : null;
      List<Integer> candidatePositions = buildCandidateInsertPositions(
          workingSequence.size(), candidateLimit, anchorPositions);
      SchedulingPlan bestPlan = selectBestLimitedInsertionPlan(
          workingSequence, task, satellite, mode, taskWindowPref, candidatePositions);
      if (bestPlan == null
        || (!retainUnscheduledAddedTasks
          && !containsScheduledTask(bestPlan.getSchedule(), task.getId()))) {
        if (retainUnscheduledAddedTasks) {
          workingSequence.add(task);
        }
      } else {
        workingSequence = new ArrayList<>(bestPlan.getTaskSequence());
      }
    }
    SchedulingPlan finalPlan = new SchedulingPlan(workingSequence);
    evaluatePlanWithSelectiveWindowPreference(
      finalPlan,
      satellite,
      mode,
      windowPreferenceTaskIds
    );
    return finalPlan;
  }

  private static Set<Integer> collectTaskIds(List<Task> tasks) {
    Set<Integer> ids = new HashSet<>();
    for (Task task : tasks) {
      if (task != null) {
        ids.add(task.getId());
      }
    }
    return ids.isEmpty() ? null : ids;
  }

  private static List<Task> deduplicateTaskSequence(List<Task> tasks) {
    List<Task> result = new ArrayList<>();
    TaskIdMarker seenTaskIds = new TaskIdMarker();
    for (Task task : tasks) {
      if (task == null || !seenTaskIds.add(task.getId())) {
        continue;
      }
      result.add(task);
    }
    return result;
  }

  private static boolean containsTaskId(List<Task> tasks, int taskId) {
    for (Task task : tasks) {
      if (task != null && task.getId() == taskId) {
        return true;
      }
    }
    return false;
  }

  private static boolean containsScheduledTask(List<ScheduledTask> schedule, int taskId) {
    for (ScheduledTask scheduledTask : schedule) {
      if (scheduledTask != null
        && scheduledTask.isScheduled()
        && scheduledTask.getTaskId() == taskId) {
        return true;
      }
    }
    return false;
  }

  private static SchedulingPlan selectBestLimitedInsertionPlan(
      List<Task> workingSequence,
      Task task,
      Satellite satellite,
      InsertionMode mode,
      Set<Integer> taskWindowPref,
      List<Integer> candidatePositions
  ) {
    SchedulingPlan bestPlan = null;
    double bestFitness = Double.NEGATIVE_INFINITY;
    boolean useSnapshot = satellite.getScheduleBuilder() == null && taskWindowPref == null && candidatePositions.size() > 1
        && !workingSequence.isEmpty();
    ScheduleSnapshotResult snapshotResult = null;
    if (useSnapshot) {
      Set<Integer> snapshotPositions = new TreeSet<>();
      for (int pos : candidatePositions) {
        if (pos > 0) {
          snapshotPositions.add(pos - 1);
        }
      }
      if (!snapshotPositions.isEmpty()) {
        snapshotResult = generateScheduleWithSnapshots(workingSequence, satellite, mode, snapshotPositions);
      }
    }
    for (int insertIndex : candidatePositions) {
      List<Task> trialSequence = new ArrayList<>(workingSequence);
      trialSequence.add(insertIndex, task);
      List<ScheduledTask> schedule;
      if (snapshotResult != null && insertIndex > 0
          && snapshotResult.scheduleSnapshots.containsKey(insertIndex - 1)) {
        schedule = generateScheduleFromSnapshot(
            trialSequence, satellite, mode,
            snapshotResult.scheduleSnapshots.get(insertIndex - 1),
            snapshotResult.scheduledIdSnapshots.get(insertIndex - 1),
            insertIndex);
      } else {
        SchedulingPlan trialPlan = new SchedulingPlan(trialSequence);
        schedule = generateSchedule(trialPlan, satellite, mode,
            taskWindowPref != null && !taskWindowPref.isEmpty(), taskWindowPref);
      }
      double fitness = (double) calculateProfit(schedule)
          + calculateNormalizedSlackBonus(schedule);
      if (fitness > bestFitness) {
        bestFitness = fitness;
        bestPlan = new SchedulingPlan(trialSequence);
        bestPlan.setSchedule(schedule);
        bestPlan.setFitness(fitness);
      }
    }
    return bestPlan;
  }

  private static List<Integer> buildCandidateInsertPositions(int sequenceSize, int maxCandidatePositions) {
    return buildCandidateInsertPositions(sequenceSize, maxCandidatePositions, null);
  }

  /**
   * Build candidate insertion position list. The baseline keeps boundary,
   * midpoint and uniform positions; replacement moves additionally sample near
   * removal anchors.
   */
  private static final int ANCHOR_NEIGHBORHOOD = 2;

  private static List<Integer> buildCandidateInsertPositions(
      int sequenceSize, int maxCandidatePositions, List<Integer> anchorPositions) {
    if (sequenceSize < Integer.MAX_VALUE
      && maxCandidatePositions >= sequenceSize + 1) {
      List<Integer> allPositions = new ArrayList<>(sequenceSize + 1);
      for (int position = 0; position <= sequenceSize; position++) {
        allPositions.add(position);
      }
      return allPositions;
    }
    List<Integer> candidatePositions = new ArrayList<>();
    addCandidatePosition(candidatePositions, sequenceSize, 0);
    if (sequenceSize > 0) {
      addCandidatePosition(candidatePositions, sequenceSize, sequenceSize / 2);
      addCandidatePosition(candidatePositions, sequenceSize, sequenceSize);
    }
    if (maxCandidatePositions > candidatePositions.size()) {
      double step = (double) Math.max(1, sequenceSize) / Math.max(1, maxCandidatePositions - 1);
      for (int i = 1; i < maxCandidatePositions - 1; i++) {
        addCandidatePosition(candidatePositions, sequenceSize, (int) Math.round(i * step));
      }
    }
    // Anchor neighborhood dense sampling
    if (anchorPositions != null) {
      for (int anchor : anchorPositions) {
        for (int offset = -ANCHOR_NEIGHBORHOOD; offset <= ANCHOR_NEIGHBORHOOD; offset++) {
          addCandidatePosition(candidatePositions, sequenceSize, anchor + offset);
        }
      }
    }
    Collections.sort(candidatePositions);
    return candidatePositions;
  }

  private static void addCandidatePosition(List<Integer> candidatePositions, int sequenceSize, int position) {
    int bounded = Math.max(0, Math.min(sequenceSize, position));
    for (int existing : candidatePositions) {
      if (existing == bounded) {
        return;
      }
    }
    candidatePositions.add(bounded);
  }

  public static List<Double> evaluatePop(List<double[]> population, Satellite satellite) {
    return evaluatePop(population, satellite, false);
  }

  public static List<Double> evaluatePop(List<double[]> population, Satellite satellite, boolean useOptimal) {
    return evaluatePop(population, satellite, InsertionMode.fromUseOptimal(useOptimal));
  }

  public static void evaluatePopInto(List<double[]> population, Satellite satellite, double[] out) {
    evaluatePopInto(population, satellite, InsertionMode.fromUseOptimal(false), out);
  }

  public static void evaluatePopInto(
    List<double[]> population,
    Satellite satellite,
    InsertionMode mode,
    double[] out
  ) {
    final List<Task> tasksSnapshot = List.copyOf(satellite.getAssignedTasks());
    evaluatePopInto(population, satellite, tasksSnapshot, mode, out);
  }

  public static List<Double> evaluatePop(List<double[]> population, Satellite satellite, InsertionMode mode) {
    final List<Task> tasksSnapshot = List.copyOf(satellite.getAssignedTasks());
    return evaluatePop(population, satellite, tasksSnapshot, mode);
  }

  /**
   * Evaluate a population using a caller-provided task reference order.
   *
   * <p>{@code referenceTasks} is borrowed, not copied. The caller owns the
   * snapshot lifecycle and must not mutate the list or its task ordering while
   * evaluation is running, including the parallel branch.
   */
  public static List<Double> evaluatePop(
    List<double[]> population,
    Satellite satellite,
    List<Task> referenceTasks,
    InsertionMode mode
  ) {
    if (population == null || population.isEmpty()) {
      return Collections.emptyList();
    }
    if (satellite == null) {
      throw new IllegalArgumentException("Satellite cannot be null");
    }
    if (referenceTasks == null) {
      throw new IllegalArgumentException("Reference task list cannot be null");
    }

    final List<Task> tasksSnapshot = referenceTasks;

    int size = population.size();
    double[] raw = new double[size];
    evaluatePopInto(population, satellite, tasksSnapshot, mode, raw);
    List<Double> results = new ArrayList<>(size);
    for (double v : raw) results.add(v);
    return results;
  }

  public static void evaluatePopInto(
    List<double[]> population,
    Satellite satellite,
    List<Task> referenceTasks,
    InsertionMode mode,
    double[] out
  ) {
    if (population == null || population.isEmpty()) {
      return;
    }
    if (satellite == null) {
      throw new IllegalArgumentException("Satellite cannot be null");
    }
    if (referenceTasks == null) {
      throw new IllegalArgumentException("Reference task list cannot be null");
    }
    if (out == null || out.length < population.size()) {
      throw new IllegalArgumentException("Output buffer must have length >= population size");
    }

    final List<Task> tasksSnapshot = referenceTasks;
    int size = population.size();

    int dimension = population.get(0).length;
    if (!ParallelPolicy.allowVectorParallelism(size, dimension, PARALLEL_EVAL_THRESHOLD)) {
      DecodeWorkspace workspace = new DecodeWorkspace();
      for (int i = 0; i < size; i++) {
        out[i] = evaluateVector(population.get(i), tasksSnapshot, satellite, mode, workspace);
      }
      return;
    }

    final InsertionMode finalMode = mode;
    ThreadLocal<DecodeWorkspace> workspaces = ThreadLocal.withInitial(DecodeWorkspace::new);
    IntStream.range(0, size)
    .parallel()
    .forEach(i -> {
        out[i] = evaluateVector(population.get(i), tasksSnapshot, satellite, finalMode, workspaces.get());
      });
  }

  public static void applyEliteEvaluation(List<double[]> population, List<Double> popFit,
    Satellite satellite, double eliteRatio) {
    if (population == null || population.isEmpty() || popFit == null) {
      return;
    }
    if (satellite == null) {
      throw new IllegalArgumentException("Satellite cannot be null");
    }
    if (eliteRatio <= 0 || eliteRatio > 1.0) {
      throw new IllegalArgumentException("Elite ratio must be in (0, 1]: " + eliteRatio);
    }

    int popSize = population.size();
    int eliteCount = Math.max(1, (int) Math.ceil(popSize * eliteRatio));

    // Create index-fitness pairs for sorting
    List<Integer> indices = new ArrayList<>(popSize);
    for (int i = 0; i < popSize; i++) {
      indices.add(i);
    }

    // Sort indices by fitness (descending order - higher fitness is better)
    indices.sort((i1, i2) -> Double.compare(popFit.get(i2), popFit.get(i1)));

    // Get elite indices (top eliteCount individuals)
    List<Integer> eliteIndices = indices.subList(0, eliteCount);

    final List<Task> tasksSnapshot = Collections.unmodifiableList(
      new ArrayList<>(satellite.getAssignedTasks())
    );

    // Debug counters
    int improvedCount = 0;
    double totalImprovement = 0.0;
    double maxImprovement = 0.0;
    double totalRelativeImprovement = 0.0;

    // Re-evaluate elite individuals with optimal insertion
    for (int idx : eliteIndices) {
      double[] chromosome = population.get(idx);
      SchedulingPlan plan = decode(chromosome, tasksSnapshot);
      double greedyFitness = popFit.get(idx);

      // Use optimal insertion (insertMode = true)
      double optimalFitness = evaluatePlan(plan, satellite, InsertionMode.fromUseOptimal(true));

      // Update fitness if optimal insertion is better
      if (optimalFitness > greedyFitness) {
        double improvement = optimalFitness - greedyFitness;
        double relativeImprovement = (improvement / greedyFitness) * 100.0;
        popFit.set(idx, optimalFitness);
        improvedCount++;
        totalImprovement += improvement;
        totalRelativeImprovement += relativeImprovement;
        maxImprovement = Math.max(maxImprovement, improvement);
      }
    }

    // Debug output (only if improvements found)
    if (improvedCount > 0) {
      double avgRelative = totalRelativeImprovement / improvedCount;
      System.out.printf("[Elite Eval] %d/%d improved, avg=%.2f (%.4f%%), max=%.2f%n",
        improvedCount, eliteCount, totalImprovement / improvedCount, avgRelative, maxImprovement);
    }
  }

  public static int calculateProfit(List<ScheduledTask> schedule) {
    if (schedule == null || schedule.isEmpty()) {
      return 0;
    }

    int profit = 0;
    for (int i = 0; i < schedule.size(); i++) {
      profit += schedule.get(i).getPriority();
    }
    return profit;
  }

  /** Continuous signal weight: small enough not to alter integer profit ranking */
  private static final double SLACK_BONUS_EPSILON = 0.001;

  /**
   * Calculate normalized transition time slack bonus.
   * For adjacent scheduled task pairs, accumulates (timeInterval - transformTime) / timeInterval,
   * then divides by the number of pairs to normalize to [0,1), multiplied by epsilon.
   * Used to prefer plans that leave more transition slack for subsequent tasks when profit is equal.
   */
  private static double calculateNormalizedSlackBonus(List<ScheduledTask> schedule) {
    if (schedule == null || schedule.size() < 2) {
      return 0.0;
    }
    double slackSum = 0.0;
    int pairs = 0;
    for (int i = 0; i < schedule.size() - 1; i++) {
      AttitudeRecord prevEnd = schedule.get(i).getEndRecord();
      AttitudeRecord nextStart = schedule.get(i + 1).getStartRecord();
      if (prevEnd == null || nextStart == null) {
        continue;
      }
      double timeInterval = TimeUtils.calculateTimeInterval(prevEnd, nextStart);
      if (timeInterval <= 0.0) {
        continue;
      }
      double transformTime = TimeUtils.calculateTransformationTime(prevEnd, nextStart);
      slackSum += (timeInterval - transformTime) / timeInterval;
      pairs++;
    }
    if (pairs == 0) {
      return 0.0;
    }
    return SLACK_BONUS_EPSILON * (slackSum / pairs);
  }

  /**
   * Try to insert a task into an existing schedule using optimal insertion.
   * Finds the best (minimum transition time) feasible insertion point.
   *
   * @param task The task to insert
   * @param satelliteId The satellite ID
   * @param currentSchedule The existing schedule (will be modified in-place if successful)
   * @return true if the task was successfully inserted
   */
}
