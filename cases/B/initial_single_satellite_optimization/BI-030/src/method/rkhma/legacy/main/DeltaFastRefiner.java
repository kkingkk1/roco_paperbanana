package method.rkhma.legacy.main;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;

import method.rkhma.legacy.common.TuningConfig;
import method.rkhma.legacy.common.TuningDefaults;
import method.rkhma.legacy.model.Satellite;
import method.rkhma.legacy.model.ScheduledTask;
import method.rkhma.legacy.model.SchedulingPlan;
import method.rkhma.legacy.model.Task;
import method.rkhma.legacy.operator.insertion.InsertionMode;
import method.rkhma.legacy.utils.PlanUtils;

final class DeltaFastRefiner {
  private TaskAllocationCoordinator.LocalRefinement localRefinement;

  void setLocalRefinement(TaskAllocationCoordinator.LocalRefinement refinement) {
    localRefinement = refinement;
  }
  interface Context {
    double computeFastRefineRepairScore(Task task, int satelliteId);
    void materializePlanSchedule(SchedulingPlan plan, Satellite satellite, InsertionMode mode);
  }

  private static final String DELTA_FAST_REFINE_ENABLED_PROPERTY = "taskcoord.deltaFastRefine.enabled";
  private static final int DELTA_FAST_REFINE_MAX_CANDIDATE_POSITIONS = TuningConfig.getInt("coord.deltaFastRefineMaxCandidatePositions", TuningDefaults.COORD_DELTA_FAST_REFINE_MAX_CANDIDATE_POSITIONS);
  private static final boolean DELTA_FAST_REFINE_WINDOW_PREFERENCE = TuningConfig.getBoolean("coord.deltaFastRefineWindowPreference", TuningDefaults.COORD_DELTA_FAST_REFINE_WINDOW_PREFERENCE);
  private static final boolean FAST_REFINE_ADJACENT_SWAP_ENABLED = TuningConfig.getBoolean("coord.fastRefineAdjacentSwapEnabled", TuningDefaults.COORD_FAST_REFINE_ADJACENT_SWAP_ENABLED);
  private static final int FAST_REFINE_SWAP_RADIUS = TuningConfig.getInt("coord.fastRefineSwapRadius", TuningDefaults.COORD_FAST_REFINE_SWAP_RADIUS);
  private static final double FAST_REFINE_CONFLICT_WEIGHT = TuningConfig.getDouble("coord.fastRefineConflictWeight", TuningDefaults.COORD_FAST_REFINE_CONFLICT_WEIGHT);
  private static final AdjacentSwapStats ADJACENT_SWAP_STATS = new AdjacentSwapStats();

  private final Context context;
  private final PlanUtils.TaskIdMarker previousTaskIds = new PlanUtils.TaskIdMarker();
  private final PlanUtils.TaskIdMarker currentTaskIds = new PlanUtils.TaskIdMarker();
  private final PlanUtils.SnapshotReplayScratch snapshotReplayScratch =
    new PlanUtils.SnapshotReplayScratch();

  DeltaFastRefiner(Context context) {
    this.context = context;
  }

  static void resetAdjacentSwapStats() {
    ADJACENT_SWAP_STATS.reset();
  }

  static AdjacentSwapStats.Snapshot adjacentSwapStatsSnapshot() {
    return ADJACENT_SWAP_STATS.snapshot();
  }

  SchedulingPlan refine(
    Satellite satellite,
    List<Task> previousOrderedTasks,
    List<Task> orderedTasks,
    Map<Integer, Integer> assignmentSnapshot,
    InsertionMode mode,
    Map<Integer, SatelliteState> stateStore,
    int currentIteration,
    SchedulingPlan currentPlan
  ) {
    if (localRefinement == null) {
      return refineOriginal(satellite, previousOrderedTasks, orderedTasks,
        assignmentSnapshot, mode, stateStore);
    }
    var view = new TaskAllocationCoordinator.RefinementContext(satellite, List.copyOf(previousOrderedTasks),
      List.copyOf(orderedTasks), currentPlan == null ? null : currentPlan.copy(),
      mode, currentIteration);
    return localRefinement.refine(view, () -> refineOriginal(satellite, previousOrderedTasks, orderedTasks,
      assignmentSnapshot, mode, stateStore));
  }

