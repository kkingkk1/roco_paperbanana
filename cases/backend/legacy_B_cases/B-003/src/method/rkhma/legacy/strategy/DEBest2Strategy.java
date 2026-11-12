package method.rkhma.legacy.strategy;

import method.rkhma.legacy.algorithm.de.DEBest2;
import method.rkhma.legacy.algorithm.de.DEConfig;
import java.util.List;
import method.rkhma.legacy.operator.mutation.DEUtils;

/**
 * DE/best/2 strategy - uses the best individual with two difference vectors.
 *
 * <p>Mutation: mutant = x_best + F * (x_r1 - x_r2) + F * (x_r3 - x_r4)</p>
 */
public class DEBest2Strategy implements ParentStrategy {
  private final DEConfig deConfig;

  public DEBest2Strategy(DEConfig deConfig) {
    this.deConfig = deConfig;
  }

  @Override
  public List<double[]> apply(ParentContext context, StrategyInput input) throws Exception {
    int bestIndex = input.getBestIndex() >= 0
      ? input.getBestIndex()
      : DEUtils.findBestIndexMax(input.getPopulationFitness());
    return DEBest2.runIntoUnsafe(
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

