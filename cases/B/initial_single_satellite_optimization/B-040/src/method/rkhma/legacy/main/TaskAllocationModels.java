package method.rkhma.legacy.main;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import method.rkhma.legacy.model.SchedulingPlan;
import method.rkhma.legacy.model.Task;

final class SatelliteState {
  double[] bestSolution;
  List<Task> orderedTasksSnapshot;
  SchedulingPlan bestPlan;

  SatelliteState(double[] bestSolution, List<Task> orderedTasks, SchedulingPlan bestPlan) {
    reset(bestSolution, orderedTasks, bestPlan);
  }

  SatelliteState copy() {
    return new SatelliteState(bestSolution, orderedTasksSnapshot, bestPlan);
  }

  void reset(double[] bestSolution, List<Task> orderedTasks, SchedulingPlan bestPlan) {
    this.bestSolution = java.util.Arrays.copyOf(bestSolution, bestSolution.length);
    this.orderedTasksSnapshot = new ArrayList<>(orderedTasks);
    this.bestPlan = bestPlan != null ? bestPlan.copy() : null;
  }
}

final class SatelliteEvaluationResult {
  final int satelliteId;
  final SchedulingPlan plan;
  final SatelliteState state;
  final List<TaskAllocationCoordinator.ConvergenceTrace> traces;

  SatelliteEvaluationResult(
    int satelliteId,
    SchedulingPlan plan,
    SatelliteState state,
    List<TaskAllocationCoordinator.ConvergenceTrace> traces
  ) {
    this.satelliteId = satelliteId;
    this.plan = plan;
    this.state = state;
    this.traces = traces;
  }
}

final class ReassignmentStats {
  int migrationCount;
  int candidateCountBeforeFilter;
  int candidateCountAfterFilter;
  int prioritizedTaskCount;
  int standardAcceptedMoveCount;
  int replacementAcceptedMoveCount;
  int swapAcceptedMoveCount;
  int bundleAcceptedMoveCount;
  double standardAcceptedGain;
  double replacementAcceptedGain;
  double swapAcceptedGain;
  double bundleAcceptedGain;
  double resultingProfit;
  int resultingUnscheduledCount;
  Map<Integer, SchedulingPlan> resultingPlans;

  void recordStandardMove(double profitGain) {
    standardAcceptedMoveCount++;
    standardAcceptedGain += profitGain;
  }

  void recordReplacementMove(double profitGain) {
    replacementAcceptedMoveCount++;
    replacementAcceptedGain += profitGain;
  }

  void recordSwapMove(double profitGain) {
    swapAcceptedMoveCount++;
    swapAcceptedGain += profitGain;
  }

  void recordBundleMove(double profitGain) {
    bundleAcceptedMoveCount++;
    bundleAcceptedGain += profitGain;
  }
}

final class ReassignmentWorkingState {
  Map<Integer, Integer> candidateAssignment;
  Map<Integer, SchedulingPlan> candidatePlans;
  double candidateProfit;

  ReassignmentWorkingState(
    Map<Integer, Integer> candidateAssignment,
    Map<Integer, SchedulingPlan> candidatePlans,
    double candidateProfit
  ) {
    this.candidateAssignment = candidateAssignment;
    this.candidatePlans = candidatePlans;
    this.candidateProfit = candidateProfit;
  }

  void apply(TrialMoveResult result) {
    this.candidateAssignment = result.materializedAssignment();
    this.candidatePlans = result.plans;
    this.candidateProfit = result.totalProfit;
  }
}

final class PrioritizedTask {
  final Task task;
  final int candidateSatelliteCount;
  final int windowCount;

  PrioritizedTask(Task task, int candidateSatelliteCount, int windowCount) {
    this.task = task;
    this.candidateSatelliteCount = candidateSatelliteCount;
    this.windowCount = windowCount;
  }
}

final class SatelliteCandidateSelection {
  final List<Integer> satelliteIds;
  final int eligibleCount;

  SatelliteCandidateSelection(List<Integer> satelliteIds, int eligibleCount) {
    this.satelliteIds = satelliteIds;
    this.eligibleCount = eligibleCount;
  }
}

final class SchedulingStats {
  final Set<Integer> scheduledTaskIds;
  final Set<Task> unscheduledTasks;
  final int scheduledCount;
  final int unscheduledCount;

  SchedulingStats(Set<Integer> scheduledTaskIds, Set<Task> unscheduledTasks) {
    this.scheduledTaskIds = scheduledTaskIds;
    this.unscheduledTasks = unscheduledTasks;
    this.scheduledCount = scheduledTaskIds.size();
    this.unscheduledCount = unscheduledTasks.size();
  }
}

final class TaskDelta {
  final List<Task> retainedTasks;
  final List<Task> addedTasks;
  final List<Task> removedTasks;

  TaskDelta(List<Task> retainedTasks, List<Task> addedTasks, List<Task> removedTasks) {
    this.retainedTasks = retainedTasks;
    this.addedTasks = addedTasks;
    this.removedTasks = removedTasks;
  }
}

final class TrialMoveResult {
  final Map<Integer, Integer> assignment;
  final Map<Integer, SchedulingPlan> plans;
  final double totalProfit;
  final double profitGain;
  final List<Task> displacedTasks;

  TrialMoveResult(
    Map<Integer, Integer> assignment,
    Map<Integer, SchedulingPlan> plans,
    double totalProfit,
    double profitGain
  ) {
    this(assignment, plans, totalProfit, profitGain, Collections.emptyList());
  }

  TrialMoveResult(
    Map<Integer, Integer> assignment,
    Map<Integer, SchedulingPlan> plans,
    double totalProfit,
    double profitGain,
    List<Task> displacedTasks
  ) {
    this.assignment = assignment;
    this.plans = plans;
    this.totalProfit = totalProfit;
    this.profitGain = profitGain;
    this.displacedTasks = displacedTasks;
  }

  Map<Integer, Integer> materializedAssignment() {
    if (assignment instanceof AssignmentOverlayMap overlay) {
      return overlay.materialize();
    }
    return assignment;
  }
}