  private SchedulingPlan refineOriginal(
    Satellite satellite,
    List<Task> previousOrderedTasks,
    List<Task> orderedTasks,
    Map<Integer, Integer> assignmentSnapshot,
    InsertionMode mode,
    Map<Integer, SatelliteState> stateStore
  ) {
    SatelliteState state = stateStore.get(satellite.getId());
    if (state == null || state.bestPlan == null) {
      return null;
    }
    TaskDelta cumulativeTaskDelta = collectTaskDelta(state.orderedTasksSnapshot, orderedTasks);
    TaskDelta roundTaskDelta = collectTaskDelta(previousOrderedTasks, orderedTasks);
    if (!isFastRefineEligible(roundTaskDelta)) {
      return null;
    }
    List<Task> baseSequence = buildFastRefineBaseSequence(state, cumulativeTaskDelta);
    if (baseSequence.isEmpty() && orderedTasks.isEmpty()) {
      SchedulingPlan emptyPlan = new SchedulingPlan(new ArrayList<>());
      emptyPlan.setFitness(0.0);
      return emptyPlan;
    }
    List<Integer> anchorPositions = computeRemovalAnchorPositions(state, roundTaskDelta, baseSequence);
    List<Task> addedTasks = new ArrayList<>(roundTaskDelta.addedTasks);
    int satId = satellite.getId();
    Map<Integer, Double> cachedSortScores = new HashMap<>(addedTasks.size() * 2);
    for (Task task : addedTasks) {
      double score = context.computeFastRefineRepairScore(task, satId)
        - FAST_REFINE_CONFLICT_WEIGHT * TaskAllocationScoring.computeConflictOverlapScore(task, baseSequence, satId);
      cachedSortScores.put(task.getId(), score);
    }
    addedTasks.sort((left, right) -> {
      int scoreCompare = Double.compare(cachedSortScores.get(right.getId()), cachedSortScores.get(left.getId()));
      if (scoreCompare != 0) {
        return scoreCompare;
      }
      return Integer.compare(left.getId(), right.getId());
    });
    int maxCandidatePositions = DELTA_FAST_REFINE_MAX_CANDIDATE_POSITIONS;
    SchedulingPlan baselinePlan;
    if (anchorPositions != null) {
      baselinePlan = PlanUtils.buildPlanByLimitedInsertion(
        baseSequence,
        addedTasks,
        satellite,
        mode,
        maxCandidatePositions,
        DELTA_FAST_REFINE_WINDOW_PREFERENCE,
        anchorPositions
      );
    } else {
      baselinePlan = PlanUtils.buildPlanByLimitedInsertion(
        baseSequence,
        addedTasks,
        satellite,
        mode,
        maxCandidatePositions,
        DELTA_FAST_REFINE_WINDOW_PREFERENCE
      );
    }
    baselinePlan.setTaskSequence(filterSequenceByAssignment(baselinePlan.getTaskSequence(), satellite.getId(), assignmentSnapshot));
    return improveWithAdjacentSwaps(baselinePlan, addedTasks, satellite, mode);
  }

