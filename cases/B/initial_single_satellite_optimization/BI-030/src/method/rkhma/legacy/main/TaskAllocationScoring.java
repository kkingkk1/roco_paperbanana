package method.rkhma.legacy.main;

import java.util.List;
import java.util.Map;

import method.rkhma.legacy.common.TuningConfig;
import method.rkhma.legacy.common.TuningDefaults;
import method.rkhma.legacy.model.Task;
import method.rkhma.legacy.model.TimeWindow;

final class TaskAllocationScoring {
  private static final double PRIORITIZED_PRIORITY_WEIGHT = TuningConfig.getDouble("coord.prioritizedPriorityWeight", TuningDefaults.COORD_PRIORITIZED_PRIORITY_WEIGHT);
  private static final double PRIORITIZED_CANDIDATE_WEIGHT = TuningConfig.getDouble("coord.prioritizedCandidateWeight", TuningDefaults.COORD_PRIORITIZED_CANDIDATE_WEIGHT);
  private static final double PRIORITIZED_WINDOW_WEIGHT = TuningConfig.getDouble("coord.prioritizedWindowWeight", TuningDefaults.COORD_PRIORITIZED_WINDOW_WEIGHT);
  private static final double PRIORITIZED_TASK_KEY_WEIGHT = TuningConfig.getDouble("coord.prioritizedTaskKeyWeight", TuningDefaults.COORD_PRIORITIZED_TASK_KEY_WEIGHT);
  private static final double FAST_REFINE_TASK_KEY_WEIGHT = TuningConfig.getDouble("coord.fastRefineTaskKeyWeight", TuningDefaults.COORD_FAST_REFINE_TASK_KEY_WEIGHT);
  private static final double FAST_REFINE_SAT_KEY_WEIGHT = TuningConfig.getDouble("coord.fastRefineSatKeyWeight", TuningDefaults.COORD_FAST_REFINE_SAT_KEY_WEIGHT);
  private static final double FAST_REFINE_PRIORITY_WEIGHT = TuningConfig.getDouble("coord.fastRefinePriorityWeight", TuningDefaults.COORD_FAST_REFINE_PRIORITY_WEIGHT);

  private TaskAllocationScoring() {
  }

  static double computePrioritizedTaskScore(
    PrioritizedTask prioritizedTask,
    int maxPriority,
    int maxCandidateCount,
    int maxWindowCount,
    Map<Integer, Double> globalTaskKeys
  ) {
    double priorityScore = normalize(prioritizedTask.task.getPriority(), maxPriority);
    double candidateScore = normalize(prioritizedTask.candidateSatelliteCount, maxCandidateCount);
    double windowScore = normalize(prioritizedTask.windowCount, maxWindowCount);
    double taskKeyScore = globalTaskKeys.getOrDefault(prioritizedTask.task.getId(), 0.0);
    return PRIORITIZED_PRIORITY_WEIGHT * priorityScore
      + PRIORITIZED_CANDIDATE_WEIGHT * candidateScore
      + PRIORITIZED_WINDOW_WEIGHT * windowScore
      + PRIORITIZED_TASK_KEY_WEIGHT * taskKeyScore;
  }

  static double computeTaskTightnessScore(int candidateSatellites, int windowCount) {
    int safeCandidateSatellites = Math.max(1, candidateSatellites);
    int safeWindowCount = Math.max(1, windowCount);
    return clamp01(1.0 / safeCandidateSatellites * 0.55 + 1.0 / safeWindowCount * 0.45);
  }

  static double computeTaskUnscheduledPressure(Task task, Map<Integer, Integer> failureCount, int keyPerturbThreshold) {
    if (task == null) {
      return 0.0;
    }
    int failures = failureCount.getOrDefault(task.getId(), 0);
    return clamp01(failures / (double) Math.max(1, keyPerturbThreshold));
  }

  static double computeFastRefineRepairScore(
    Task task,
    int satelliteId,
    int maxTaskPriority,
    Map<Integer, Double> globalTaskKeys,
    Map<Integer, Map<Integer, Double>> taskSatelliteBaseScores,
    Map<Integer, Map<Integer, Double>> taskSatelliteFeedbackScores
  ) {
    if (task == null) {
      return Double.NEGATIVE_INFINITY;
    }
    double priorityScore = normalize(task.getPriority(), maxTaskPriority);
    return FAST_REFINE_TASK_KEY_WEIGHT * globalTaskKeys.getOrDefault(task.getId(), 0.0)
      + FAST_REFINE_SAT_KEY_WEIGHT * getEffectiveTaskSatelliteScoreOrDefault(
        taskSatelliteBaseScores,
        taskSatelliteFeedbackScores,
        task.getId(),
        satelliteId
      )
      + FAST_REFINE_PRIORITY_WEIGHT * priorityScore;
  }

  static double computeConflictOverlapScore(Task newTask, List<Task> baseSequence, int satelliteId) {
    List<TimeWindow> newWindows = newTask.getTimeWindowsForSatellite(satelliteId);
    if (newWindows.isEmpty() || baseSequence.isEmpty()) {
      return 0.0;
    }
    int overlapCount = 0;
    int totalBaseWindows = 0;
    for (Task baseTask : baseSequence) {
      List<TimeWindow> baseWindows = baseTask.getTimeWindowsForSatellite(satelliteId);
      totalBaseWindows += baseWindows.size();
      for (TimeWindow nw : newWindows) {
        for (TimeWindow bw : baseWindows) {
          if (nw.overlaps(bw)) {
            overlapCount++;
          }
        }
      }
    }
    int totalPairs = newWindows.size() * totalBaseWindows;
    if (totalPairs == 0) {
      return 0.0;
    }
    return (double) overlapCount / totalPairs;
  }

  static double getEffectiveTaskSatelliteScoreOrDefault(
    Map<Integer, Map<Integer, Double>> taskSatelliteBaseScores,
    Map<Integer, Map<Integer, Double>> taskSatelliteFeedbackScores,
    int taskId,
    int satelliteId
  ) {
    return clip01(
      getNestedScoreOrDefault(taskSatelliteBaseScores, taskId, satelliteId)
        + getNestedScoreOrDefault(taskSatelliteFeedbackScores, taskId, satelliteId)
    );
  }

  private static double getNestedScoreOrDefault(
    Map<Integer, Map<Integer, Double>> scores,
    int taskId,
    int satelliteId
  ) {
    Map<Integer, Double> satelliteScores = scores.get(taskId);
    if (satelliteScores == null) {
      return 0.0;
    }
    return satelliteScores.getOrDefault(satelliteId, 0.0);
  }

  static double normalize(int value, int maxValue) {
    if (maxValue <= 0) {
      return 0.0;
    }
    return Math.max(0.0, Math.min(1.0, value / (double) maxValue));
  }

  static double clip01(double value) {
    return Math.max(0.0, Math.min(1.0, value));
  }

  static double clamp01(double value) {
    if (Double.isNaN(value)) {
      return 0.0;
    }
    return clip01(value);
  }
}
