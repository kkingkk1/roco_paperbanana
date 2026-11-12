package method.rkhma.legacy.strategy;

import method.rkhma.legacy.algorithm.de.DEConfig;
import method.rkhma.legacy.algorithm.de.DERand1;
import java.util.List;

/**
 * DE/rand/1 (Differential Evolution with rand/1 mutation) strategy.
 *
 * <p>This variant randomly selects three distinct individuals from the population
 * and creates a mutant vector using: mutant = x_r1 + F * (x_r2 - x_r3)</p>
 *
 * <h2>Required Input Fields</h2>
 * <ul>
 *   <li>{@code population} - Complete population for mutation base selection</li>
 *   <li>{@code selectedIndices} - Indices of individuals to evolve</li>
 * </ul>
 *
 * @see DERand1
 * @see ParentStrategy
 */
public class DERand1Strategy implements ParentStrategy {
  private final DEConfig deConfig;

  public DERand1Strategy(DEConfig deConfig) {
    this.deConfig = deConfig;
  }

  @Override
  public List<double[]> apply(ParentContext context, StrategyInput input) throws Exception {
    if (input.getSelectedIndices().length == input.getPopulation().size()) {
      return DERand1.runIntoUnsafe(
        input.getPopulation(),
        input.getSelectedIndices(),
        deConfig,
        context.getGen(),
        context.getRand(),
        context.getDEWorkspace()
      );
    }
    return DERand1.runUnsafe(
      input.getPopulation(),
      input.getSelectedIndices(),
      deConfig,
      context.getGen(),
      context.getRand()
    );
  }
}

