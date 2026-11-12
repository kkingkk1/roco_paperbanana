package method.rkhma.legacy.strategy;

import java.util.AbstractList;
import java.util.List;
import method.rkhma.legacy.algorithm.pso.PSO;

/**
 * Particle Swarm Optimization (PSO) strategy for parent algorithm selection.
 *
 * <p>PSO maintains velocity and personal best (pbest) for each particle, and uses
 * global best (gbest) to guide the search. Particles update their positions based
 * on their own experience and the swarm's collective knowledge.</p>
 *
 * <h2>Algorithm Characteristics</h2>
 * <ul>
 *   <li>Requires personal best solutions (pbest) for each individual</li>
 *   <li>Requires global best solution (gbest)</li>
 *   <li>Uses update mask (indToBeUpdated) to selectively update particles</li>
 *   <li>Maintains external velocity reference that persists across iterations</li>
 * </ul>
 *
 * <h2>Required Input Fields</h2>
 * <ul>
 *   <li>{@code population} - Current positions of all particles</li>
 *   <li>{@code selectedIndices} - Indices of particles to update</li>
 *   <li>{@code pbestSolution} - Personal best positions</li>
 *   <li>{@code gbestSolution} - Global best position</li>
 *   <li>{@code indToBeUpdated} - Boolean mask for selective updates</li>
 * </ul>
 *
 * @see PSO
 * @see ParentStrategy
 */
public class PSOStrategy implements ParentStrategy {
  private final PSO.PSOConfig psoConfig;
  private final List<double[]> velocityRef;

  /**
   * Creates a new PSO strategy with the specified configuration and velocity reference.
   *
   * @param psoConfig   PSO algorithm configuration
   * @param velocityRef External velocity storage that persists across iterations
   */
  public PSOStrategy(PSO.PSOConfig psoConfig, List<double[]> velocityRef) {
    this.psoConfig = psoConfig;
    this.velocityRef = velocityRef;
  }

  @Override
  public List<double[]> apply(ParentContext context, StrategyInput input) throws Exception {
    // Extract all required fields for PSO
    List<double[]> population = input.getPopulation();
    int[] selectedIndices = input.getSelectedIndices();
    List<double[]> pbestSolution = input.getPbestSolution();
    double[] gbestSolution = input.getGbestSolution();
    List<Boolean> indToBeUpdated = input.getIndToBeUpdated();

    // Validate PSO-specific requirements
    if (pbestSolution == null) {
      throw new IllegalArgumentException("PSO requires pbestSolution");
    }
    if (gbestSolution == null) {
      throw new IllegalArgumentException("PSO requires gbestSolution");
    }
    if (indToBeUpdated == null) {
      throw new IllegalArgumentException("PSO requires indToBeUpdated mask");
    }

    // Run PSO with full population - PSO uses selectIndices to pick particles to evolve
    PSO.PsoResult result = PSO.runUnsafe(
      population,
      pbestSolution,
      gbestSolution,
      velocityRef,
      selectedIndices,
      psoConfig,
      context.getGen(),
      context.getRand(),
      context.getPSOWorkspace(),
      selectedVelocityView(selectedIndices)
    );

    return result.popSelect;
  }

  private List<double[]> selectedVelocityView(int[] selectedIndices) {
    return new AbstractList<>() {
      @Override
      public double[] get(int index) {
        if (index < 0 || index >= selectedIndices.length) {
          throw new IndexOutOfBoundsException("index=" + index + ", size=" + selectedIndices.length);
        }
        return velocityRef.get(selectedIndices[index]);
      }

      @Override
      public int size() {
        return selectedIndices.length;
      }
    };
  }
}
