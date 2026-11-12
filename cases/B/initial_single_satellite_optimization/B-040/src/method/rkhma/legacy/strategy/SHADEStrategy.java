package method.rkhma.legacy.strategy;

import java.util.List;

import method.rkhma.legacy.algorithm.shade.SHADELoopBrief;

/**
 * SHADE/L-SHADE (Success-History based Adaptive Differential Evolution) strategy.
 *
 * <p>SHADE adapts F and CR parameters based on successful parameter values from
 * previous generations, maintaining a historical memory.</p>
 *
 * <h2>Required Input Fields</h2>
 * <ul>
 *   <li>{@code population} - Complete population</li>
 *   <li>{@code selectedIndices} - Indices of individuals to evolve</li>
 *   <li>{@code populationFitness} - Complete fitness values</li>
 * </ul>
 *
 * <h2>Required Context Fields</h2>
 * <ul>
 *   <li>{@code context.shadeContext} - SHADE-specific state (config, memory)</li>
 *   <li>{@code context.itera} - Current iteration number</li>
 *   <li>{@code context.maxItera} - Maximum iterations</li>
 *   <li>{@code context.stateUpdater} - For updating SHADE memory</li>
 * </ul>
 */
public class SHADEStrategy implements ParentStrategy {

  public SHADEStrategy() {
    // No configuration needed - all information obtained from context
  }

  @Override
  public List<double[]> apply(ParentContext context, StrategyInput input) throws Exception {
    List<double[]> population = input.getPopulation();
    int[] selectedIndices = input.getSelectedIndices();
    List<Double> populationFitness = input.getPopulationFitness();

    // Get SHADE context
    ParentContext.SHADEContext shadeCtx = context.requireSHADEContext();

    // Get configuration object
    SHADELoopBrief.SHADEConfig cfg = shadeCtx.getShadeConfig();
    cfg.cachedFitnessRankDesc = input.getFitnessRankDesc();

    // Run SHADE algorithm with selectedIndices
    SHADELoopBrief.SHADEResult resultSHADE;
    if (cfg.probEigenCrossover == 0.0) {
      resultSHADE = SHADELoopBrief.runInto(
        population,
        populationFitness,
        selectedIndices,
        shadeCtx.getShadeGG(),
        context.getItera(),
        context.getMaxItera(),
        cfg,
        shadeCtx.getGoodFit1All(),
        shadeCtx.getGoodFit2All(),
        shadeCtx.getBadFit1All(),
        shadeCtx.getBadFit2All(),
        context.getRand(),
        context.getSHADEWorkspace()
      );
    } else {
      resultSHADE = SHADELoopBrief.run(
        population,
        populationFitness,
        selectedIndices,
        shadeCtx.getShadeGG(),
        context.getItera(),
        context.getMaxItera(),
        cfg,
        shadeCtx.getGoodFit1All(),
        shadeCtx.getGoodFit2All(),
        shadeCtx.getBadFit1All(),
        shadeCtx.getBadFit2All(),
        context.getRand()
      );
    }

    // Audited: these sampled values are later filtered to successful trials in UpdateProbSHADE.
    context.getStateUpdater().updateSHADEState(
      resultSHADE.shadeGG,
      resultSHADE.shadeSF,
      resultSHADE.shadeCR,
      resultSHADE.shadeFreq,
      resultSHADE.flag1Shade,
      resultSHADE.flag2Shade
    );

    return resultSHADE.newPop;
  }
}

