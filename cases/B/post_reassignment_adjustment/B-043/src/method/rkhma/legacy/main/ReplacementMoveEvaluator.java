package method.rkhma.legacy.main;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import method.rkhma.legacy.common.TuningConfig;
import method.rkhma.legacy.common.TuningDefaults;
import method.rkhma.legacy.model.ScheduledTask;
import method.rkhma.legacy.model.SchedulingPlan;
import method.rkhma.legacy.model.Task;
import method.rkhma.legacy.model.TimeWindow;

final class ReplacementMoveEvaluator {
  private static final int REPLACEMENT_MAX_TRIALS_PER_SAT = TuningConfig.getInt("coord.replacementMaxTrialsPerSat", TuningDefaults.COORD_REPLACEMENT_MAX_TRIALS_PER_SAT);
  private static final int MAX_BATCH_REMOVAL = TuningConfig.getInt("coord.maxBatchRemoval", TuningDefaults.COORD_MAX_BATCH_REMOVAL);
  private static final double REPLACEMENT_NEARBY_GAP_SECONDS = TuningConfig.getDouble("coord.replacementNearbyGapSeconds", TuningDefaults.COORD_REPLACEMENT_NEARBY_GAP_SECONDS);

  private final ReassignmentMoveServices services;

  ReplacementMoveEvaluator(ReassignmentMoveServices services) {
    this.services = services;
  }

  List<ScheduledTask> identifyReplaceableTasks(
    Task newTask,
    List<ScheduledTask> schedule,
    int targetSatelliteId
  ) {
    int newPriority = newTask.getPriority();
    List<TimeWindow> candidateWindows = newTask.getTimeWindowsForSatellite(targetSatelliteId);

    List<ScheduledTask> overlapCandidates = new ArrayList<>();
    List<ScheduledTask> nearbyCandidates = new ArrayList<>();
    Map<Integer, Double> overlapAmountMap = new HashMap<>();
    for (ScheduledTask scheduledTask : schedule) {
      if (scheduledTask == null || scheduledTask.getTask() == null) {
        continue;
      }
      int existingPriority = scheduledTask.getTask().getPriority();
      if (existingPriority > newPriority) {
        continue;
      }
      if (existingPriority == newPriority
        && services.getGlobalTaskKeyOrDefault(scheduledTask.getTask().getId(), 0.0)
        >= services.getGlobalTaskKeyOrDefault(newTask.getId(), 0.0)) {
        continue;
      }
      if (!scheduledTask.isScheduled()) {
        continue;
      }

      double scheduledStart = scheduledTask.getStartRecord().getTotalSeconds();
      double scheduledEnd = scheduledTask.getEndRecord().getTotalSeconds();
      boolean hasOverlap = false;
      boolean hasNearby = false;
      double totalOverlap = 0.0;
      for (TimeWindow candidateWindow : candidateWindows) {
        double overlapStart = Math.max(scheduledStart, candidateWindow.getStartTime());
        double overlapEnd = Math.min(scheduledEnd, candidateWindow.getEndTime());
        if (overlapEnd > overlapStart) {
          hasOverlap = true;
          totalOverlap += overlapEnd - overlapStart;
        } else if (!hasOverlap) {
          double gap = Math.max(
            candidateWindow.getStartTime() - scheduledEnd,
            scheduledStart - candidateWindow.getEndTime()
          );
          if (gap <= REPLACEMENT_NEARBY_GAP_SECONDS) {
            hasNearby = true;
          }
        }
      }
      if (hasOverlap) {
        overlapCandidates.add(scheduledTask);
        overlapAmountMap.put(scheduledTask.getTask().getId(), totalOverlap);
      } else if (hasNearby) {
        nearbyCandidates.add(scheduledTask);
      }
    }
    Comparator<ScheduledTask> comparator = Comparator.comparingInt(ScheduledTask::getPriority)
      .thenComparing((a, b) -> Double.compare(
        overlapAmountMap.getOrDefault(b.getTask().getId(), 0.0),
        overlapAmountMap.getOrDefault(a.getTask().getId(), 0.0)
      ));
    overlapCandidates.sort(comparator);
    nearbyCandidates.sort(Comparator.comparingInt(ScheduledTask::getPriority));
    List<ScheduledTask> result = new ArrayList<>(overlapCandidates);
    result.addAll(nearbyCandidates);
    return result;
  }

