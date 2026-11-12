package method.rkhma.legacy.main;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import method.rkhma.legacy.model.Satellite;
import method.rkhma.legacy.model.ScheduledTask;
import method.rkhma.legacy.model.SchedulingPlan;
import method.rkhma.legacy.model.Task;

final class ReassignmentOrchestrator {
  interface Context {
    default void checkpoint(Map<Integer, Integer> assignment, Map<Integer, SchedulingPlan> plans) { }

    SatelliteCandidateSelection selectTopTargetSatellites(
      Task task,
      Integer currentSatelliteId,
      int currentIter,
      Map<Integer, Integer> assignmentSnapshot,
      boolean prioritizedTask
    );

    TrialMoveResult evaluateTrialMove(
      Task task,
      Integer sourceSatelliteId,
      int targetSatelliteId,
      Map<Integer, Integer> baseAssignment,
      Map<Integer, SchedulingPlan> basePlans,
      double baseProfit,
      TaskAllocationCoordinator.HybCecConfig config
    ) throws Exception;

    Satellite getSatelliteById(int satelliteId);

    void applyTaskSatelliteFeedback(int taskId, int satelliteId, boolean success);
  }

  private final int priorityTaskTopK;
  private final ReassignmentMoveEvaluator moveEvaluator;
  private final Context context;

  ReassignmentOrchestrator(
    int priorityTaskTopK,
    ReassignmentMoveEvaluator moveEvaluator,
    Context context
  ) {
    this.priorityTaskTopK = priorityTaskTopK;
    this.moveEvaluator = moveEvaluator;
    this.context = context;
  }

  void runPrioritizedMigrationLoop(
    List<Task> prioritizedUnscheduledTasks,
    int prioritizedTaskCount,
    int maxMigrationsThisIter,
    int currentIter,
    TaskAllocationCoordinator.HybCecConfig config,
    ReassignmentWorkingState workingState,
    ReassignmentStats stats
  ) throws Exception {
    int consecutiveFailures = 0;
    int progressiveSkipThreshold = Math.max(50, (int)(prioritizedUnscheduledTasks.size() * 0.20));
    for (int index = 0; index < prioritizedUnscheduledTasks.size(); index++) {
      context.checkpoint(workingState.candidateAssignment, workingState.candidatePlans);
      if (stats.migrationCount >= maxMigrationsThisIter) {
        break;
      }
      int migrationCountBefore = stats.migrationCount;
      Task task = prioritizedUnscheduledTasks.get(index);
      boolean prioritizedTask = index < prioritizedTaskCount;
      tryStandardOrAdvancedMove(
        task,
        prioritizedTask,
        prioritizedUnscheduledTasks,
        currentIter,
        config,
        workingState,
        stats
      );
      context.checkpoint(workingState.candidateAssignment, workingState.candidatePlans);
      if (stats.migrationCount > migrationCountBefore) {
        consecutiveFailures = 0;
      } else {
        consecutiveFailures++;
        if (consecutiveFailures >= progressiveSkipThreshold) {
          break;
        }
      }
    }
  }

  void tryBundleMoves(
    List<Task> prioritizedUnscheduledTasks,
    int currentIter,
    TaskAllocationCoordinator.HybCecConfig config,
    ReassignmentWorkingState workingState,
    ReassignmentStats stats
  ) throws Exception {
    List<Task> stillUnscheduled = collectStillUnscheduledTasks(
      prioritizedUnscheduledTasks,
      workingState.candidatePlans
    );
    if (stillUnscheduled.size() < 2) {
      return;
    }
    List<List<Task>> bundles = moveEvaluator.buildBundleCandidates(
      stillUnscheduled,
      workingState.candidateAssignment,
      currentIter
    );
    Set<Integer> bundledTaskIds = new HashSet<>();
    for (List<Task> bundle : bundles) {
      context.checkpoint(workingState.candidateAssignment, workingState.candidatePlans);
      if (containsBundledTask(bundle, bundledTaskIds)) {
        continue;
      }
      TrialMoveResult bundleResult = moveEvaluator.tryBundleMove(
        bundle,
        currentIter,
        workingState.candidateAssignment,
        workingState.candidatePlans,
        workingState.candidateProfit,
        config
      );
      if (bundleResult != null && bundleResult.profitGain > 0.0) {
        workingState.apply(bundleResult);
        context.checkpoint(workingState.candidateAssignment, workingState.candidatePlans);
        stats.migrationCount += bundle.size();
        stats.recordBundleMove(bundleResult.profitGain);
        for (Task task : bundle) {
          bundledTaskIds.add(task.getId());
        }
      }
    }
  }

  private void tryStandardOrAdvancedMove(
    Task task,
    boolean prioritizedTask,
    List<Task> prioritizedUnscheduledTasks,
    int currentIter,
    TaskAllocationCoordinator.HybCecConfig config,
    ReassignmentWorkingState workingState,
    ReassignmentStats stats
  ) throws Exception {
    Integer currentSatelliteId = workingState.candidateAssignment.get(task.getId());
    SatelliteCandidateSelection candidateSelection = context.selectTopTargetSatellites(
      task,
      currentSatelliteId,
      currentIter,
      workingState.candidateAssignment,
      prioritizedTask
    );
    stats.candidateCountBeforeFilter += candidateSelection.eligibleCount;
    stats.candidateCountAfterFilter += candidateSelection.satelliteIds.size();
    StandardMoveSelection standardMove = selectStandardMove(
      task,
      currentSatelliteId,
      candidateSelection,
      config,
      workingState
    );
    if (standardMove.hasAcceptedMove()) {
      acceptStandardMove(task, currentSatelliteId, standardMove, currentIter, workingState, stats);
    } else if (standardMove.hasFailedCandidate()) {
      context.applyTaskSatelliteFeedback(task.getId(), standardMove.failedSatelliteId, false);
    }
    boolean eligibleForAdvancedMoves = prioritizedTask
      || prioritizedUnscheduledTasks.size() <= priorityTaskTopK * 2;
    if (!standardMove.hasAcceptedMove() && eligibleForAdvancedMoves) {
      tryAdvancedMove(
        task,
        currentSatelliteId,
        prioritizedUnscheduledTasks,
        currentIter,
        config,
        workingState,
        stats
      );
    }
  }

