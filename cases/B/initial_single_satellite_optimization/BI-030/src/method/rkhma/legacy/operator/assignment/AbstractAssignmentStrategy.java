package method.rkhma.legacy.operator.assignment;

import method.rkhma.legacy.model.Satellite;
import method.rkhma.legacy.model.Task;
import method.rkhma.legacy.model.TimeWindow;

import java.util.ArrayList;
import java.util.List;

/**
 * Abstract base class for assignment strategies.
 * Provides common utility methods for all strategies.
 */
public abstract class AbstractAssignmentStrategy implements AssignmentStrategy {

  /**
   * Find the first available time window that satisfies the task's imaging duration.
   *
   * @param task The task to check
   * @param satelliteIndex The satellite index
   * @return The first available time window, or null if none found
   */
  protected TimeWindow findFirstAvailableTimeWindow(Task task, int satelliteIndex) {
    for (TimeWindow tw : task.getTimeWindowsForSatellite(satelliteIndex)) {
      if (tw.canAccommodateTask(task.getImagingDuration())) {
        return tw;
      }
    }
    return null;
  }

  /**
   * Find the index of the minimum value in a list.
   *
   * @param values List of comparable values
   * @param <T> Type extending Comparable
   * @return The index of minimum value, or -1 if list is empty
   */
  protected <T extends Comparable<T>> int findMinIndex(List<T> values) {
    if (values == null || values.isEmpty()) {
      return -1;
    }
    int minIndex = 0;
    T minValue = values.get(0);
    for (int i = 1; i < values.size(); i++) {
      if (values.get(i).compareTo(minValue) < 0) {
        minValue = values.get(i);
        minIndex = i;
      }
    }
    return minIndex;
  }

  /**
   * Find the index of the maximum value in a list.
   *
   * @param values List of comparable values
   * @param <T> Type extending Comparable
   * @return The index of maximum value, or -1 if list is empty
   */
  protected <T extends Comparable<T>> int findMaxIndex(List<T> values) {
    if (values == null || values.isEmpty()) {
      return -1;
    }
    int maxIndex = 0;
    T maxValue = values.get(0);
    for (int i = 1; i < values.size(); i++) {
      if (values.get(i).compareTo(maxValue) > 0) {
        maxValue = values.get(i);
        maxIndex = i;
      }
    }
    return maxIndex;
  }

  /**
   * Build a list of satellites that can accommodate the task.
   *
   * @param task The task to check
   * @param allSatellites List of all satellites
   * @return List of satellites that can accommodate the task
   */
  protected List<Satellite> findAvailableSatellites(Task task, List<Satellite> allSatellites) {
    List<Satellite> availableSatellites = new ArrayList<>();
    for (int j = 0; j < allSatellites.size(); j++) {
      for (TimeWindow tw : task.getTimeWindowsForSatellite(j)) {
        if (tw.canAccommodateTask(task.getImagingDuration())) {
          availableSatellites.add(allSatellites.get(j));
          break;
        }
      }
    }
    return availableSatellites;
  }

  /**
   * Calculate the total usable window length for a task on a satellite.
   * The usable length is the sum of (attitudeRecordCount - 2) for all valid windows.
   *
   * @param task The task
   * @param satelliteIndex The satellite index
   * @return Total usable window length
   */
  protected int calculateUsableWindowLength(Task task, int satelliteIndex) {
    int totalLength = 0;
    for (TimeWindow tw : task.getTimeWindowsForSatellite(satelliteIndex)) {
      if (tw.canAccommodateTask(task.getImagingDuration())) {
        // Subtract 2 because task execution requires taskDuration + 2 attitude records
        // (one at start, one at end), so net usable length is recordCount - 2
        totalLength += tw.getAttitudeRecordCount() - 2;
      }
    }
    return totalLength;
  }

  /**
   * Count the number of available time windows for a task on a satellite.
   *
   * @param task The task
   * @param satelliteIndex The satellite index
   * @return Number of available time windows
   */
  protected int countAvailableWindows(Task task, int satelliteIndex) {
    int count = 0;
    for (TimeWindow tw : task.getTimeWindowsForSatellite(satelliteIndex)) {
      if (tw.canAccommodateTask(task.getImagingDuration())) {
        count++;
      }
    }
    return count;
  }
}
