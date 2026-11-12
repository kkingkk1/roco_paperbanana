package method.rkhma.legacy.operator.assignment;

import method.rkhma.legacy.model.Satellite;
import method.rkhma.legacy.model.Task;
import method.rkhma.legacy.model.TimeWindow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Earliest time strategy.
 * Selects the satellite with the earliest available time window start time.
 */
public class EarliestTimeStrategy extends AbstractAssignmentStrategy {

  @Override
  public Satellite selectSatellite(Task task, List<Satellite> availableSatellites,
    List<Satellite> allSatellites,
    Map<Integer, Integer> satelliteIndexMap) {
    if (availableSatellites.isEmpty()) {
      return null;
    }

    List<Satellite> candidateSatellites = new ArrayList<>();
    List<Double> startTimes = new ArrayList<>();

    for (Satellite sat : availableSatellites) {
      Integer satIndex = satelliteIndexMap.get(sat.getId());
      if (satIndex != null) {
        TimeWindow tw = findFirstAvailableTimeWindow(task, satIndex);
        if (tw != null) {
          candidateSatellites.add(sat);
          startTimes.add(tw.getStartTime());
        }
      }
    }

    if (candidateSatellites.isEmpty()) {
      return null;
    }

    int minIndex = findMinIndex(startTimes);
    return minIndex >= 0 ? candidateSatellites.get(minIndex) : null;
  }

  @Override
  public String getName() {
    return "EarliestTime";
  }
}
