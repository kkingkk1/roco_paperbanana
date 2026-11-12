package method.rkhma.legacy.strategy;

import method.rkhma.legacy.algorithm.de.DEConfig;
import method.rkhma.legacy.algorithm.de.DERand2;
import java.util.List;

/**
 * DE/rand/2 strategy - uses two difference vectors.
 *
 * <p>Mutation: mutant = x_r1 + F * (x_r2 - x_r3) + F * (x_r4 - x_r5)</p>
 */
public class DERand2Strategy implements ParentStrategy {
  private final DEConfig deConfig;

  public DERand2Strategy(DEConfig deConfig) {
    this.deConfig = deConfig;
  }

  @Override
  public List<double[]> apply(ParentContext context, StrategyInput input) throws Exception {
    return DERand2.runIntoUnsafe(
      input.getPopulation(),
      input.getSelectedIndices(),
      deConfig,
      context.getGen(),
      context.getRand(),
      context.getDEWorkspace()
    );
  }
}


