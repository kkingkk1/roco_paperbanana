package method.rkhma.legacy.operator.assignment;

import method.rkhma.legacy.model.Satellite;
import method.rkhma.legacy.model.Task;
import method.rkhma.legacy.model.TimeWindow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Minimum average priority strategy.
 * Selects the satellite with the lowest average priority of already assigned tasks.
 * This helps balance the priority distribution across satellites.
 */
public class MinPriorityStrategy extends AbstractAssignmentStrategy {

  @Override
  public Satellite selectSatellite(Task task, List<Satellite> availableSatellites,
    List<Satellite> allSatellites,
    Map<Integer, Integer> satelliteIndexMap) {
    if (availableSatellites.isEmpty()) {
      return null;
    }

    // Pre-calculate average priorities for all satellites
    List<Double> priorityAvgList = new ArrayList<>();
    for (Satellite sat : allSatellites) {
      double avgPriority = calculateAveragePriority(sat);
      priorityAvgList.add(avgPriority);
    }

    List<Satellite> candidateSatellites = new ArrayList<>();
    List<Double> candidatePriorities = new ArrayList<>();

    for (Satellite sat : availableSatellites) {
      Integer satIndex = satelliteIndexMap.get(sat.getId());
      if (satIndex != null) {
        TimeWindow tw = findFirstAvailableTimeWindow(task, satIndex);
        if (tw != null) {
          candidateSatellites.add(sat);
          candidatePriorities.add(priorityAvgList.get(satIndex));
        }
      }
    }

    if (candidateSatellites.isEmpty()) {
      return null;
    }

    int minIndex = findMinIndex(candidatePriorities);
    return minIndex >= 0 ? candidateSatellites.get(minIndex) : null;
  }

  /**
   * Calculate the average priority of tasks assigned to a satellite.
   *
   * @param satellite The satellite
   * @return Average priority, or 0.0 if no tasks assigned
   */
  private double calculateAveragePriority(Satellite satellite) {
    if (!satellite.hasTasks()) {
      return 0.0;
    }

    int prioritySum = 0;
    for (Task task : satellite.getAssignedTasks()) {
      prioritySum += task.getPriority();
    }

    return (double) prioritySum / satellite.getTaskCount();
  }

  @Override
  public String getName() {
    return "MinPriority";
  }
}