  private StandardMoveSelection selectStandardMove(
    Task task,
    Integer currentSatelliteId,
    SatelliteCandidateSelection candidateSelection,
    TaskAllocationCoordinator.HybCecConfig config,
    ReassignmentWorkingState workingState
  ) throws Exception {
    StandardMoveSelection selection = new StandardMoveSelection();
    for (Integer satelliteId : candidateSelection.satelliteIds) {
      TrialMoveResult trialMove = context.evaluateTrialMove(
        task,
        currentSatelliteId,
        satelliteId,
        workingState.candidateAssignment,
        workingState.candidatePlans,
        workingState.candidateProfit,
        config
      );
      selection.consider(trialMove, satelliteId, context.getSatelliteById(satelliteId));
    }
    return selection;
  }

  private void acceptStandardMove(
    Task task,
    Integer currentSatelliteId,
    StandardMoveSelection selection,
    int currentIter,
    ReassignmentWorkingState workingState,
    ReassignmentStats stats
  ) {
    context.applyTaskSatelliteFeedback(task.getId(), selection.acceptedTarget.getId(), true);
    workingState.apply(selection.acceptedTrialMove);
    stats.migrationCount++;
    stats.recordStandardMove(selection.acceptedTrialMove.profitGain);
  }

  private static final class StandardMoveSelection {
    private Satellite acceptedTarget;
    private TrialMoveResult acceptedTrialMove;
    private double acceptedProfitGain = 0.0;
    private Integer failedSatelliteId;
    private double failedProfitGain = Double.NEGATIVE_INFINITY;

    private void consider(
      TrialMoveResult trialMove,
      int satelliteId,
      Satellite targetSatellite
    ) {
      if (trialMove.profitGain > acceptedProfitGain) {
        acceptedProfitGain = trialMove.profitGain;
        acceptedTarget = targetSatellite;
        acceptedTrialMove = trialMove;
      }
      if (trialMove.profitGain <= 0.0 && trialMove.profitGain > failedProfitGain) {
        failedProfitGain = trialMove.profitGain;
        failedSatelliteId = satelliteId;
      }
    }

    private boolean hasAcceptedMove() {
      return acceptedTarget != null && acceptedTrialMove != null;
    }

    private boolean hasFailedCandidate() {
      return failedSatelliteId != null;
    }
  }

  private void tryAdvancedMove(
    Task task,
    Integer currentSatelliteId,
    List<Task> prioritizedUnscheduledTasks,
    int currentIter,
    TaskAllocationCoordinator.HybCecConfig config,
    ReassignmentWorkingState workingState,
    ReassignmentStats stats
  ) throws Exception {
    TrialMoveResult replacementResult = moveEvaluator.tryReplacementMove(
      task,
      currentSatelliteId,
      currentIter,
      workingState.candidateAssignment,
      workingState.candidatePlans,
      workingState.candidateProfit,
      config
    );
    if (replacementResult != null) {
      workingState.apply(replacementResult);
      stats.migrationCount++;
      stats.recordReplacementMove(replacementResult.profitGain);
      for (Task displaced : replacementResult.displacedTasks) {
        if (displaced != null && !prioritizedUnscheduledTasks.contains(displaced)) {
          prioritizedUnscheduledTasks.add(displaced);
        }
      }
      return;
    }
    TrialMoveResult swapResult = moveEvaluator.trySwapMove(
      task,
      currentSatelliteId,
      currentIter,
      workingState.candidateAssignment,
      workingState.candidatePlans,
      workingState.candidateProfit,
      config
    );
    if (swapResult != null) {
      workingState.apply(swapResult);
      stats.migrationCount++;
      stats.recordSwapMove(swapResult.profitGain);
    }
  }

  private List<Task> collectStillUnscheduledTasks(
    List<Task> prioritizedUnscheduledTasks,
    Map<Integer, SchedulingPlan> candidatePlans
  ) {
    Set<Integer> scheduledTaskIds = new HashSet<>();
    for (SchedulingPlan plan : candidatePlans.values()) {
      if (plan != null && plan.getSchedule() != null) {
        for (ScheduledTask scheduledTask : plan.getSchedule()) {
          if (scheduledTask != null && scheduledTask.isScheduled()) {
            scheduledTaskIds.add(scheduledTask.getTaskId());
          }
        }
      }
    }
    List<Task> stillUnscheduled = new ArrayList<>();
    for (Task task : prioritizedUnscheduledTasks) {
      if (!scheduledTaskIds.contains(task.getId())) {
        stillUnscheduled.add(task);
      }
    }
    return stillUnscheduled;
  }

  private boolean containsBundledTask(List<Task> bundle, Set<Integer> bundledTaskIds) {
    for (Task task : bundle) {
      if (bundledTaskIds.contains(task.getId())) {
        return true;
      }
    }
    return false;
  }
}
