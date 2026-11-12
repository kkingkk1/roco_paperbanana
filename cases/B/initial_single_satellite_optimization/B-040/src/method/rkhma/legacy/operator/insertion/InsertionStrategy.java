package method.rkhma.legacy.operator.insertion;

import method.rkhma.legacy.model.ScheduledTask;
import method.rkhma.legacy.model.Task;

import java.util.List;

/**
 * Insertion strategy interface.
 *
 * Defines the unified interface for task insertion scheduling; all insertion strategies must implement this.
 *
 * Dependencies: model.ScheduledTask, model.Task
 */
public interface InsertionStrategy {

  /**
   * Insertion result: contains the ScheduledTask to insert and the insertion index
   */
  class InsertionResult {
    private final ScheduledTask task;
    private final int insertIndex;

    public InsertionResult(ScheduledTask task, int insertIndex) {
      this.task = task;
      this.insertIndex = insertIndex;
    }

    public ScheduledTask getTask() {
      return task;
    }

    public int getInsertIndex() {
      return insertIndex;
    }
  }

  /**
   * Find the best insertion position for the given task in the current schedule.
   *
   * @param task task to insert
   * @param satelliteId satellite ID
   * @param currentSchedule current schedule (sorted by start time ascending)
   * @return insertion result, or null if no feasible position exists
   */
  InsertionResult findBestPosition(Task task, int satelliteId, List<ScheduledTask> currentSchedule);
}
