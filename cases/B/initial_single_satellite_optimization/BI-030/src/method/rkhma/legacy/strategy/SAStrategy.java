package method.rkhma.legacy.strategy;

import java.util.List;
import java.util.function.Function;
import method.rkhma.legacy.algorithm.sa.SACont;

/**
 * Simulated Annealing (SA) strategy implementation.
 *
 * <p>SA uses temperature-based acceptance criteria to escape local optima.
 * The algorithm maintains a temperature parameter that decreases over iterations.</p>
 *
 * <h2>Required Input Fields</h2>
 * <ul>
 *   <li>{@code population} - Complete population</li>
 *   <li>{@code selectedIndices} - Indices of individuals to operate on</li>
 *   <li>{@code populationFitness} - Fitness values</li>
 *   <li>{@code indToBeUpdated} - Boolean mask for selective updates</li>
 * </ul>
 *
 * <h2>Required Context Fields</h2>
 * <ul>
 *   <li>{@code context.saContext} - SA-specific state (config, sigma, evaluator)</li>
 *   <li>{@code context.satellite} - Satellite configuration</li>
 *   <li>{@code context.allTasks} - Task list</li>
 *   <li>{@code context.stateUpdater} - For updating SA sigma</li>
 * </ul>
 */
public class SAStrategy implements ParentStrategy {

  public SAStrategy() {
    // No configuration needed - all information obtained from context
  }

  @Override
  public List<double[]> apply(ParentContext context, StrategyInput input) throws Exception {
    List<double[]> population = input.getPopulation();
    int[] selectedIndices = input.getSelectedIndices();
    List<Double> populationFitness = input.getPopulationFitness();
    List<Boolean> indToBeUpdated = input.getIndToBeUpdated();

    // Validate SA-specific requirements
    if (indToBeUpdated == null) {
      throw new IllegalArgumentException("SA requires indToBeUpdated mask");
    }

    // Get SA context
    ParentContext.SAContext saCtx = context.requireSAContext();

    // Run SA algorithm using index-based mode
    Function<List<double[]>, List<Double>> evaluator = saCtx.getEvaluator().orElse(null);

    SACont.SAResult resultSA = SACont.run(
      population,                      // Full population
      populationFitness,               // Full fitness
      saCtx.getSigma(),                // Full sigma
      selectedIndices,                 // Selected indices
      saCtx.getSaConfig(),
      context.getSatellite(),
      context.getAllTasks(),
      context.getRand(),
      evaluator,
      context.getSAWorkspace()
    );

    // Update state using generic interface
    context.getStateUpdater().updateSASigma(resultSA.sigma, indToBeUpdated);
    context.getStateUpdater().incrementIteration(context.getCount());

    return resultSA.sBest;
  }
}
