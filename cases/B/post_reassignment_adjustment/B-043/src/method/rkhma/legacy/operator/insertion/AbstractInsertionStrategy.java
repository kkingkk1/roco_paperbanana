package method.rkhma.legacy.operator.insertion;

import method.rkhma.legacy.model.ScheduledTask;

import java.util.List;

/**
 * Abstract base class for insertion strategies.
 *
 * Provides shared utility methods for all insertion strategies to avoid code duplication.
 *
 * Dependencies: model.ScheduledTask
 */
public abstract class AbstractInsertionStrategy implements InsertionStrategy {

  /**
   * Find insertion index using binary search.
   *
   * @param schedule current schedule (sorted by start time ascending)
   * @param targetTime target start time
   * @return insertion index
   */
  protected int findInsertionIndex(List<ScheduledTask> schedule, double targetTime) {
    int low = 0;
    int high = schedule.size() - 1;

    while (low <= high) {
      int mid = (low + high) >>> 1;
      double midTime = schedule.get(mid).getStartRecord().getTotalSeconds();

      if (midTime < targetTime) {
        low = mid + 1;
      } else {
        high = mid - 1;
      }
    }
    return low;
  }

  /**
   * Check whether two tasks overlap in time.
   *
   * @param existing existing scheduled task
   * @param startTime new task start time
   * @param endTime new task end time
   * @return true if overlapping
   */
  protected boolean isOverlapping(ScheduledTask existing, double startTime, double endTime) {
    double existingStart = existing.getStartRecord().getTotalSeconds();
    double existingEnd = existing.getEndRecord().getTotalSeconds();
    return Math.max(startTime, existingStart) < Math.min(endTime, existingEnd);
  }
}
