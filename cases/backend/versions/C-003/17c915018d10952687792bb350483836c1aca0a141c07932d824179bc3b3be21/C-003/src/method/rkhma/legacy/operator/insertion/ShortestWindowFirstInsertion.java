package method.rkhma.legacy.operator.insertion;

import method.rkhma.legacy.model.AttitudeRecord;
import method.rkhma.legacy.model.ScheduledTask;
import method.rkhma.legacy.model.Task;
import method.rkhma.legacy.model.TimeWindow;
import method.rkhma.legacy.utils.TimeUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Shortest window first insertion strategy.
 *
 * Prioritizes windows with the shortest time span, handling the most constrained tasks first.
 *
 * Dependencies: model.AttitudeRecord, model.ScheduledTask, model.Task, model.TimeWindow, utils.TimeUtils, operator.insertion.AbstractInsertionStrategy
 */
public final class ShortestWindowFirstInsertion extends AbstractInsertionStrategy {

  @Override
  public InsertionResult findBestPosition(
    Task task, int satelliteId, List<ScheduledTask> currentSchedule) {

    int taskDuration = task.getImagingDuration();
    List<TimeWindow> windows = task.getTimeWindowsForSatellite(satelliteId);
    if (windows == null || windows.isEmpty()) {
      return null;
    }

    // Sort windows by duration ascending
    List<TimeWindow> sortedWindows = new ArrayList<>(windows);
    sortedWindows.sort((w1, w2) -> {
        double duration1 = w1.getEndTime() - w1.getStartTime();
        double duration2 = w2.getEndTime() - w2.getStartTime();
        return Double.compare(duration1, duration2);
      });

    for (TimeWindow window : sortedWindows) {
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

        if (prevTask != null) {
          transitionFromPrevOK = TimeUtils.checkTransformationTime(
            prevTask.getEndRecord(), candidateStart);
        }
        if (nextTask != null) {
          transitionToNextOK = TimeUtils.checkTransformationTime(
            candidateEnd, nextTask.getStartRecord());
        }

        if (transitionFromPrevOK && transitionToNextOK) {
          ScheduledTask newTask = new ScheduledTask(task);
          newTask.setSchedule(window, candidateStart, candidateEnd);
          return new InsertionResult(newTask, index);
        }
      }
    }

    return null;
  }
}
