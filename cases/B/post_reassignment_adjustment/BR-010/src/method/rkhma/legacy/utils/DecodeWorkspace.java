package method.rkhma.legacy.utils;

import java.util.ArrayList;
import java.util.List;
import method.rkhma.legacy.model.AttitudeRecord;
import method.rkhma.legacy.model.ScheduledTask;
import method.rkhma.legacy.model.Task;
import method.rkhma.legacy.model.TimeWindow;
import method.rkhma.legacy.operator.insertion.GreedyInsertion;

/**
 * Per-thread scratch buffers for random-key decoding and direct evaluation.
 *
 * <p>The workspace is mutable and not thread-safe. Callers should keep one
 * instance per thread or per serial evaluation loop.
 */
public final class DecodeWorkspace {

  int[] order = new int[0];
  int[] scratch = new int[0];
  Task[] sequence = new Task[0];
  final List<ScheduledTask> schedule = new ArrayList<>();
  private final List<ScheduledTask> scheduledTaskPool = new ArrayList<>();
  private int scheduledTaskPoolIndex;
  final PlanUtils.TaskIdMarker scheduledTaskIds = new PlanUtils.TaskIdMarker();
  final GreedyInsertion.ReusableInsertionResult greedyInsertionResult =
    new GreedyInsertion.ReusableInsertionResult();

  public DecodeWorkspace() {
    greedyInsertionResult.setTaskProvider(this::borrowScheduledTask);
  }

  void ensureCapacity(int size) {
    if (order.length >= size) {
      return;
    }
    order = new int[size];
    scratch = new int[size];
    sequence = new Task[size];
  }

  void clearScheduleState() {
    schedule.clear();
    scheduledTaskPoolIndex = 0;
    scheduledTaskIds.clear();
  }

  private ScheduledTask borrowScheduledTask(
    Task task,
    TimeWindow window,
    AttitudeRecord startRecord,
    AttitudeRecord endRecord
  ) {
    ScheduledTask scheduledTask;
    if (scheduledTaskPoolIndex < scheduledTaskPool.size()) {
      scheduledTask = scheduledTaskPool.get(scheduledTaskPoolIndex);
      scheduledTask.resetSchedule(task, window, startRecord, endRecord);
    } else {
      scheduledTask = new ScheduledTask(task);
      scheduledTask.setSchedule(window, startRecord, endRecord);
      scheduledTaskPool.add(scheduledTask);
    }
    scheduledTaskPoolIndex++;
    return scheduledTask;
  }
}