  TrialMoveResult tryReplacementMove(
    Task highValueTask,
    Integer currentSatelliteId,
    int currentIter,
    Map<Integer, Integer> baseAssignment,
    Map<Integer, SchedulingPlan> basePlans,
    double baseProfit,
    TaskAllocationCoordinator.HybCecConfig config
  ) throws Exception {
    SatelliteCandidateSelection candidateSelection = services.selectTopTargetSatellites(
      highValueTask,
      currentSatelliteId,
      currentIter,
      baseAssignment,
      true
    );

    ReplacementBestMove bestMove = new ReplacementBestMove();

    for (Integer targetSatId : candidateSelection.satelliteIds) {
      SchedulingPlan targetPlan = basePlans.get(targetSatId);
      if (targetPlan == null || targetPlan.getSchedule() == null) {
        continue;
      }

      List<ScheduledTask> replaceableTasks = identifyReplaceableTasks(
        highValueTask,
        targetPlan.getSchedule(),
        targetSatId
      );
      if (replaceableTasks.isEmpty()) {
        continue;
      }

      List<TimeWindow> targetWindows = highValueTask.getTimeWindowsForSatellite(targetSatId);
      int trialsThisSat = Math.min(REPLACEMENT_MAX_TRIALS_PER_SAT, replaceableTasks.size());
      double bestProfitGainThisSat = 0.0;
      for (int replacementIndex = 0; replacementIndex < trialsThisSat; replacementIndex++) {
        ScheduledTask candidateScheduledTask = replaceableTasks.get(replacementIndex);
        Task lowValueTask = candidateScheduledTask.getTask();
        TrialMoveResult trialResult = evaluateSingleReplacementTrial(
          highValueTask,
          lowValueTask,
          currentSatelliteId,
          targetSatId,
          baseAssignment,
          basePlans,
          baseProfit,
          config
        );

        bestMove.acceptSingle(trialResult, lowValueTask, targetSatId);
        if (trialResult.profitGain > bestProfitGainThisSat) {
          bestProfitGainThisSat = trialResult.profitGain;
        }
      }

      if (bestProfitGainThisSat <= 0.0 && replaceableTasks.size() >= 2) {
        List<ScheduledTask> overlapSorted = sortByOverlapDescending(replaceableTasks, targetWindows);
        int batchLimit = Math.min(MAX_BATCH_REMOVAL, overlapSorted.size());
        List<Task> tasksToRemove = new ArrayList<>();
        for (int batchIndex = 0; batchIndex < batchLimit; batchIndex++) {
          tasksToRemove.add(overlapSorted.get(batchIndex).getTask());
          if (tasksToRemove.size() < 2) {
            continue;
          }
          TrialMoveResult trialResult = evaluateBatchRemovalTrial(
            highValueTask,
            tasksToRemove,
            currentSatelliteId,
            targetSatId,
            baseAssignment,
            basePlans,
            baseProfit,
            config
          );

          if (bestMove.acceptBatch(trialResult, tasksToRemove, targetSatId)) {
            break;
          }
        }
      }
    }

    if (bestMove.hasResult()) {
      services.applyTaskSatelliteFeedback(highValueTask.getId(), bestMove.targetSatelliteId, true);
    }

    return bestMove.toResultWithDisplacedTasks();
  }

  private static final class ReplacementBestMove {
    private TrialMoveResult result;
    private double profitGain = 0.0;
    private Task replacedTask;
    private List<Task> replacedBatch;
    private int targetSatelliteId = -1;

    private boolean hasResult() {
      return result != null;
    }

    private void acceptSingle(
      TrialMoveResult trialResult,
      Task lowValueTask,
      int targetSatelliteId
    ) {
      if (!isBetterThanCurrent(trialResult)) {
        return;
      }
      this.result = trialResult;
      this.profitGain = trialResult.profitGain;
      this.replacedTask = lowValueTask;
      this.replacedBatch = null;
      this.targetSatelliteId = targetSatelliteId;
    }

    private boolean acceptBatch(
      TrialMoveResult trialResult,
      List<Task> tasksToRemove,
      int targetSatelliteId
    ) {
      if (!isBetterThanCurrent(trialResult)) {
        return false;
      }
      this.result = trialResult;
      this.profitGain = trialResult.profitGain;
      this.replacedTask = null;
      this.replacedBatch = new ArrayList<>(tasksToRemove);
      this.targetSatelliteId = targetSatelliteId;
      return true;
    }

