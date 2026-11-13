package method.rkhma.legacy.strategy;

import method.rkhma.legacy.algorithm.abc.ABC;
import java.util.ArrayList;
import java.util.List;
import method.rkhma.legacy.model.Task;
import method.rkhma.legacy.operator.insertion.InsertionMode;
import method.rkhma.legacy.utils.PlanUtils;

/**
 * Artificial Bee Colony (ABC) strategy implementation.
 *
 * <p>ABC simulates the foraging behavior of honey bees with three phases:
 * employed bees, onlooker bees, and scout bees.</p>
 *
 * <h2>Required Input Fields</h2>
 * <ul>
 *   <li>{@code population} - Complete population (food sources)</li>
 *   <li>{@code selectedIndices} - Indices of individuals to operate on</li>
 *   <li>{@code populationFitness} - Fitness values</li>
 * </ul>
 *
 * <h2>Required Context Fields</h2>
 * <ul>
 *   <li>{@code context.abcContext} - ABC-specific state (config, limits)</li>
 *   <li>{@code context.satellite} - Satellite configuration</li>
 *   <li>{@code context.allTasks} - Task list</li>
 *   <li>{@code context.stateUpdater} - For updating ABC limits</li>
 * </ul>
 */
public class ABCStrategy implements ParentStrategy {

  // ABC algorithm has two phases: employed bee and onlooker bee
  private static final int ABC_PHASES = 2;

  public ABCStrategy() {
    // No configuration needed - all information obtained from context
  }

  @Override
  public List<double[]> apply(ParentContext context, StrategyInput input) throws Exception {
    List<double[]> population = input.getPopulation();
    int[] selectedIndices = input.getSelectedIndices();
    List<Double> populationFitness = input.getPopulationFitness();

    // Get ABC context
    ParentContext.ABCContext abcCtx = context.requireABCContext();

    // Run the ABC algorithm, using custom evaluator if available
    ABC.ABCIterationResult resultABC;
    java.util.Optional<java.util.function.Function<List<double[]>, List<Double>>> evalOpt =
    abcCtx.getEvaluator();
    if (evalOpt.isPresent()) {
      ABC.ObjectiveEvaluator evaluator = pop -> evalOpt.get().apply(pop);
      resultABC = ABC.run(
        population, selectedIndices, populationFitness,
        abcCtx.getAbcConfig(), abcCtx.getLimitABC(),
        context.getSatellite(), context.getAllTasks(),
        evaluator, context.getRand(), context.getABCWorkspace());
    } else {
      List<Task> referenceTasks = context.getAllTasks().isEmpty()
        ? context.getSatellite().getAssignedTasks()
        : context.getAllTasks();
      ABC.ObjectiveArrayEvaluator evaluator = (pop, out) ->
        PlanUtils.evaluatePopInto(
          pop, context.getSatellite(), referenceTasks, InsertionMode.GREEDY, out);
      resultABC = ABC.run(
        population, selectedIndices, populationFitness,
        abcCtx.getAbcConfig(), abcCtx.getLimitABC(),
        context.getSatellite(), context.getAllTasks(),
        null, evaluator, context.getRand(), context.getABCWorkspace());
    }

    // Update state using generic interface
    context.getStateUpdater().updateABCLimit(resultABC.limit);
    // ABC has two phases (employed + onlooker) per selected individual, plus scout evaluations
    context.getStateUpdater().incrementIteration(
      ABC_PHASES * selectedIndices.length + resultABC.scoutEvaluations);

    // Extract updated individuals based on selectIndices
    List<double[]> result = new ArrayList<>(selectedIndices.length);
    for (int idx : selectedIndices) {
      result.add(resultABC.pop.get(idx));
    }

    return result;
  }
}
