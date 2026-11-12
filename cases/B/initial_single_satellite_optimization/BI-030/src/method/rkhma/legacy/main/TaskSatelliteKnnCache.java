package method.rkhma.legacy.main;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import method.rkhma.legacy.model.Satellite;
import method.rkhma.legacy.model.Task;
import method.rkhma.legacy.model.TimeWindow;

final class TaskSatelliteKnnCache {
  private final Map<Long, Feature> featuresByTaskSat = new HashMap<>();

  TaskSatelliteKnnCache(List<Task> tasks, List<Satellite> satellites) {
    if (tasks == null || satellites == null) {
      return;
    }
    for (Task task : tasks) {
      if (task == null) {
        continue;
      }
      for (Satellite satellite : satellites) {
        if (satellite == null) {
          continue;
        }
        int satelliteId = satellite.getId();
        List<TimeWindow> windows = task.getTimeWindowsForSatellite(satelliteId);
        featuresByTaskSat.put(key(task.getId(), satelliteId), Feature.from(windows));
      }
    }
  }

  Feature get(int taskId, int satelliteId) {
    return featuresByTaskSat.get(key(taskId, satelliteId));
  }

  private static long key(int taskId, int satelliteId) {
    return (((long) taskId) << 32) ^ (satelliteId & 0xffffffffL);
  }

  static final class Feature {
    final int windowCount;
    final double totalWindowLength;
    final double earliestStart;

    private Feature(int windowCount, double totalWindowLength, double earliestStart) {
      this.windowCount = windowCount;
      this.totalWindowLength = totalWindowLength;
      this.earliestStart = earliestStart;
    }

    static Feature from(List<TimeWindow> windows) {
      if (windows == null || windows.isEmpty()) {
        return new Feature(0, 0.0, Double.POSITIVE_INFINITY);
      }
      int count = windows.size();
      double totalLength = 0.0;
      double earliest = Double.POSITIVE_INFINITY;
      for (TimeWindow window : windows) {
        if (window == null) {
          continue;
        }
        totalLength += Math.max(0.0, window.getEndTime() - window.getStartTime());
        earliest = Math.min(earliest, window.getStartTime());
      }
      return new Feature(count, totalLength, earliest);
    }
  }
}
