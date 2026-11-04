package model;

import java.util.Objects;

/** 调用方明确给出的观测决策；引用当前问题实例的任务、窗口和姿态记录。 */
public record ScheduledTask(
    Task task, TimeWindow timeWindow, AttitudeRecord startRecord, AttitudeRecord endRecord) {
  public ScheduledTask {
    Objects.requireNonNull(task, "task");
    Objects.requireNonNull(timeWindow, "timeWindow");
    Objects.requireNonNull(startRecord, "startRecord");
    Objects.requireNonNull(endRecord, "endRecord");
  }

  public int getTaskId() {
    return task.getId();
  }

  public int getSatelliteId() {
    return timeWindow.getSatelliteId();
  }

  public double getStartTime() {
    return startRecord.getTotalSeconds();
  }

  public double getEndTime() {
    return endRecord.getTotalSeconds();
  }
}
