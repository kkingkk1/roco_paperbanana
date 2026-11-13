package method.rkhma.legacy.operator.assignment;

import method.rkhma.legacy.model.Satellite;
import method.rkhma.legacy.model.Task;
import method.rkhma.legacy.model.TimeWindow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Minimum task count strategy.
 * Selects the satellite with the fewest assigned tasks.
 */
public class MinTaskCountStrategy extends AbstractAssignmentStrategy {

  @Override
  public Satellite selectSatellite(Task task, List<Satellite> availableSatellites,
    List<Satellite> allSatellites,
    Map<Integer, Integer> satelliteIndexMap) {
    if (availableSatellites.isEmpty()) {
      return null;
    }

    List<Satellite> candidateSatellites = new ArrayList<>();
    List<Integer> taskCounts = new ArrayList<>();

    for (Satellite sat : availableSatellites) {
      Integer satIndex = satelliteIndexMap.get(sat.getId());
      if (satIndex != null) {
        TimeWindow tw = findFirstAvailableTimeWindow(task, satIndex);
        if (tw != null) {
          candidateSatellites.add(sat);
          taskCounts.add(sat.getTaskCount());
        }
      }
    }

    if (candidateSatellites.isEmpty()) {
      return null;
    }

    int minIndex = findMinIndex(taskCounts);
    return minIndex >= 0 ? candidateSatellites.get(minIndex) : null;
  }

  @Override
  public String getName() {
    return "MinTaskCount";
  }
}
