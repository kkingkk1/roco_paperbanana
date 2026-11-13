package method.rkhma.bridge;

import java.util.List;
import method.rkhma.legacy.operator.insertion.InsertionStrategy;

/** 在真实记录间隔上保留按窗口顺序首个可行插入。 */
public final class GridGreedyInsertion implements InsertionStrategy {
  private final ProblemBridge bridge;
  private final method.ProblemAdapter adapter;
  private final Runnable check;

  public GridGreedyInsertion(ProblemBridge bridge) {
    this(bridge, () -> { });
  }

  public GridGreedyInsertion(ProblemBridge bridge, Runnable check) {
    this.bridge = java.util.Objects.requireNonNull(bridge);
    adapter = new method.ProblemAdapter(bridge.problem());
    this.check = java.util.Objects.requireNonNull(check);
  }

  @Override public InsertionResult findBestPosition(method.rkhma.legacy.model.Task task, int satelliteId,
      List<method.rkhma.legacy.model.ScheduledTask> schedule) {
    for (var sourceWindow : task.getTimeWindowsForSatellite(satelliteId)) {
      check.run();
      var window = bridge.originalWindow(sourceWindow);
      if (window == null) throw new IllegalArgumentException("foreign window");
      for (var candidate : adapter.candidates(window)) {
        check.run();
        int index = 0;
        while (index < schedule.size() && schedule.get(index).getStartTime() < candidate.getStartTime()) index++;
        if (index > 0) {
          var previous = bridge.fromLegacy(schedule.get(index - 1));
          if (!utils.TimeUtils.checkTransformationTime(previous.endRecord(), candidate.startRecord())) continue;
        }
        if (index < schedule.size()) {
          var next = bridge.fromLegacy(schedule.get(index));
          if (!utils.TimeUtils.checkTransformationTime(candidate.endRecord(), next.startRecord())) continue;
        }
        return new InsertionResult(bridge.toLegacy(candidate), index);
      }
    }
    return null;
  }
}