  private SchedulingPlan improveWithAdjacentSwaps(
    SchedulingPlan baselinePlan,
    List<Task> addedTasks,
    Satellite satellite,
    InsertionMode mode
  ) {
    long startNanos = System.nanoTime();
    if (baselinePlan.getTaskSequence().size() < 2 || addedTasks.isEmpty()) {
      context.materializePlanSchedule(baselinePlan, satellite, mode);
      ADJACENT_SWAP_STATS.recordCall(0, 0, 0.0, System.nanoTime() - startNanos);
      return baselinePlan;
    }
    List<Task> seq = new ArrayList<>(baselinePlan.getTaskSequence());
    int n = seq.size();
    PlanUtils.TaskIdMarker addedTaskIds = new PlanUtils.TaskIdMarker();
    for (Task task : addedTasks) {
      addedTaskIds.add(task.getId());
    }
    int earliestInsertPos = n;
    for (int i = 0; i < n; i++) {
      if (addedTaskIds.contains(seq.get(i).getId())) {
        earliestInsertPos = Math.min(earliestInsertPos, i);
      }
    }
    int swapStart = Math.max(0, earliestInsertPos - FAST_REFINE_SWAP_RADIUS);
    int swapEnd = n - 1;
    int swapPositionCount = Math.max(0, swapEnd - swapStart);
    if (!FAST_REFINE_ADJACENT_SWAP_ENABLED || swapPositionCount == 0) {
      context.materializePlanSchedule(baselinePlan, satellite, mode);
      ADJACENT_SWAP_STATS.recordCall(swapPositionCount, 0, 0.0, System.nanoTime() - startNanos);
      return baselinePlan;
    }
    // 统一C接管解码时，每个交换候选都必须经过C及运行时试评。
    if (satellite.getScheduleBuilder() != null) {
      SchedulingPlan best = new SchedulingPlan(seq);
      context.materializePlanSchedule(best, satellite, mode);
      double fitness = best.getFitness();
      int accepted = 0;
      double gain = 0.0;
      for (int pos = swapStart; pos < swapEnd; pos++) {
        Collections.swap(seq, pos, pos + 1);
        SchedulingPlan candidate = new SchedulingPlan(seq);
        context.materializePlanSchedule(candidate, satellite, mode);
        if (candidate.getFitness() > fitness) {
          gain += candidate.getFitness() - fitness;
          fitness = candidate.getFitness();
          best = candidate;
          accepted++;
        } else {
          Collections.swap(seq, pos, pos + 1);
        }
      }
      ADJACENT_SWAP_STATS.recordCall(swapPositionCount, accepted, gain, System.nanoTime() - startNanos);
      return best;
    }
    int snapshotStart = Math.max(0, swapStart - 1);
    // Include the post-pair state for strict GREEDY prefix equivalence checks.
    int snapshotEnd = swapEnd;
    PlanUtils.ScheduleSnapshotResult snapshotResult =
      PlanUtils.generateScheduleWithSnapshotRange(seq, satellite, mode, snapshotStart, snapshotEnd);
    SchedulingPlan currentBest = new SchedulingPlan(seq);
    currentBest.setSchedule(snapshotResult.finalSchedule);
    currentBest.setFitness((double) PlanUtils.calculateProfit(snapshotResult.finalSchedule));
    double currentFitness = currentBest.getFitness();
    int acceptedSwaps = 0;
    double totalGain = 0.0;
    List<ScheduledTask> emptyPrefixSchedule = new ArrayList<>();
    for (int pos = swapStart; pos < swapEnd; pos++) {
      Collections.swap(seq, pos, pos + 1);
      int startIdx = pos;
      List<ScheduledTask> prefixSchedule;
      int[] prefixIds;
      List<ScheduledTask> savedPrefix = pos > 0
        ? snapshotResult.scheduleSnapshots.get(pos - 1)
        : null;
      if (savedPrefix != null) {
        prefixSchedule = savedPrefix;
        prefixIds = snapshotResult.scheduledIdSnapshots.get(pos - 1);
      } else {
        prefixSchedule = emptyPrefixSchedule;
        prefixIds = null;
        startIdx = 0;
      }
      boolean accepted = false;
      try {
        boolean comparePair = mode == InsertionMode.GREEDY && pos + 2 < n;
        List<ScheduledTask> schedule = PlanUtils.generateScheduleFromSnapshotInPlace(
          seq, satellite, mode, prefixSchedule, prefixIds, startIdx,
          comparePair ? pos + 2 : n, snapshotReplayScratch);
        if (comparePair) {
          if (hasSameScheduleState(schedule, snapshotResult.scheduleSnapshots.get(pos + 1))) {
            // Identical prefix and unchanged suffix imply identical profit: strict > rejects.
            continue;
          }
          schedule = PlanUtils.continueScheduleFromSnapshotInPlace(
            seq, satellite, mode, prefixSchedule, pos + 2, snapshotReplayScratch);
        }
        double swapFitness = (double) PlanUtils.calculateProfit(schedule);
        if (swapFitness > currentFitness) {
          double gain = swapFitness - currentFitness;
          totalGain += gain;
          acceptedSwaps++;
          currentFitness = swapFitness;
          currentBest = new SchedulingPlan(seq);
          currentBest.setSchedule(schedule);
          currentBest.setFitness(swapFitness);
          accepted = true;
        }
      } finally {
        snapshotReplayScratch.rollback(prefixSchedule);
        if (!accepted) {
          Collections.swap(seq, pos, pos + 1);
        }
      }
      if (accepted) {
        snapshotResult = PlanUtils.generateScheduleWithSnapshotRange(
          seq, satellite, mode, snapshotStart, snapshotEnd);
      }
    }
    ADJACENT_SWAP_STATS.recordCall(swapPositionCount, acceptedSwaps, totalGain, System.nanoTime() - startNanos);
    return currentBest;
  }

  private static boolean hasSameScheduleState(List<ScheduledTask> left, List<ScheduledTask> right) {
    if (right == null || left.size() != right.size()) {
      return false;
    }
    for (int i = 0; i < left.size(); i++) {
      ScheduledTask a = left.get(i);
      ScheduledTask b = right.get(i);
      // Input object identity proves equality of every decoder-visible field.
      if (a.getTask() != b.getTask() || a.getSatelliteId() != b.getSatelliteId()
        || a.getTimeWindow() != b.getTimeWindow() || a.getStartRecord() != b.getStartRecord()
        || a.getEndRecord() != b.getEndRecord()) {
        return false;
      }
    }
    return true;
  }

  private List<Task> buildFastRefineBaseSequence(SatelliteState state, TaskDelta taskDelta) {
    List<Task> baseSequence = new ArrayList<>();
    PlanUtils.TaskIdMarker retainedIds = new PlanUtils.TaskIdMarker();
    for (Task task : taskDelta.retainedTasks) {
      retainedIds.add(task.getId());
    }
    for (Task task : state.bestPlan.getTaskSequence()) {
      if (task != null && retainedIds.contains(task.getId())) {
        baseSequence.add(task);
      }
    }
    return baseSequence;
  }

