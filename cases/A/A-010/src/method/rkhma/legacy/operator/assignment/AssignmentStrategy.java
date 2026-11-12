package method.rkhma.legacy.operator.assignment;

import method.rkhma.legacy.model.Satellite;
import method.rkhma.legacy.model.Task;

import java.util.List;

/**
 * Strategy interface for satellite assignment algorithms.
 * Each strategy evaluates satellites and selects the best one for a given task.
 */
public interface AssignmentStrategy {

  /**
   * Select the best satellite for a task from the list of available satellites.
   *
   * @param task The task to be assigned
   * @param availableSatellites List of satellites that can accommodate the task
   * @param allSatellites List of all satellites in the system
   * @param satelliteIndexMap Map from satellite ID to its index in allSatellites
   * @return The selected satellite, or null if no suitable satellite found
   */
  Satellite selectSatellite(Task task, List<Satellite> availableSatellites,
    List<Satellite> allSatellites,
    java.util.Map<Integer, Integer> satelliteIndexMap);

  /**
   * Get the name of this strategy for logging/debugging purposes.
   *
   * @return The strategy name
   */
  String getName();
}
