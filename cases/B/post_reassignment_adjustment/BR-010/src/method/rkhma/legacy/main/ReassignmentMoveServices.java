package method.rkhma.legacy.main;

import java.util.List;
import java.util.Map;
import java.util.Set;

import method.rkhma.legacy.model.Satellite;
import method.rkhma.legacy.model.SchedulingPlan;
import method.rkhma.legacy.model.Task;

final class ReassignmentMoveServices {
  private final List<Satellite> satellites;
  private final Map<Integer, Double> globalTaskKeys;
  private final AssignmentDeltaEvaluator assignmentDeltaEvaluator;
  private final ReassignmentMoveEvaluator.Context context;

  ReassignmentMoveServices(
    List<Satellite> satellites,
    Map<Integer, Double> globalTaskKeys,
    AssignmentDeltaEvaluator assignmentDeltaEvaluator,
    ReassignmentMoveEvaluator.Context context
  ) {
    this.satellites = satellites;
    this.globalTaskKeys = globalTaskKeys;
    this.assignmentDeltaEvaluator = assignmentDeltaEvaluator;
    this.context = context;
  }

  List<Satellite> satellites() {
    return satellites;
  }

  double getGlobalTaskKeyOrDefault(int taskId, double defaultValue) {
    return globalTaskKeys.getOrDefault(taskId, defaultValue);
  }

  SatelliteCandidateSelection selectTopTargetSatellites(
    Task task,
    Integer currentSatelliteId,
    int currentIter,
    Map<Integer, Integer> assignmentSnapshot,
    boolean prioritizedTask
  ) {
    return context.selectTopTargetSatellites(
      task,
      currentSatelliteId,
      currentIter,
      assignmentSnapshot,
      prioritizedTask
    );
  }

  void applyTaskSatelliteFeedback(int taskId, int satelliteId, boolean success) {
    context.applyTaskSatelliteFeedback(taskId, satelliteId, success);
  }

  TrialMoveResult evaluateTrialAssignment(
    Map<Integer, SchedulingPlan> basePlans,
    Map<Integer, Integer> baseAssignment,
    Map<Integer, Integer> trialAssignment,
    Set<Integer> affectedSatelliteIds,
    double baseProfit,
    TaskAllocationCoordinator.HybCecConfig config
  ) throws Exception {
    Map<Integer, SchedulingPlan> trialPlans = assignmentDeltaEvaluator.buildDeltaPlans(
      basePlans,
      baseAssignment,
      trialAssignment,
      affectedSatelliteIds,
      config
    );
    double trialProfit = calculateDeltaProfit(
      basePlans,
      trialPlans,
      affectedSatelliteIds,
      baseProfit
    );
    double profitGain = trialProfit - baseProfit;
    return new TrialMoveResult(trialAssignment, trialPlans, trialProfit, profitGain);
  }

  private double calculateDeltaProfit(
    Map<Integer, SchedulingPlan> basePlans,
    Map<Integer, SchedulingPlan> trialPlans,
    Set<Integer> affectedSatelliteIds,
    double baseProfit
  ) {
    return assignmentDeltaEvaluator.calculateDeltaProfit(
      basePlans,
      trialPlans,
      affectedSatelliteIds,
      baseProfit
    );
  }
}
