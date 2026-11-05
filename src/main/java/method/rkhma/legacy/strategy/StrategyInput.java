package method.rkhma.legacy.strategy;

import java.util.List;

/**
 * Input data container for ParentStrategy algorithms.
 *
 * This class encapsulates all possible input parameters that different strategies
 * might need, allowing each strategy to extract only what it requires.
 * Uses the Builder pattern for flexible and readable construction.
 *
 * <p>Common fields used by most strategies:</p>
 * <ul>
 *   <li>{@code population} - The complete population of solutions</li>
 *   <li>{@code selectedIndices} - Indices of individuals selected for operation</li>
 *   <li>{@code populationFitness} - Fitness values for the complete population</li>
 * </ul>
 *
 * <p>Optional fields used by specific strategies:</p>
 * <ul>
 *   <li>{@code pbestSolution} - Personal best solutions (PSO)</li>
 *   <li>{@code gbestSolution} - Global best solution (PSO)</li>
 *   <li>{@code indToBeUpdated} - Mask indicating which individuals to update (PSO)</li>
 * </ul>
 *
 * @see ParentStrategy
 */
public class StrategyInput {

  // ========================================
  // Core fields (used by most strategies)
  // ========================================

  /** Complete population of solutions */
  private final List<double[]> population;

  /** Indices of selected individuals in the population */
  private final int[] selectedIndices;

  /** Fitness values for the complete population */
  private final List<Double> populationFitness;

  /** Cached index of the best individual; -1 means unavailable */
  private final int bestIndex;

  /** Cached descending fitness rank; null means unavailable. */
  private final int[] fitnessRankDesc;

  // ========================================
  // Optional fields (strategy-specific)
  // ========================================

  /** Personal best solutions for each individual (PSO) */
  private final List<double[]> pbestSolution;

  /** Global best solution (PSO, some DE variants) */
  private final double[] gbestSolution;

  /** Boolean mask indicating which individuals should be updated (PSO) */
  private final List<Boolean> indToBeUpdated;

  // Private constructor - use Builder
  private StrategyInput(Builder builder) {
    this.population = builder.population;
    this.selectedIndices = builder.selectedIndices;
    this.populationFitness = builder.populationFitness;
    this.bestIndex = builder.bestIndex;
    this.fitnessRankDesc = builder.fitnessRankDesc;
    this.pbestSolution = builder.pbestSolution;
    this.gbestSolution = builder.gbestSolution;
    this.indToBeUpdated = builder.indToBeUpdated;

    // Validate required fields
    if (population == null) {
      throw new IllegalArgumentException("population cannot be null");
    }
    if (selectedIndices == null) {
      throw new IllegalArgumentException("selectedIndices cannot be null");
    }
  }

  // ========================================
  // Getters
  // ========================================

  /** Returns the complete population of solutions */
  public List<double[]> getPopulation() {
    return population;
  }

  /** Returns indices of selected individuals */
  public int[] getSelectedIndices() {
    return selectedIndices;
  }

  /** Returns fitness values for the complete population */
  public List<Double> getPopulationFitness() {
    return populationFitness;
  }

  /** Returns cached best index, or -1 if unavailable */
  public int getBestIndex() {
    return bestIndex;
  }

  /** Returns cached descending fitness rank, or null if unavailable. */
  public int[] getFitnessRankDesc() {
    return fitnessRankDesc;
  }

  /** Returns personal best solutions (may be null, used by PSO) */
  public List<double[]> getPbestSolution() {
    return pbestSolution;
  }

  /** Returns global best solution (may be null, used by PSO) */
  public double[] getGbestSolution() {
    return gbestSolution;
  }

  /** Returns update mask (may be null, used by PSO) */
  public List<Boolean> getIndToBeUpdated() {
    return indToBeUpdated;
  }

  // ========================================
  // Builder Pattern
  // ========================================

  /**
   * Builder for creating StrategyInput instances.
   *
   * <p>Example usage:</p>
   * <pre>{@code
   * StrategyInput input = StrategyInput.builder()
   *     .population(X)
   *     .selectedIndices(indices)
   *     .populationFitness(popFit)
   *     .build();
   * }</pre>
   */
  public static class Builder {
    // Required
    private List<double[]> population;
    private int[] selectedIndices;

    // Optional
    private List<Double> populationFitness;
    private int bestIndex = -1;
    private int[] fitnessRankDesc;
    private List<double[]> pbestSolution;
    private double[] gbestSolution;
    private List<Boolean> indToBeUpdated;

    public Builder population(List<double[]> population) {
      this.population = population;
      return this;
    }

    public Builder selectedIndices(int[] selectedIndices) {
      this.selectedIndices = selectedIndices;
      return this;
    }

    public Builder populationFitness(List<Double> populationFitness) {
      this.populationFitness = populationFitness;
      return this;
    }

    public Builder bestIndex(int bestIndex) {
      this.bestIndex = bestIndex;
      return this;
    }

    public Builder fitnessRankDesc(int[] fitnessRankDesc) {
      this.fitnessRankDesc = fitnessRankDesc;
      return this;
    }

    public Builder pbestSolution(List<double[]> pbestSolution) {
      this.pbestSolution = pbestSolution;
      return this;
    }

    public Builder gbestSolution(double[] gbestSolution) {
      this.gbestSolution = gbestSolution;
      return this;
    }

    public Builder indToBeUpdated(List<Boolean> indToBeUpdated) {
      this.indToBeUpdated = indToBeUpdated;
      return this;
    }

    /**
     * Builds the StrategyInput instance.
     *
     * @return A new StrategyInput instance
     * @throws IllegalArgumentException if required fields are missing
     */
    public StrategyInput build() {
      return new StrategyInput(this);
    }
  }

  /**
   * Creates a new Builder instance.
   *
   * @return A new Builder
   */
  public static Builder builder() {
    return new Builder();
  }
}