  private static List<Integer> computeRemovalAnchorPositions(
    SatelliteState state,
    TaskDelta roundTaskDelta,
    List<Task> baseSequence
  ) {
    if (roundTaskDelta.removedTasks.isEmpty()) {
      return null;
    }
    PlanUtils.TaskIdMarker removedIds = new PlanUtils.TaskIdMarker();
    for (Task task : roundTaskDelta.removedTasks) {
      removedIds.add(task.getId());
    }
    PlanUtils.TaskIdMarker baseIds = new PlanUtils.TaskIdMarker();
    for (Task task : baseSequence) {
      baseIds.add(task.getId());
    }
    List<Integer> anchors = new ArrayList<>();
    int retainedCount = 0;
    for (Task task : state.bestPlan.getTaskSequence()) {
      if (task == null) continue;
      if (removedIds.contains(task.getId())) {
        anchors.add(retainedCount);
      } else if (baseIds.contains(task.getId())) {
        retainedCount++;
      }
    }
    return anchors.isEmpty() ? null : anchors;
  }

  private TaskDelta collectTaskDelta(List<Task> previousTasks, List<Task> currentTasks) {
    currentTaskIds.clear();
    for (Task task : currentTasks) {
      if (task != null) {
        currentTaskIds.add(task.getId());
      }
    }
    previousTaskIds.clear();
    for (Task task : previousTasks) {
      if (task != null) {
        previousTaskIds.add(task.getId());
      }
    }
    List<Task> retainedTasks = new ArrayList<>();
    List<Task> addedTasks = new ArrayList<>();
    List<Task> removedTasks = new ArrayList<>();
    for (Task task : currentTasks) {
      if (task == null) {
        continue;
      }
      if (previousTaskIds.contains(task.getId())) {
        retainedTasks.add(task);
      } else {
        addedTasks.add(task);
      }
    }
    for (Task task : previousTasks) {
      if (task != null && !currentTaskIds.contains(task.getId())) {
        removedTasks.add(task);
      }
    }
    return new TaskDelta(retainedTasks, addedTasks, removedTasks);
  }

  private boolean isFastRefineEligible(TaskDelta roundTaskDelta) {
    return roundTaskDelta != null && isDeltaFastRefineEnabled();
  }

  private boolean isDeltaFastRefineEnabled() {
    return Boolean.parseBoolean(System.getProperty(DELTA_FAST_REFINE_ENABLED_PROPERTY, "true"));
  }

  private List<Task> filterSequenceByAssignment(
    List<Task> sequence,
    int satelliteId,
    Map<Integer, Integer> assignmentSnapshot
  ) {
    List<Task> filtered = new ArrayList<>();
    PlanUtils.TaskIdMarker seenTaskIds = new PlanUtils.TaskIdMarker();
    for (Task task : sequence) {
      if (task == null) {
        continue;
      }
      Integer assignedSatelliteId = assignmentSnapshot.get(task.getId());
      if (assignedSatelliteId != null && assignedSatelliteId == satelliteId && seenTaskIds.add(task.getId())) {
        filtered.add(task);
      }
    }
    return filtered;
  }

  static final class AdjacentSwapStats {
    private final AtomicLong callCount = new AtomicLong();
    private final AtomicLong attemptedPositions = new AtomicLong();
    private final AtomicLong acceptedSwaps = new AtomicLong();
    private final AtomicLong elapsedNanos = new AtomicLong();
    private final DoubleAdder profitGain = new DoubleAdder();

    void reset() {
      callCount.set(0L);
      attemptedPositions.set(0L);
      acceptedSwaps.set(0L);
      elapsedNanos.set(0L);
      profitGain.reset();
    }

    void recordCall(int attemptedPositionCount, int acceptedSwapCount, double gain, long elapsedNanosForCall) {
      callCount.incrementAndGet();
      attemptedPositions.addAndGet(attemptedPositionCount);
      acceptedSwaps.addAndGet(acceptedSwapCount);
      elapsedNanos.addAndGet(elapsedNanosForCall);
      profitGain.add(gain);
    }

    Snapshot snapshot() {
      return new Snapshot(
        callCount.get(),
        attemptedPositions.get(),
        acceptedSwaps.get(),
        profitGain.sum(),
        elapsedNanos.get()
      );
    }

    static final class Snapshot {
      final long callCount;
      final long attemptedPositions;
      final long acceptedSwaps;
      final double profitGain;
      final long elapsedNanos;

      Snapshot(
        long callCount,
        long attemptedPositions,
        long acceptedSwaps,
        double profitGain,
        long elapsedNanos
      ) {
        this.callCount = callCount;
        this.attemptedPositions = attemptedPositions;
        this.acceptedSwaps = acceptedSwaps;
        this.profitGain = profitGain;
        this.elapsedNanos = elapsedNanos;
      }
    }
  }
}
