package method.rkhma.legacy.operator.assignment;

import method.rkhma.legacy.model.Satellite;
import method.rkhma.legacy.model.Task;
import method.rkhma.legacy.model.TimeWindow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Minimum roll angle strategy.
 * Selects the satellite with the smallest absolute roll angle.
 */
public class MinRollAngleStrategy extends AbstractAssignmentStrategy {

  @Override
  public Satellite selectSatellite(Task task, List<Satellite> availableSatellites,
    List<Satellite> allSatellites,
    Map<Integer, Integer> satelliteIndexMap) {
    if (availableSatellites.isEmpty()) {
      return null;
    }

    List<Satellite> candidateSatellites = new ArrayList<>();
    List<Double> rollAngles = new ArrayList<>();

    for (Satellite sat : availableSatellites) {
      Integer satIndex = satelliteIndexMap.get(sat.getId());
      if (satIndex != null) {
        for (TimeWindow tw : task.getTimeWindowsForSatellite(satIndex)) {
          if (tw.hasAttitudeRecords()) {
            candidateSatellites.add(sat);
            rollAngles.add(Math.abs(tw.getAttitudeRecord(0).getRollAngle()));
            break;
          }
        }
      }
    }

    if (candidateSatellites.isEmpty()) {
      return null;
    }

    int minIndex = findMinIndex(rollAngles);
    return minIndex >= 0 ? candidateSatellites.get(minIndex) : null;
  }

  @Override
  public String getName() {
    return "MinRollAngle";
  }
}
