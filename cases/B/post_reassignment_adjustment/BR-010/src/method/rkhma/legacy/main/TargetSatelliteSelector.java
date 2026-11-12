package method.rkhma.legacy.main;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import method.rkhma.legacy.model.Satellite;
import method.rkhma.legacy.model.Task;
import method.rkhma.legacy.model.TimeWindow;

final class TargetSatelliteSelector {
  interface Context {
    double getTaskSatelliteKeyOrDefault(int taskId, int satelliteId);
    int countCandidateSatellites(Task task);
    int countTotalWindows(Task task);
    int getMaxTaskPriority();
    int getKeyPerturbThreshold();
    Map<Integer, Integer> getFailureCount();
    TaskSatelliteKnnCache.Feature getTaskSatelliteFeature(Task task, int satelliteId);
  }

  private final List<Satellite> satellites;
  private final TargetSatelliteRanker ranker;
  private final Context context;

  TargetSatelliteSelector(List<Satellite> satellites, TargetSatelliteRanker ranker, Context context) {
    this.satellites = satellites;
    this.ranker = ranker;
    this.context = context;
  }

  SatelliteCandidateSelection selectTopTargetSatellites(
    Task task,
    Integer currentSatelliteId,
    int currentIter,
    Map<Integer, Integer> assignmentSnapshot,
    boolean prioritizedTask
  ) {
    List<TargetSatelliteRanker.SatelliteCandidateContext> candidateContexts = new ArrayList<>();
    int eligibleCount = 0;

    for (Satellite satellite : satellites) {
      int satelliteId = satellite.getId();
      if (currentSatelliteId != null && satelliteId == currentSatelliteId) {
        continue;
      }
      TaskSatelliteKnnCache.Feature cachedFeature = context.getTaskSatelliteFeature(task, satelliteId);
      int windowCount;
      double totalWindowLength;
      double earliestStart;
      if (cachedFeature != null) {
        windowCount = cachedFeature.windowCount;
        totalWindowLength = cachedFeature.totalWindowLength;
        earliestStart = cachedFeature.earliestStart;
      } else {
        List<TimeWindow> windows = task.getTimeWindowsForSatellite(satelliteId);
        if (windows == null || windows.isEmpty()) {
          continue;
        }
        windowCount = windows.size();
        totalWindowLength = 0.0;
        earliestStart = Double.POSITIVE_INFINITY;
        for (TimeWindow window : windows) {
          if (window == null) {
            continue;
          }
          totalWindowLength += Math.max(0.0, window.getEndTime() - window.getStartTime());
          earliestStart = Math.min(earliestStart, window.getStartTime());
        }
      }
      if (windowCount <= 0) {
        continue;
      }
      eligibleCount++;
      candidateContexts.add(new TargetSatelliteRanker.SatelliteCandidateContext(
        satelliteId,
        windowCount,
        totalWindowLength,
        earliestStart,
        context.getTaskSatelliteKeyOrDefault(task.getId(), satelliteId)
      ));
    }

    if (candidateContexts.isEmpty()) {
      return new SatelliteCandidateSelection(Collections.emptyList(), eligibleCount);
    }

    TargetSatelliteRanker.RankRequest request = new TargetSatelliteRanker.RankRequest(
      candidateContexts,
      prioritizedTask,
      TaskAllocationScoring.normalize(task.getPriority(), context.getMaxTaskPriority()),
      TaskAllocationScoring.computeTaskTightnessScore(
        context.countCandidateSatellites(task),
        context.countTotalWindows(task)
      ),
      TaskAllocationScoring.computeTaskUnscheduledPressure(
        task,
        context.getFailureCount(),
        context.getKeyPerturbThreshold()
      )
    );
    return new SatelliteCandidateSelection(ranker.rank(request), eligibleCount);
  }
}
