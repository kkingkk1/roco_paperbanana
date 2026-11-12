package method.rkhma.legacy.main;

import java.util.List;
import java.util.Map;

import method.rkhma.legacy.model.Satellite;
import method.rkhma.legacy.model.ScheduledTask;
import method.rkhma.legacy.model.SchedulingPlan;
import method.rkhma.legacy.model.Task;

final class ReassignmentMoveEvaluator {
  interface Context {
    SatelliteCandidateSelection selectTopTargetSatellites(
      Task task,
      Integer currentSatelliteId,
      int currentIter,
      Map<Integer, Integer> assignmentSnapshot,
      boolean prioritizedTask
    );

    void applyTaskSatelliteFeedback(int taskId, int satelliteId, boolean success);
  }

  private final ReplacementMoveEvaluator replacementMoveEvaluator;
  private final SwapMoveEvaluator swapMoveEvaluator;
  private final BundleMoveEvaluator bundleMoveEvaluator;

  ReassignmentMoveEvaluator(
    List<Satellite> satellites,
    Map<Integer, Double> globalTaskKeys,
    AssignmentDeltaEvaluator assignmentDeltaEvaluator,
    Context context
  ) {
    ReassignmentMoveServices services = new ReassignmentMoveServices(
      satellites,
      globalTaskKeys,
      assignmentDeltaEvaluator,
      context
    );
    this.replacementMoveEvaluator = new ReplacementMoveEvaluator(services);
    this.swapMoveEvaluator = new SwapMoveEvaluator(services);
    this.bundleMoveEvaluator = new BundleMoveEvaluator(services);
  }

  List<ScheduledTask> identifyReplaceableTasks(
    Task newTask,
    List<ScheduledTask> schedule,
    int targetSatelliteId
  ) {
    return replacementMoveEvaluator.identifyReplaceableTasks(newTask, schedule, targetSatelliteId);
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
    return replacementMoveEvaluator.tryReplacementMove(
      highValueTask,
      currentSatelliteId,
      currentIter,
      baseAssignment,
      basePlans,
      baseProfit,
      config
    );
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
    return swapMoveEvaluator.trySwapMove(
      unscheduledTask,
      currentSatelliteId,
      currentIter,
      baseAssignment,
      basePlans,
      baseProfit,
      config
    );
  }

  List<List<Task>> buildBundleCandidates(
    List<Task> failedTasks,
    Map<Integer, Integer> currentAssignment,
    int currentIter
  ) {
    return bundleMoveEvaluator.buildBundleCandidates(failedTasks, currentAssignment, currentIter);
  }

  TrialMoveResult tryBundleMove(
    List<Task> bundle,
    int currentIter,
    Map<Integer, Integer> baseAssignment,
    Map<Integer, SchedulingPlan> basePlans,
    double baseProfit,
    TaskAllocationCoordinator.HybCecConfig config
  ) throws Exception {
    return bundleMoveEvaluator.tryBundleMove(
      bundle,
      currentIter,
      baseAssignment,
      basePlans,
      baseProfit,
      config
    );
  }

  List<Task> identifySwapCandidates(
    Task unscheduledTask,
    List<ScheduledTask> schedule,
    int targetSatelliteId
  ) {
    return swapMoveEvaluator.identifySwapCandidates(unscheduledTask, schedule, targetSatelliteId);
  }

  List<Integer> findReceiverSatellites(Task swapTask, int sourceSatelliteId) {
    return swapMoveEvaluator.findReceiverSatellites(swapTask, sourceSatelliteId);
  }
}
