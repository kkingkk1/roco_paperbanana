package model;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 问题实例与 ID 索引。加载后不得修改其任务、窗口和姿态输入。 */
public final class ProblemInstance {
  private final List<Task> tasks;
  private final List<Satellite> satellites;
  private final Map<Integer, Task> tasksById = new HashMap<>();
  private final Map<Integer, Satellite> satellitesById = new HashMap<>();

  public ProblemInstance(List<Task> tasks, List<Satellite> satellites) {
    this.tasks = List.copyOf(tasks);
    this.satellites = List.copyOf(satellites);
    for (Task task : tasks) {
      if (task.getId() < 0 || task.getImagingDuration() <= 0 || task.getPriority() < 0
          || !Double.isFinite(task.getLongitude()) || !Double.isFinite(task.getLatitude())
          || tasksById.putIfAbsent(task.getId(), task) != null) {
        throw new IllegalArgumentException("Invalid or duplicate task: " + task.getId());
      }
    }
    for (Satellite satellite : satellites) {
      if (satellite.getId() < 0 || satellitesById.putIfAbsent(satellite.getId(), satellite) != null) {
        throw new IllegalArgumentException("Invalid or duplicate satellite: " + satellite.getId());
      }
      for (TimeWindow window : satellite.getTimeWindows()) {
        if (window.getSatelliteId() != satellite.getId() || !tasksById.containsKey(window.getTaskId())
            || !Double.isFinite(window.getStartTime()) || !Double.isFinite(window.getEndTime())
            || window.getStartTime() < 0 || window.getEndTime() < 0) {
          throw new IllegalArgumentException("Invalid window: " + window);
        }
        double previous = Double.NEGATIVE_INFINITY;
        for (AttitudeRecord record : window.getAttitudeRecords()) {
          if (!Double.isFinite(record.getTotalSeconds()) || !Double.isFinite(record.getRollAngle())
              || !Double.isFinite(record.getPitchAngle()) || record.getTotalSeconds() <= previous) {
            throw new IllegalArgumentException("Invalid attitude records: " + window);
          }
          previous = record.getTotalSeconds();
        }
      }
    }
  }

  public List<Task> tasks() {
    return tasks;
  }

  public List<Satellite> satellites() {
    return satellites;
  }

  public Task task(int id) {
    return tasksById.get(id);
  }

  public Satellite satellite(int id) {
    return satellitesById.get(id);
  }
}
