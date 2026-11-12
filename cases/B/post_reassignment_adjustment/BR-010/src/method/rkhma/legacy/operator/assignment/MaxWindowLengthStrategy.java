package method.rkhma.legacy.operator.assignment;

import method.rkhma.legacy.model.Satellite;
import method.rkhma.legacy.model.Task;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Maximum window length strategy.
 * Selects the satellite with the longest total usable window length.
 * The usable length is calculated as the sum of (attitudeRecordCount - 2) across all valid windows.
 */
public class MaxWindowLengthStrategy extends AbstractAssignmentStrategy {

  @Override
  public Satellite selectSatellite(Task task, List<Satellite> availableSatellites,
    List<Satellite> allSatellites,
    Map<Integer, Integer> satelliteIndexMap) {
    if (availableSatellites.isEmpty()) {
      return null;
    }

    List<Satellite> candidateSatellites = new ArrayList<>();
    List<Integer> windowLengths = new ArrayList<>();

    for (Satellite sat : availableSatellites) {
      Integer satIndex = satelliteIndexMap.get(sat.getId());
      if (satIndex != null) {
        int totalLength = calculateUsableWindowLength(task, satIndex);
        if (totalLength > 0) {
          candidateSatellites.add(sat);
          windowLengths.add(totalLength);
        }
      }
    }

    if (candidateSatellites.isEmpty()) {
      return null;
    }

    int maxIndex = findMaxIndex(windowLengths);
    return maxIndex >= 0 ? candidateSatellites.get(maxIndex) : null;
  }

  @Override
  public String getName() {
    return "MaxWindowLength";
  }
}
