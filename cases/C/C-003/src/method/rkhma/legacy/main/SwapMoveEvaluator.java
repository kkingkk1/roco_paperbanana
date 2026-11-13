package method.rkhma.legacy.main;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import method.rkhma.legacy.common.TuningConfig;
import method.rkhma.legacy.common.TuningDefaults;
import method.rkhma.legacy.model.Satellite;
import method.rkhma.legacy.model.ScheduledTask;
import method.rkhma.legacy.model.SchedulingPlan;
import method.rkhma.legacy.model.Task;
import method.rkhma.legacy.model.TimeWindow;

final class SwapMoveEvaluator {
  private static final int SWAP_MAX_CANDIDATES_PER_SAT = TuningConfig.getInt("coord.swapMaxCandidatesPerSat", TuningDefaults.COORD_SWAP_MAX_CANDIDATES_PER_SAT);
  private static final int SWAP_MAX_RECEIVER_SATS = TuningConfig.getInt("coord.swapMaxReceiverSats", TuningDefaults.COORD_SWAP_MAX_RECEIVER_SATS);

  private final ReassignmentMoveServices services;

  SwapMoveEvaluator(ReassignmentMoveServices services) {
    this.services = services;
  }

  TrialMoveResult trySwapMove(
    Task unscheduledTask,
    Integer currentSatelliteId,
    int currentIter,
    Map<Integer, Integer> baseAssignment,
    Map<Integer, SchedulingPlan> basePlans,
    double baseProfit,
    TaskAllocationCoordinator.HybCecConfig config
  ) throws Exception {
    SwapBestMove bestMove = new SwapBestMove();

    SatelliteCandidateSelection candidateSelection = services.selectTopTargetSatellites(
      unscheduledTask,
      currentSatelliteId,
      currentIter,
      baseAssignment,
      true
    );

    for (int targetSatId : candidateSelection.satelliteIds) {
      SchedulingPlan targetPlan = basePlans.get(targetSatId);
      if (targetPlan == null || targetPlan.getSchedule() == null) {
        continue;
      }

      List<Task> swapCandidates = identifySwapCandidates(
        unscheduledTask,
        targetPlan.getSchedule(),
        targetSatId
      );
      if (swapCandidates.isEmpty()) {
        continue;
      }

      int trialsThisSat = Math.min(SWAP_MAX_CANDIDATES_PER_SAT, swapCandidates.size());
      for (int replacementIndex = 0; replacementIndex < trialsThisSat; replacementIndex++) {
        Task swapTask = swapCandidates.get(replacementIndex);
        List<Integer> receiverSatIds = findReceiverSatellites(swapTask, targetSatId);
        int receiversToTry = Math.min(SWAP_MAX_RECEIVER_SATS, receiverSatIds.size());

        for (int receiverIndex = 0; receiverIndex < receiversToTry; receiverIndex++) {
          int receiverSatId = receiverSatIds.get(receiverIndex);
          TrialMoveResult trialResult = evaluateSwapTrial(
            unscheduledTask,
            swapTask,
            currentSatelliteId,
            targetSatId,
            receiverSatId,
            baseAssignment,
            basePlans,
            baseProfit,
            config
          );
          bestMove.accept(trialResult, swapTask, targetSatId, receiverSatId);
        }
      }
    }

    if (bestMove.hasResult()) {
      services.applyTaskSatelliteFeedback(unscheduledTask.getId(), bestMove.targetSatelliteId, true);
      services.applyTaskSatelliteFeedback(bestMove.swappedTask.getId(), bestMove.receiverSatelliteId, true);
    }

    return bestMove.result;
  }

  private TrialMoveResult evaluateSwapTrial(
    Task unscheduledTask,
    Task swapTask,
    Integer currentSatelliteId,
    int targetSatelliteId,
    int receiverSatelliteId,
    Map<Integer, Integer> baseAssignment,
    Map<Integer, SchedulingPlan> basePlans,
    double baseProfit,
    TaskAllocationCoordinator.HybCecConfig config
  ) throws Exception {
    Map<Integer, Integer> trialAssignment = AssignmentOverlayMap.over(baseAssignment)
      .withAssigned(unscheduledTask.getId(), targetSatelliteId)
      .withAssigned(swapTask.getId(), receiverSatelliteId);

    Set<Integer> affectedSatelliteIds = new HashSet<>();
    affectedSatelliteIds.add(targetSatelliteId);
    affectedSatelliteIds.add(receiverSatelliteId);
    if (currentSatelliteId != null) {
      affectedSatelliteIds.add(currentSatelliteId);
    }
    return services.evaluateTrialAssignment(
      basePlans,
      baseAssignment,
      trialAssignment,
      affectedSatelliteIds,
      baseProfit,
      config
    );
  }

