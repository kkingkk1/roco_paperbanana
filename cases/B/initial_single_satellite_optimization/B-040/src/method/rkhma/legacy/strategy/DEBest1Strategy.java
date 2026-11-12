package method.rkhma.legacy.strategy;

import method.rkhma.legacy.algorithm.de.DEConfig;
import method.rkhma.legacy.algorithm.de.DEBest1;
import java.util.List;
import method.rkhma.legacy.operator.mutation.DEUtils;

/**
 * DE/best/1 strategy - uses the best individual as mutation base.
 *
 * <p>Mutation: mutant = x_best + F * (x_r1 - x_r2)</p>
 *
 * <h2>Required Input Fields</h2>
 * <ul>
 *   <li>{@code population} - Complete population (for best selection and mutation)</li>
 *   <li>{@code selectedIndices} - Indices of individuals to evolve</li>
 *   <li>{@code populationFitness} - Fitness values to identify best individual</li>
 * </ul>
 */
public class DEBest1Strategy implements ParentStrategy {
  private final DEConfig deConfig;

  public DEBest1Strategy(DEConfig deConfig) {
    this.deConfig = deConfig;
  }

  @Override
  public List<double[]> apply(ParentContext context, StrategyInput input) throws Exception {
    int bestIndex = input.getBestIndex() >= 0
      ? input.getBestIndex()
      : DEUtils.findBestIndexMax(input.getPopulationFitness());
    if (input.getSelectedIndices().length == input.getPopulation().size()) {
      return DEBest1.runIntoUnsafe(
        input.getPopulation(),
        input.getSelectedIndices(),
        bestIndex,
        deConfig,
        context.getGen(),
        context.getRand(),
        context.getDEWorkspace()
      );
    }
    return DEBest1.runWithBestUnsafe(
      input.getPopulation(),
      input.getSelectedIndices(),
      bestIndex,
      deConfig,
      context.getGen(),
      context.getRand()
    );
  }
}
