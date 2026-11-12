package method.rkhma.legacy.operator.insertion;

import method.rkhma.legacy.model.AttitudeRecord;
import method.rkhma.legacy.model.ScheduledTask;
import method.rkhma.legacy.model.Task;
import method.rkhma.legacy.model.TimeWindow;
import method.rkhma.legacy.utils.TimeUtils;

import java.util.List;

/**
 * Minimum transition time insertion strategy.
 *
 * Selects the feasible position with the smallest transition time, minimizing satellite attitude adjustment time.
 *
 * Dependencies: model.AttitudeRecord, model.ScheduledTask, model.Task, model.TimeWindow, utils.TimeUtils, operator.insertion.AbstractInsertionStrategy
 */
public final class MinTransitionInsertion extends AbstractInsertionStrategy {

  @Override
  public InsertionResult findBestPosition(
    Task task, int satelliteId, List<ScheduledTask> currentSchedule) {

    int taskDuration = task.getImagingDuration();
    List<TimeWindow> windows = task.getTimeWindowsForSatellite(satelliteId);
    if (windows == null || windows.isEmpty()) {
      return null;
    }

    ScheduledTask bestCandidate = null;
    int bestInsertIndex = -1;
    double bestTransitionTime = Double.MAX_VALUE;

    for (TimeWindow window : windows) {
      if (!window.canAccommodateTask(taskDuration)) continue;

      int maxStartIndex = window.getAttitudeRecordCount() - 2 - taskDuration;
      for (int i = 1; i <= maxStartIndex; i++) {
        AttitudeRecord candidateStart = window.getAttitudeRecord(i);
        AttitudeRecord candidateEnd = window.getAttitudeRecord(i + taskDuration);

        double startTime = candidateStart.getTotalSeconds();
        double endTime = candidateEnd.getTotalSeconds();

        int index = findInsertionIndex(currentSchedule, startTime);

        ScheduledTask prevTask = (index > 0) ? currentSchedule.get(index - 1) : null;
        ScheduledTask nextTask = (index < currentSchedule.size()) ? currentSchedule.get(index) : null;

        if (prevTask != null && isOverlapping(prevTask, startTime, endTime)) continue;
        if (nextTask != null && isOverlapping(nextTask, startTime, endTime)) continue;

        boolean transitionFromPrevOK = true;
        boolean transitionToNextOK = true;
        double totalTransitionTime = 0.0;

        if (prevTask != null) {
          transitionFromPrevOK = TimeUtils.checkTransformationTime(
            prevTask.getEndRecord(), candidateStart);
          if (transitionFromPrevOK) {
            totalTransitionTime += Math.abs(
              candidateStart.getTotalSeconds() - prevTask.getEndRecord().getTotalSeconds());
          }
        }
        if (nextTask != null) {
          transitionToNextOK = TimeUtils.checkTransformationTime(
            candidateEnd, nextTask.getStartRecord());
          if (transitionToNextOK) {
            totalTransitionTime += Math.abs(
              nextTask.getStartRecord().getTotalSeconds() - candidateEnd.getTotalSeconds());
          }
        }

        if (transitionFromPrevOK && transitionToNextOK) {
          // Core: select the position with minimum transition time
          if (totalTransitionTime < bestTransitionTime) {
            bestTransitionTime = totalTransitionTime;
            bestCandidate = new ScheduledTask(task);
            bestCandidate.setSchedule(window, candidateStart, candidateEnd);
            bestInsertIndex = index;
          }
        }
      }
    }

    return (bestCandidate != null) ? new InsertionResult(bestCandidate, bestInsertIndex) : null;
  }
}
