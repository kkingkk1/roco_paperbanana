package method.rkhma.legacy.strategy;

import method.rkhma.legacy.algorithm.alns.ALNS;
import java.util.List;
import java.util.function.Function;

/**
 * Adaptive Large Neighborhood Search (ALNS) strategy implementation.
 *
 * <p>ALNS acts as a single-satellite local search parent algorithm. It decodes
 * selected random-key individuals into task sequences, applies adaptive
 * destroy/repair operators, and returns updated random-key chromosomes.</p>
 *
 * <p>Operator weights persist for the LIFETIME of this strategy instance (i.e.,
 * one HybCec.run() session — HybCec constructs a fresh strategy per session
 * via {@code getParentAlgorithm()}). This preserves adaptive learning across
 * outer ALNS calls within a single run while guaranteeing fixed-seed
 * reproducibility across runs.</p>
 */
public class ALNSStrategy implements ParentStrategy {

  private final ALNS.ALNSConfig alnsConfig;
  private final Function<List<double[]>, List<Double>> evaluator;

  public ALNSStrategy(ALNS.ALNSConfig alnsConfig) {
    this(alnsConfig, null);
  }

  public ALNSStrategy(
    ALNS.ALNSConfig alnsConfig,
    Function<List<double[]>, List<Double>> evaluator
  ) {
    this.alnsConfig = alnsConfig;
    this.evaluator = evaluator;
  }

  @Override
  public List<double[]> apply(ParentContext context, StrategyInput input) {
    return applyWithResult(context, input).getUpdatedPopulation();
  }

  @Override
  public ParentUpdateResult applyWithResult(ParentContext context, StrategyInput input) {
    ALNS.ALNSResult result = ALNS.runWithResult(
      input.getPopulation(),
      input.getSelectedIndices(),
      input.getPopulationFitness(),
      alnsConfig,
      context.getSatellite(),
      context.getAllTasks(),
      context.getRand(),
      evaluator,
      context.getSingleEvaluator().orElse(null),
      context.getSequenceEvaluator().orElse(null),
      context.getAlnsDestroyOperators(alnsConfig),
      context.getAlnsRepairOperators(alnsConfig)
    );
    context.getStateUpdater().incrementIteration(result.evaluationCount());
    return ParentUpdateResult.evaluated(result.updatedPopulation(), result.evaluatedFitness());
  }
}
