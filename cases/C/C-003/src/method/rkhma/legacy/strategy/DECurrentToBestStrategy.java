package method.rkhma.legacy.strategy;

import method.rkhma.legacy.algorithm.de.DEConfig;
import method.rkhma.legacy.algorithm.de.DECurrentToBest;
import java.util.List;
import method.rkhma.legacy.operator.mutation.DEUtils;

/**
 * DE/current-to-best strategy.
 *
 * <p>Mutation: mutant = x_i + F * (x_best - x_i) + F * (x_r1 - x_r2)</p>
 */
public class DECurrentToBestStrategy implements ParentStrategy {
  private final DEConfig deConfig;

  public DECurrentToBestStrategy(DEConfig deConfig) {
    this.deConfig = deConfig;
  }

  @Override
  public List<double[]> apply(ParentContext context, StrategyInput input) throws Exception {
    int bestIndex = input.getBestIndex() >= 0
      ? input.getBestIndex()
      : DEUtils.findBestIndexMax(input.getPopulationFitness());
    if (input.getSelectedIndices().length == input.getPopulation().size()) {
      return DECurrentToBest.runIntoUnsafe(
        input.getPopulation(),
        input.getSelectedIndices(),
        bestIndex,
        deConfig,
        context.getGen(),
        context.getRand(),
        context.getDEWorkspace()
      );
    }
    return DECurrentToBest.runWithBestUnsafe(
      input.getPopulation(),
      input.getSelectedIndices(),
      bestIndex,
      deConfig,
      context.getGen(),
      context.getRand()
    );
  }
}
