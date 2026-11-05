package method.rkhma.legacy.strategy;

import method.rkhma.legacy.algorithm.eda.EDAGaussSam;
import java.util.List;

/**
 * Estimation of Distribution Algorithm (EDA) strategy using Gaussian sampling.
 *
 * <p>EDA builds a probabilistic model of promising solutions and samples new
 * individuals from this distribution.</p>
 *
 * <h2>Required Input Fields</h2>
 * <ul>
 *   <li>{@code population} - Complete population for model estimation</li>
 *   <li>{@code populationFitness} - Fitness values for elite selection</li>
 *   <li>{@code selectedIndices} - Indices of individuals to evolve</li>
 * </ul>
 *
 * @see EDAGaussSam
 * @see ParentStrategy
 */
public class EDAStrategy implements ParentStrategy {
  private final EDAGaussSam.EDAConfig edaConfig;

  public EDAStrategy(EDAGaussSam.EDAConfig edaConfig) {
    this.edaConfig = edaConfig;
  }

  @Override
  public List<double[]> apply(ParentContext context, StrategyInput input) throws Exception {
    return EDAGaussSam.run(
      input.getPopulation(),
      input.getPopulationFitness(),
      input.getSelectedIndices(),
      edaConfig,
      context.getRand(),
      context.getCountNoImprove(),
      context.getEDAWorkspace()
    );
  }
}