  private static final class SwapBestMove {
    private TrialMoveResult result;
    private double profitGain = 0.0;
    private Task swappedTask;
    private int targetSatelliteId = -1;
    private int receiverSatelliteId = -1;

    private boolean hasResult() {
      return result != null;
    }

    private void accept(
      TrialMoveResult trialResult,
      Task swapTask,
      int targetSatelliteId,
      int receiverSatelliteId
    ) {
      if (trialResult.profitGain <= profitGain) {
        return;
      }
      this.result = trialResult;
      this.profitGain = trialResult.profitGain;
      this.swappedTask = swapTask;
      this.targetSatelliteId = targetSatelliteId;
      this.receiverSatelliteId = receiverSatelliteId;
    }
  }

  List<Task> identifySwapCandidates(
    Task unscheduledTask,
    List<ScheduledTask> schedule,
    int targetSatelliteId
  ) {
    List<TimeWindow> candidateWindows = unscheduledTask.getTimeWindowsForSatellite(targetSatelliteId);
    if (candidateWindows == null || candidateWindows.isEmpty()) {
      return Collections.emptyList();
    }

    List<Task> candidates = new ArrayList<>();
    for (ScheduledTask scheduledTask : schedule) {
      if (scheduledTask == null || scheduledTask.getTask() == null || !scheduledTask.isScheduled()) {
        continue;
      }
      Task existingTask = scheduledTask.getTask();

      boolean hasOtherSatWindow = false;
      for (Satellite satellite : services.satellites()) {
        if (satellite.getId() == targetSatelliteId) {
          continue;
        }
        List<TimeWindow> otherWindows = existingTask.getTimeWindowsForSatellite(satellite.getId());
        if (otherWindows != null && !otherWindows.isEmpty()) {
          hasOtherSatWindow = true;
          break;
        }
      }
      if (!hasOtherSatWindow) {
        continue;
      }

      double scheduledStart = scheduledTask.getStartRecord().getTotalSeconds();
      double scheduledEnd = scheduledTask.getEndRecord().getTotalSeconds();
      boolean hasOverlap = false;
      for (TimeWindow candidateWindow : candidateWindows) {
        if (scheduledStart < candidateWindow.getEndTime() && scheduledEnd > candidateWindow.getStartTime()) {
          hasOverlap = true;
          break;
        }
      }
      if (hasOverlap) {
        candidates.add(existingTask);
      }
    }

    candidates.sort((a, b) -> {
      int priorityComparison = Integer.compare(a.getPriority(), b.getPriority());
      if (priorityComparison != 0) {
        return priorityComparison;
      }
      return Double.compare(
        services.getGlobalTaskKeyOrDefault(a.getId(), 0.0),
        services.getGlobalTaskKeyOrDefault(b.getId(), 0.0)
      );
    });
    return candidates;
  }

  List<Integer> findReceiverSatellites(Task swapTask, int sourceSatelliteId) {
    List<int[]> receivers = new ArrayList<>();
    for (Satellite satellite : services.satellites()) {
      int satelliteId = satellite.getId();
      if (satelliteId == sourceSatelliteId) {
        continue;
      }
      List<TimeWindow> windows = swapTask.getTimeWindowsForSatellite(satelliteId);
      if (windows != null && !windows.isEmpty()) {
        receivers.add(new int[]{satelliteId, windows.size()});
      }
    }
    receivers.sort((a, b) -> Integer.compare(b[1], a[1]));
    List<Integer> result = new ArrayList<>();
    for (int index = 0; index < Math.min(SWAP_MAX_RECEIVER_SATS, receivers.size()); index++) {
      result.add(receivers.get(index)[0]);
    }
    return result;
  }
}
