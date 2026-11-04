package problem;

import model.AttitudeRecord;
import model.ProblemInstance;
import model.Satellite;
import model.ScheduledTask;
import model.SchedulingPlan;
import model.Task;
import model.TimeWindow;
import utils.TimeUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 只检查显式候选解；不插入、删改、修复或生成观测。 */
public final class ScheduleValidator {
  private static final double DURATION_EPSILON = 1e-6;

  private ScheduleValidator() {
  }

  /** totalProfit 仅在 feasible=true 时有效；非法方案返回 0 和具体违规项。 */
  public record Result(boolean feasible, long totalProfit, List<String> violations) {
    public Result {
      violations = List.copyOf(violations);
    }
  }

  public static Result evaluate(ProblemInstance instance, SchedulingPlan plan) {
    List<String> violations = new ArrayList<>();
    Set<Integer> seenTasks = new HashSet<>();
    Map<Integer, List<ScheduledTask>> perSatellite = new HashMap<>();
    long totalProfit = 0;
    for (ScheduledTask observation : plan.observations()) {
      int taskId = observation.getTaskId();
      String label = "task=" + taskId + ", satellite=" + observation.getSatelliteId() + ": ";
      Task task = instance.task(taskId);
      Satellite satellite = instance.satellite(observation.getSatelliteId());
      TimeWindow window = observation.timeWindow();
      if (!seenTasks.add(taskId)) {
        violations.add(label + "DUPLICATE_TASK");
      }
      if (task == null || task != observation.task()) {
        violations.add(label + "TASK_NOT_IN_INSTANCE");
        continue;
      }
      if (satellite == null || window.getTaskId() != taskId
          || satellite.getTimeWindows().stream().noneMatch(candidate -> candidate == window)) {
        violations.add(label + "WINDOW_NOT_IN_INSTANCE");
        continue;
      }
      if (!containsRecord(window, observation.startRecord())
          || !containsRecord(window, observation.endRecord())) {
        violations.add(label + "ATTITUDE_NOT_IN_WINDOW");
        continue;
      }
      double start = observation.getStartTime();
      double end = observation.getEndTime();
      if (!Double.isFinite(start) || !Double.isFinite(end) || end <= start
          || Math.abs(end - start - task.getImagingDuration()) > DURATION_EPSILON) {
        violations.add(label + "IMAGING_DURATION");
      }
      if (start < window.getStartTime() || end > window.getEndTime()) {
        violations.add(label + "OUTSIDE_WINDOW");
      }
      perSatellite.computeIfAbsent(satellite.getId(), key -> new ArrayList<>()).add(observation);
      totalProfit += task.getPriority();
    }
    for (List<ScheduledTask> observations : perSatellite.values()) {
      // 排序副本用于识别物理上的相邻观测，不改变调用方列表或任务选择。
      observations.sort(Comparator.comparingDouble(ScheduledTask::getStartTime));
      for (int i = 1; i < observations.size(); i++) {
        ScheduledTask previous = observations.get(i - 1);
        ScheduledTask current = observations.get(i);
        if (current.getStartTime() < previous.getEndTime()) {
          violations.add("satellite=" + current.getSatelliteId() + ": OVERLAP");
        } else if (!TimeUtils.checkTransformationTime(previous.endRecord(), current.startRecord())) {
          violations.add("satellite=" + current.getSatelliteId() + ": ATTITUDE_TRANSITION");
        }
      }
    }
    boolean feasible = violations.isEmpty();
    return new Result(feasible, feasible ? totalProfit : 0, violations);
  }

  private static boolean containsRecord(TimeWindow window, AttitudeRecord record) {
    return window.getAttitudeRecords().stream().anyMatch(candidate -> candidate == record);
  }
}