    private boolean isBetterThanCurrent(TrialMoveResult trialResult) {
      return trialResult.profitGain > profitGain;
    }

    private TrialMoveResult toResultWithDisplacedTasks() {
      if (result == null) {
        return null;
      }
      List<Task> displaced = replacedBatch != null
        ? new ArrayList<>(replacedBatch)
        : Collections.singletonList(replacedTask);
      return new TrialMoveResult(
        result.assignment,
        result.plans,
        result.totalProfit,
        result.profitGain,
        displaced
      );
    }
  }

  private TrialMoveResult evaluateSingleReplacementTrial(
    Task highValueTask,
    Task lowValueTask,
    Integer currentSatelliteId,
    int targetSatelliteId,
    Map<Integer, Integer> baseAssignment,
    Map<Integer, SchedulingPlan> basePlans,
    double baseProfit,
    TaskAllocationCoordinator.HybCecConfig config
  ) throws Exception {
    Map<Integer, Integer> trialAssignment = AssignmentOverlayMap.over(baseAssignment)
      .withRemoved(lowValueTask.getId())
      .withAssigned(highValueTask.getId(), targetSatelliteId);

    Set<Integer> affectedSatelliteIds = buildAffectedSatelliteIds(
      currentSatelliteId,
      targetSatelliteId
    );
    return services.evaluateTrialAssignment(
      basePlans,
      baseAssignment,
      trialAssignment,
      affectedSatelliteIds,
      baseProfit,
      config
    );
  }

  private TrialMoveResult evaluateBatchRemovalTrial(
    Task highValueTask,
    List<Task> tasksToRemove,
    Integer currentSatelliteId,
    int targetSatelliteId,
    Map<Integer, Integer> baseAssignment,
    Map<Integer, SchedulingPlan> basePlans,
    double baseProfit,
    TaskAllocationCoordinator.HybCecConfig config
  ) throws Exception {
    AssignmentOverlayMap trialAssignment = AssignmentOverlayMap.over(baseAssignment);
    for (Task removed : tasksToRemove) {
      trialAssignment = trialAssignment.withRemoved(removed.getId());
    }
    trialAssignment = trialAssignment.withAssigned(highValueTask.getId(), targetSatelliteId);

    Set<Integer> affectedSatelliteIds = buildAffectedSatelliteIds(
      currentSatelliteId,
      targetSatelliteId
    );
    return services.evaluateTrialAssignment(
      basePlans,
      baseAssignment,
      trialAssignment,
      affectedSatelliteIds,
      baseProfit,
      config
    );
  }

  private static Set<Integer> buildAffectedSatelliteIds(
    Integer currentSatelliteId,
    int targetSatelliteId
  ) {
    Set<Integer> affectedSatelliteIds = new LinkedHashSet<>();
    if (currentSatelliteId != null) {
      affectedSatelliteIds.add(currentSatelliteId);
    }
    affectedSatelliteIds.add(targetSatelliteId);
    return affectedSatelliteIds;
  }

  private static List<ScheduledTask> sortByOverlapDescending(
    List<ScheduledTask> replaceableTasks,
    List<TimeWindow> candidateWindows
  ) {
    List<ScheduledTask> sorted = new ArrayList<>(replaceableTasks);
    sorted.sort((a, b) -> {
      double overlapA = computeTotalOverlap(a, candidateWindows);
      double overlapB = computeTotalOverlap(b, candidateWindows);
      return Double.compare(overlapB, overlapA);
    });
    return sorted;
  }

  private static double computeTotalOverlap(ScheduledTask scheduledTask, List<TimeWindow> candidateWindows) {
    double scheduledStart = scheduledTask.getStartRecord().getTotalSeconds();
    double scheduledEnd = scheduledTask.getEndRecord().getTotalSeconds();
    double totalOverlap = 0.0;
    for (TimeWindow candidateWindow : candidateWindows) {
      double overlapStart = Math.max(scheduledStart, candidateWindow.getStartTime());
      double overlapEnd = Math.min(scheduledEnd, candidateWindow.getEndTime());
      if (overlapEnd > overlapStart) {
        totalOverlap += overlapEnd - overlapStart;
      }
    }
    return totalOverlap;
  }
}
