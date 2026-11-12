package method.rkhma.legacy.model;

import java.util.Objects;

/**
 * Represents a pair of satellite ID and task ID for task assignment.
 * This class replaces the List<Integer> pairs used in the original implementation,
 * providing type safety and better code readability.
 */
public class SatelliteTaskPair {
  private final int satelliteId;
  private final int taskId;

  /**
   * Constructor
   *
   * @param satelliteId The ID of the satellite
   * @param taskId The ID of the task
   */
  public SatelliteTaskPair(int satelliteId, int taskId) {
    this.satelliteId = satelliteId;
    this.taskId = taskId;
  }

  /**
   * Get the satellite ID
   *
   * @return The satellite ID
   */
  public int getSatelliteId() {
    return satelliteId;
  }

  /**
   * Get the task ID
   *
   * @return The task ID
   */
  public int getTaskId() {
    return taskId;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    SatelliteTaskPair that = (SatelliteTaskPair) o;
    return satelliteId == that.satelliteId && taskId == that.taskId;
  }

  @Override
  public int hashCode() {
    return Objects.hash(satelliteId, taskId);
  }

  @Override
  public String toString() {
    return String.format("SatelliteTaskPair{satId=%d, taskId=%d}", satelliteId, taskId);
  }
}
