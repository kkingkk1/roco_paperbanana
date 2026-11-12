package method.rkhma.legacy.strategy;

import method.rkhma.legacy.algorithm.de.DERandToBest;
import method.rkhma.legacy.algorithm.de.DEConfig;
import java.util.List;
import method.rkhma.legacy.operator.mutation.DEUtils;

/**
 * DE/rand-to-best strategy - balances between rand and best-based mutations.
 *
 * <p>Mutation: mutant = x_r1 + F1 * (x_best - x_r1) + F2 * (x_r2 - x_r3)</p>
 *
 * <h2>Required Input Fields</h2>
 * <ul>
 *   <li>{@code population} - Complete population</li>
 *   <li>{@code selectedIndices} - Indices of individuals to evolve</li>
 *   <li>{@code populationFitness} - Fitness values</li>
 * </ul>
 */
public class DERandToBestStrategy implements ParentStrategy {
  private final DEConfig deConfig;

  public DERandToBestStrategy(DEConfig deConfig) {
    this.deConfig = deConfig;
  }

  @Override
  public List<double[]> apply(ParentContext context, StrategyInput input) throws Exception {
    int bestIndex = input.getBestIndex() >= 0
      ? input.getBestIndex()
      : DEUtils.findBestIndexMax(input.getPopulationFitness());
    return DERandToBest.runIntoUnsafe(
      input.getPopulation(),
      input.getSelectedIndices(),
      bestIndex,
      deConfig,
      context.getGen(),
      context.getRand(),
      context.getDEWorkspace()
    );
  }
}
