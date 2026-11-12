package method.rkhma.legacy.strategy;

import java.util.List;
import java.util.Objects;

/**
 * Parent algorithm output, optionally carrying fitness values already evaluated
 * inside the parent strategy.
 */
public final class ParentUpdateResult {

  private final List<double[]> updatedPopulation;
  private final List<Double> evaluatedFitness;

  private ParentUpdateResult(List<double[]> updatedPopulation, List<Double> evaluatedFitness) {
    this.updatedPopulation = Objects.requireNonNull(updatedPopulation, "updatedPopulation cannot be null");
    if (evaluatedFitness != null && evaluatedFitness.size() != updatedPopulation.size()) {
      throw new IllegalArgumentException(
        "evaluatedFitness size must match updatedPopulation size: fitness="
          + evaluatedFitness.size() + ", population=" + updatedPopulation.size());
    }
    this.evaluatedFitness = evaluatedFitness;
  }

  public static ParentUpdateResult unevaluated(List<double[]> updatedPopulation) {
    return new ParentUpdateResult(updatedPopulation, null);
  }

  public static ParentUpdateResult evaluated(
    List<double[]> updatedPopulation,
    List<Double> evaluatedFitness
  ) {
    return new ParentUpdateResult(
      updatedPopulation,
      Objects.requireNonNull(evaluatedFitness, "evaluatedFitness cannot be null"));
  }

  public List<double[]> getUpdatedPopulation() {
    return updatedPopulation;
  }

  public boolean hasEvaluatedFitness() {
    return evaluatedFitness != null;
  }

  public List<Double> getEvaluatedFitness() {
    return evaluatedFitness;
  }
}
