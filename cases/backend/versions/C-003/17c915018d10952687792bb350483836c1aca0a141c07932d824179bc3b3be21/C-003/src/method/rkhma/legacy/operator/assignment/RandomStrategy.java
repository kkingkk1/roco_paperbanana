package method.rkhma.legacy.operator.assignment;

import method.rkhma.legacy.model.Satellite;
import method.rkhma.legacy.model.Task;

import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Random assignment strategy.
 * Randomly selects a satellite from the available satellites.
 */
public class RandomStrategy extends AbstractAssignmentStrategy {

  private final Random random;

  public RandomStrategy(Random random) {
    this.random = random;
  }

  @Override
  public Satellite selectSatellite(Task task, List<Satellite> availableSatellites,
    List<Satellite> allSatellites,
    Map<Integer, Integer> satelliteIndexMap) {
    if (availableSatellites.isEmpty()) {
      return null;
    }
    int randomIndex = random.nextInt(availableSatellites.size());
    return availableSatellites.get(randomIndex);
  }

  @Override
  public String getName() {
    return "Random";
  }
}
