package method.rkhma.legacy.core.manager;

import method.rkhma.legacy.core.config.HybCecConfigManager;
import method.rkhma.legacy.algorithm.shade.SHADELoopBrief;
import method.rkhma.legacy.common.AlgorithmConstants;
import method.rkhma.legacy.core.HybCec;
import method.rkhma.legacy.core.calculator.HybridLevelCalculator;
import method.rkhma.legacy.core.config.AlgorithmIndex;
import method.rkhma.legacy.core.state.HybCecState;
import method.rkhma.legacy.operator.selection.SurvivorSelect;
import method.rkhma.legacy.algorithm.shade.UpdateProbSHADE;
import method.rkhma.legacy.utils.ListUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import method.rkhma.legacy.model.Satellite;
import method.rkhma.legacy.strategy.ParentUpdateResult;
import method.rkhma.legacy.utils.PlanUtils;

/**
 * Population Manager
 * 
 * Responsible for handling the selection, preparation, and updating of the population, including:
 * - Population Prepared for Selection (preparePopSelect Series)
 * - Processing Parent Algorithm Update Results (processParentUpdate Series)
 * - Processing Updates in Parallel Mode (processParallelUpdate Series)
 * - Survivor Combination in Parallel Mode (combineSurvivorsParallel)
 * 
 * By centrally managing population operation logic, it is possible to:
 * 1. Centralize population operation logic for easier maintenance.
 * 2. Optimize population operation performance.
 * 3. Support different population management strategies.
 * 4. Reduce the complexity of the HybCec class.
 */
public class PopulationManager {

  /** State Manager Reference */
  private final HybCecState state;

  /** Configuration Manager Reference */
  private final HybCecConfigManager configManager;

  /** Population Resizer Reference */
  private final PopulationResizer populationResizer;

  /** Hybrid Level Calculator Reference */
  private final HybridLevelCalculator hybridLevelCalculator;

  /** Satellite Object */
  private final Satellite satellite;
  private final HybCec.FitnessEvaluator fitnessEvaluator;

  /** Problem Dimension */
  private final int D;

  /** Initial population size (used for initial capacities only) */
  private final int nPopInit;

  /** Operation level array */
  private final int[] oL;

  /** Operation order */
  private final int oO;

  /** Survival strategy */
  private final int strSurvive;

  /** Function interface for obtaining a new population */
  @FunctionalInterface
  public interface NewPopulationGetter {
    ParentUpdateResult getNewPopUpdated(
      List<double[]> pbestSolution,
      double[] gbestSolution,
      int parent,
      List<Boolean> indToBeUpdated,
      int[] selectIndices,
      List<double[]> X,
      List<Double> popFit) throws Exception;
  }

  /** Interface for setting the population */
  public interface PopulationSetter {
    void setPop(List<double[]> pop);
    void setPopFit(List<Double> popFit);
    void setPbestSolution(List<double[]> pbestSolution);
    void setPbestFit(List<Double> pbestFit);
    void reSetParameters(int D, int newNPop);
  }

  /** New population acquirer */
  private final NewPopulationGetter newPopulationGetter;

  /** Population setter */
  private final PopulationSetter populationSetter;

  /**
   * Wrapper class for individual arrays to enable HashMap-based lookups.
   * Provides O(1) average-case lookup instead of O(n) linear search.
   */
  private static class IndividualWrapper {
    private final double[] individual;
    private final int hashCode;

    public IndividualWrapper(double[] individual) {
      this.individual = individual;
      this.hashCode = Arrays.hashCode(individual);
    }

    @Override
    public int hashCode() {
      return hashCode;
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) return true;
      if (!(obj instanceof IndividualWrapper)) return false;
      IndividualWrapper other = (IndividualWrapper) obj;
      return Arrays.equals(this.individual, other.individual);
    }
  }

  /**
   * Deep copy a population list.
   * This is necessary because parent algorithms may modify the population during evolution.
   *
   * @param source Source population to copy
   * @param dimension Dimension of each individual
   * @return Deep copied population
   */
  private List<double[]> deepCopyPopulation(List<double[]> source, int dimension) {
    List<double[]> copy = new ArrayList<>(source.size());
    for (double[] individual : source) {
      copy.add(Arrays.copyOf(individual, dimension));
    }
    return copy;
  }

  /**
   * Evaluate fitness for updated individuals and update iteration counters.
   *
   * @param X Modified population
   * @param indToBeUpdated Mask of individuals that were updated
   * @param popFitX Output fitness values
   */
  private List<Double> evaluateAndUpdateCounters(
    List<double[]> X,
    List<Boolean> indToBeUpdated,
    List<Double> popFitX,
    int parentNum
  ) {
    List<double[]> toEval = ListUtils.selectRows(X, indToBeUpdated);
    List<Double> newPopFit = (fitnessEvaluator != null)
    ? fitnessEvaluator.evaluate(toEval)
    : PlanUtils.evaluatePop(toEval, satellite);
    ListUtils.assignElements(popFitX, newPopFit, indToBeUpdated);

    int cnt = ListUtils.countTrue(indToBeUpdated);
    state.incrementItera(cnt);
    state.addTotalUpdatedInd(cnt);
    recordIterationEvalCost(parentNum, cnt);

    return newPopFit;
  }

  private void recordIterationEvalCost(int parentNum, int evalCost) {
    if (evalCost <= 0) {
      return;
    }
    if (parentNum == 1) {
      state.addIterationEvalCostParent1(evalCost);
    } else if (parentNum == 2) {
      state.addIterationEvalCostParent2(evalCost);
    }
  }

  private List<Double> evaluateOrReuseFitness(
    List<double[]> candidates,
    List<Double> cachedFitness,
    int parentNum
  ) {
    if (cachedFitness != null) {
      state.addTotalUpdatedInd(candidates.size());
      return cachedFitness;
    }
    List<Double> newFits = (fitnessEvaluator != null)
    ? fitnessEvaluator.evaluate(candidates)
    : PlanUtils.evaluatePop(candidates, satellite);
    state.incrementItera(candidates.size());
    state.addTotalUpdatedInd(candidates.size());
    recordIterationEvalCost(parentNum, candidates.size());
    return newFits;
  }

  private boolean maskCoversAllDimensions(boolean[] mask) {
    if (mask == null || mask.length < D) {
      return false;
    }
    for (int j = 0; j < D; j++) {
      if (!mask[j]) {
        return false;
      }
    }
    return true;
  }

  private List<Double> selectUsableCachedFitness(
    ParentUpdateResult updateResult,
    List<Boolean> indToBeUpdated,
    List<boolean[]> unitMask
  ) {
    if (!updateResult.hasEvaluatedFitness()) {
      return null;
    }
    List<Double> cached = updateResult.getEvaluatedFitness();
    List<Double> usable = new ArrayList<>(cached.size());
    int updateIdx = 0;
    for (int i = 0; i < indToBeUpdated.size(); i++) {
      if (!indToBeUpdated.get(i)) continue;
      if (!maskCoversAllDimensions(unitMask.get(i))) {
        return null;
      }
      usable.add(cached.get(updateIdx++));
    }
    return usable;
  }

  /**
   * Update SHADE algorithm-specific probabilities if applicable.
   *
   * @param parentAlg Parent algorithm index
   * @param indToBeUpdated Mask of individuals that were updated
   * @param newPopFit Fitness values of updated individuals
   */
  private void updateSHADEProbabilities(int parentAlg, List<Boolean> indToBeUpdated,
    List<Double> newPopFit) {
    if (!configManager.isShadeActive()) {
      return;
    }
    if (parentAlg != AlgorithmConstants.ALGORITHM_INDEX_LSHADE) {
      return;
    }

    SHADELoopBrief.SHADEConfig shadeConfig = configManager.getShadeConfig();
    if (shadeConfig != null) {
      int[] selectIndices = method.rkhma.legacy.operator.mutation.DEUtils.maskToIndices(indToBeUpdated);
      state.setMemoryPos(UpdateProbSHADE.updateProbSHADE(
        state.getPop(), state.getPopFit(), selectIndices, newPopFit,
        state.getShadeSF(), state.getShadeCR(), state.getShadeFreq(), shadeConfig.archive,
        state.getGoodFit1All(), state.getBadFit1All(), state.getGoodFit2All(), state.getBadFit2All(),
        state.getMemoryPos(), shadeConfig.memorySF, shadeConfig.memoryCR, shadeConfig.memoryFreq,
        state.isFlag1Shade(), state.isFlag2Shade(), shadeConfig.memorySize));
    }
  }

  /**
   * Rebuild pbest solution tracking after survivor selection.
   * Uses HashMap-based O(n) matching instead of O(n²) nested loop.
   *
   * @param popBefore Population before survivor selection
   * @param pbestBefore Personal best solutions before selection
   * @param pbestFitBefore Personal best fitness values before selection
   * @param popAfter Population after survivor selection
   * @return Result containing updated pbest solutions and fitness values
   */
  private PbestRebuildResult rebuildPbest(List<double[]> popBefore,
    List<double[]> pbestBefore,
    List<Double> pbestFitBefore,
    List<double[]> popAfter) {
    int popAfterSize = popAfter.size();
    List<double[]> pbestAfter = new ArrayList<>(popAfterSize);
    List<Double> pbestFitAfter = new ArrayList<>(popAfterSize);

    // Use IdentityHashMap for O(1) reference-based lookup (no Arrays.hashCode overhead)
    java.util.IdentityHashMap<double[], Integer> popBeforeIndex = new java.util.IdentityHashMap<>(popBefore.size());
    for (int j = 0; j < popBefore.size(); j++) {
      popBeforeIndex.put(popBefore.get(j), j);
    }

    for (int i = 0; i < popAfterSize; i++) {
      double[] indiv = popAfter.get(i);
      Integer matchIdx = popBeforeIndex.get(indiv);

      if (matchIdx != null && matchIdx < pbestBefore.size() && matchIdx < pbestFitBefore.size()) {
        pbestAfter.add(Arrays.copyOf(pbestBefore.get(matchIdx), D));
        pbestFitAfter.add(pbestFitBefore.get(matchIdx));
      } else {
        pbestAfter.add(Arrays.copyOf(indiv, D));
        pbestFitAfter.add(state.getPopFit().get(i));
      }
    }

    return new PbestRebuildResult(pbestAfter, pbestFitAfter);
  }

  /**
   * Result container for pbest rebuilding operation.
   */
  private static class PbestRebuildResult {
    final List<double[]> pbestSolution;
    final List<Double> pbestFit;

    PbestRebuildResult(List<double[]> pbestSolution, List<Double> pbestFit) {
      this.pbestSolution = pbestSolution;
      this.pbestFit = pbestFit;
    }
  }

  private static final int SURVIVOR_SOURCE_PARENT = 0;
  private static final int SURVIVOR_SOURCE_OA1 = 1;
  private static final int SURVIVOR_SOURCE_OA2 = 2;

  /**
   * Apply L-SHADE population resizing if enabled.
   */
  private void applyPopulationResizing() {
    if (state.getFlagPar()[AlgorithmConstants.ALGORITHM_INDEX_LSHADE] != 1) {
      return;
    }

    int popSizeNowForResize = state.getPop().size();
    PopulationResizer.ResizeResult resizeResult = populationResizer.resizePopulation(popSizeNowForResize);
    if (resizeResult.needsParameterReset()) {
      populationSetter.reSetParameters(D, resizeResult.getNewNPop());
    }
    populationSetter.setPop(resizeResult.getResizeResult().pop);
    populationSetter.setPopFit(resizeResult.getResizeResult().popFit);
    populationSetter.setPbestSolution(resizeResult.getResizeResult().pbestS);
    populationSetter.setPbestFit(resizeResult.getResizeResult().pbestF);
  }

  /**
   * Construct Population Manager
   */
  public PopulationManager(
    HybCecState state,
    HybCecConfigManager configManager,
    PopulationResizer populationResizer,
    HybridLevelCalculator hybridLevelCalculator,
    Satellite satellite,
    int D,
    int nPopInit,
    int[] oL,
    int oO,
    int strSurvive,
    NewPopulationGetter newPopulationGetter,
    PopulationSetter populationSetter,
    HybCec.FitnessEvaluator fitnessEvaluator) {
    // Validate required parameters
    if (state == null) {
      throw new IllegalArgumentException("state cannot be null");
    }
    if (configManager == null) {
      throw new IllegalArgumentException("configManager cannot be null");
    }
    if (populationResizer == null) {
      throw new IllegalArgumentException("populationResizer cannot be null");
    }
    if (hybridLevelCalculator == null) {
      throw new IllegalArgumentException("hybridLevelCalculator cannot be null");
    }
    if (newPopulationGetter == null) {
      throw new IllegalArgumentException("newPopulationGetter cannot be null");
    }
    if (populationSetter == null) {
      throw new IllegalArgumentException("populationSetter cannot be null");
    }
    if (oL == null || oL.length < 2) {
      throw new IllegalArgumentException("oL must be non-null and have at least 2 elements");
    }
    if (D <= 0) {
      throw new IllegalArgumentException("D must be positive, got: " + D);
    }
    if (nPopInit <= 0) {
      throw new IllegalArgumentException("nPopInit must be positive, got: " + nPopInit);
    }

    this.state = state;
    this.configManager = configManager;
    this.populationResizer = populationResizer;
    this.hybridLevelCalculator = hybridLevelCalculator;
    this.satellite = satellite;
    this.fitnessEvaluator = fitnessEvaluator;
    this.D = D;
    this.nPopInit = nPopInit;
    this.oL = oL;
    this.oO = oO;
    this.strSurvive = strSurvive;
    this.newPopulationGetter = newPopulationGetter;
    this.populationSetter = populationSetter;
  }

  /**
   * Population prepared for selection (unified method, used for OA1 and OA2)
   *
   * @param parentIdx Parent algorithm index (0 or 1)
   * @param oLIndex Operation level index (0 or 1)
   * @param indToBeUpdated Individual masks that need updating
   * @param X Current population Copy
   * @param popFitX Current Fitness Copy
   * @return The indices array for selected individuals
   */
  public int[] preparePopSelect(int parentIdx, int oLIndex,
    List<Boolean> indToBeUpdated,
    List<double[]> X, List<Double> popFitX) {
    // Validate input parameters
    if (indToBeUpdated == null) {
      throw new IllegalArgumentException("indToBeUpdated cannot be null");
    }
    if (X == null || popFitX == null) {
      throw new IllegalArgumentException("Output lists cannot be null");
    }
    if (oLIndex < 0 || oLIndex >= oL.length) {
      throw new IllegalArgumentException("oLIndex out of bounds: " + oLIndex);
    }

    // Deep copy current population
    List<double[]> pop = state.getPop();
    if (pop == null) {
      throw new IllegalStateException("state.getPop() returned null");
    }

    X.clear();
    X.addAll(deepCopyPopulation(pop, D));

    // Copy fitness
    List<Double> popFit = state.getPopFit();
    if (popFit == null) {
      throw new IllegalStateException("state.getPopFit() returned null");
    }
    popFitX.clear();
    popFitX.addAll(popFit);

    // Generate indices based on operation level
    int[] selectIndices;

    List<Integer> indicesList = new java.util.ArrayList<>();
    for (int i = 0; i < indToBeUpdated.size(); i++) {
      if (indToBeUpdated.get(i)) {
        indicesList.add(i);
      }
    }
    selectIndices = indicesList.stream().mapToInt(Integer::intValue).toArray();

    return selectIndices;
  }

  /**
   * Prepare the population selected for OA1
   */
  public void preparePopSelect1() {
    List<double[]> X1 = state.getX1();
    List<Double> popFit1 = state.getPopFit1();
    X1.clear();
    popFit1.clear();
    int[] selectIndices = preparePopSelect(0, 0, state.getIndMask1(), X1, popFit1);
    state.setSelectIndices1(selectIndices);
  }

  /**
   * Prepare the population selected by OA2
   */
  public void preparePopSelect2() {
    List<double[]> X2 = state.getX2();
    List<Double> popFit2 = state.getPopFit2();
    X2.clear();
    popFit2.clear();
    int[] selectIndices = preparePopSelect(1, 1, state.getIndMask2(), X2, popFit2);
    state.setSelectIndices2(selectIndices);
  }

  /**
   * Handling Parent Algorithm Update Results (Unified Method for OA1 and OA2)
   *
   * This method orchestrates the complete update cycle:
   * 1. Get new population from parent algorithm
   * 2. Combine updates with component-level masks
   * 3. Evaluate fitness
   * 4. Update algorithm-specific parameters (SHADE)
   * 5. Perform survivor selection
   * 6. Rebuild pbest tracking
   * 7. Apply population resizing if needed
   *
   * @param parentAlg Parent algorithm index (parent1 or parent2)
   * @param parentNum Parent algorithm number (1 or 2)
   * @param indToBeUpdated List of individuals to be updated
   * @param selectIndices Selected individual indices
   * @param X Current population copy
   * @param popFitX Current fitness copy
   * @param unitMask Component-level update mask
   * @param resUnitWhetherUpdate Negated Component-level update mask
   * @param newPopUpdated Output: updated population
   */
  public void processParentUpdate(
    int parentAlg, int parentNum,
    List<Boolean> indToBeUpdated,
    int[] selectIndices,
    List<double[]> X, List<Double> popFitX,
    List<boolean[]> unitMask, List<boolean[]> resUnitWhetherUpdate,
    List<double[]> newPopUpdated) throws Exception {

    // Validate input parameters
    if (indToBeUpdated == null || selectIndices == null ||
      X == null || popFitX == null || unitMask == null ||
      resUnitWhetherUpdate == null || newPopUpdated == null) {
      throw new IllegalArgumentException("Input parameters cannot be null");
    }
    if (parentNum < 1 || parentNum > 2) {
      throw new IllegalArgumentException("parentNum must be 1 or 2, got: " + parentNum);
    }

    // Step 1: Get updated population from parent algorithm
    newPopUpdated.clear();
    ParentUpdateResult updateResult = newPopulationGetter.getNewPopUpdated(
      state.getPbestSolution(), state.getGbestSolution(),
      parentAlg, indToBeUpdated,
      selectIndices, X, popFitX);
    newPopUpdated.addAll(updateResult.getUpdatedPopulation());

    if (newPopUpdated.isEmpty()) {
      return;
    }

    // Step 2: Combine updates with component-level masks
    int expectedUpdates = ListUtils.countTrue(indToBeUpdated);
    if (newPopUpdated.size() != expectedUpdates) {
      throw new IllegalStateException(
        "processParentUpdate update-count mismatch: expected=" + expectedUpdates + ", actual=" + newPopUpdated.size());
    }
    ListUtils.assignRows(X, newPopUpdated, indToBeUpdated);
    List<double[]> pop = state.getPop();
    X = ListUtils.combineByMask(X, pop, unitMask, resUnitWhetherUpdate);

    // Step 3: Evaluate fitness and update counters
    List<Double> cachedFitness = selectUsableCachedFitness(updateResult, indToBeUpdated, unitMask);
    List<Double> newPopFit;
    if (cachedFitness != null) {
      newPopFit = cachedFitness;
      ListUtils.assignElements(popFitX, newPopFit, indToBeUpdated);
      state.addTotalUpdatedInd(newPopFit.size());
    } else {
      newPopFit = evaluateAndUpdateCounters(X, indToBeUpdated, popFitX, parentNum);
    }

    // Step 4: Update algorithm-specific parameters
    updateSHADEProbabilities(parentAlg, indToBeUpdated, newPopFit);

    // Step 5: Perform survivor selection
    List<Double> popFit = state.getPopFit();
    int survivorStrategy = AlgorithmConstants.getBaseSurvivorSelectStrategy(strSurvive);
    SurvivorSelect.SurvivorSelectResult surviveResult =
    SurvivorSelect.survivorSelect(X, pop, popFitX, popFit, pop.size(), survivorStrategy);
    populationSetter.setPop(surviveResult.pop);
    populationSetter.setPopFit(surviveResult.popFit);
    if (parentAlg == AlgorithmConstants.ALGORITHM_INDEX_ACO) {
      state.getACOWorkspace().invalidateDistributionCache();
    }

    // Step 6: Rebuild pbest tracking
    PbestRebuildResult pbestResult = rebuildPbest(
      pop,
      state.getPbestSolution(),
      state.getPbestFit(),
      state.getPop()
    );
    populationSetter.setPbestSolution(pbestResult.pbestSolution);
    populationSetter.setPbestFit(pbestResult.pbestFit);

    // Step 7: Apply population resizing if needed
    applyPopulationResizing();
  }

  /**
   * Processing OA1 Update Results
   */
  public void processParent1Update(int parent1) throws Exception {
    processParentUpdate(parent1, 1, state.getIndMask1(),
      state.getSelectIndices1(), state.getX1(), state.getPopFit1(),
      state.getUnitMask1(), state.getResUnitMask1(), state.getNewPopUpdated1());
  }

  /**
   * Processing OA2 Update Results
   */
  public void processParent2Update(int parent2) throws Exception {
    processParentUpdate(parent2, 2, state.getIndMask2(),
      state.getSelectIndices2(), state.getX2(), state.getPopFit2(),
      state.getUnitMask2(), state.getResUnitMask2(), state.getNewPopUpdated2());
  }

  // ==========================================================
  // Fast path for non-POPULATION level updates (cnt < N)
  // ==========================================================

  /**
   * Compute select indices from indToBeUpdated mask without deep-copying the population.
   * Reuses the index computation logic from preparePopSelect (lines 469-503).
   *
   * @param oLIndex Operation level index (0 or 1)
   * @param indToBeUpdated Individual masks that need updating
   * @return The indices array for selected individuals
   */
  private int[] computeSelectIndices(int oLIndex, List<Boolean> indToBeUpdated) {
    List<Integer> indicesList = new java.util.ArrayList<>();
    for (int i = 0; i < indToBeUpdated.size(); i++) {
      if (indToBeUpdated.get(i)) {
        indicesList.add(i);
      }
    }
    return indicesList.stream().mapToInt(Integer::intValue).toArray();
  }

  /**
   * Fast path for non-POPULATION level updates.
   *
   * When cnt < N (i.e., not a full population update), this method:
   * 1. Skips deep copy - passes original pop directly to read-only parent strategies
   * 2. Only applies component mask to mutated individuals (O(cnt*D) instead of O(N*D))
   * 3. Only evaluates mutated individuals (O(cnt))
   * 4. Applies the configured G5 survivor policy on the updated subset
   * 5. Only updates pbest for changed individuals
   *
   * @param parentAlg Parent algorithm index
   * @param parentNum Parent algorithm number (1 or 2)
   * @param indToBeUpdated Mask of individuals to update
   * @param unitMask Component-level update mask
   * @param resUnitWhetherUpdate Negated component-level update mask
   */
  private void processParentUpdateFastPath(
    int parentAlg, int parentNum,
    List<Boolean> indToBeUpdated,
    List<boolean[]> unitMask, List<boolean[]> resUnitWhetherUpdate) throws Exception {

    List<double[]> pop = state.getPop();
    List<Double> popFit = state.getPopFit();
    int[] selectIndices = computeSelectIndices(parentNum - 1, indToBeUpdated);

    // 1. Pass original pop directly to strategy (read-only, no deep copy needed)
    ParentUpdateResult updateResult = newPopulationGetter.getNewPopUpdated(
      state.getPbestSolution(), state.getGbestSolution(),
      parentAlg, indToBeUpdated, selectIndices, pop, popFit);
    List<double[]> mutated = updateResult.getUpdatedPopulation();
    if (mutated.isEmpty()) return;
    List<Double> cachedFitness = selectUsableCachedFitness(updateResult, indToBeUpdated, unitMask);

    // 2. Apply component mask only to mutated individuals + collect for evaluation
    int updateIdx = 0;
    int fitnessIdx = 0;
    List<double[]> toEval = new ArrayList<>(mutated.size());
    List<Double> usableCachedFitness = cachedFitness == null
      ? null
      : new ArrayList<>(mutated.size());
    List<Integer> positions = new ArrayList<>(mutated.size());

    for (int i = 0; i < indToBeUpdated.size(); i++) {
      if (!indToBeUpdated.get(i)) continue;
      double[] candidate = mutated.get(updateIdx++);
      boolean[] mask = unitMask.get(i);
      double[] original = pop.get(i);
      // Apply component mask: keep original values where mask is false
      for (int j = 0; j < D; j++) {
        if (!mask[j]) candidate[j] = original[j];
      }
      toEval.add(copyCandidateForIsolation(parentAlg, candidate));
      if (usableCachedFitness != null) {
        usableCachedFitness.add(cachedFitness.get(fitnessIdx++));
      }
      positions.add(i);
    }

    // 3. Evaluate fitness only when the parent did not already evaluate the full candidate.
    List<Double> newFits = evaluateOrReuseFitness(toEval, usableCachedFitness, parentNum);

    // 4. Update SHADE probabilities before survivor selection modifies popFit
    updateSHADEProbabilities(parentAlg, indToBeUpdated, newFits);

    // 5. Apply configured G5 survivor policy to the updated subset.
    applyPartialSurvivorSelection(parentAlg, toEval, newFits, positions);

    // 6. Apply population resizing if needed
    applyPopulationResizing();
  }

  private void applyPartialSurvivorSelection(
    int parentAlg,
    List<double[]> candidates,
    List<Double> candidateFits,
    List<Integer> positions) {
    if (candidates.isEmpty()) {
      return;
    }

    List<double[]> pop = state.getPop();
    List<Double> popFit = state.getPopFit();
    List<double[]> parentSubset = new ArrayList<>(positions.size());
    List<Double> parentFitSubset = new ArrayList<>(positions.size());
    double[][] candidatePbestSolutions = new double[candidates.size()][];
    double[] candidatePbestFits = new double[candidates.size()];
    for (int k = 0; k < positions.size(); k++) {
      int pos = positions.get(k);
      parentSubset.add(pop.get(pos));
      parentFitSubset.add(popFit.get(pos));

      double[] candidate = candidates.get(k);
      double candidateFit = candidateFits.get(k);
      double[] candidatePbest = candidateFit > state.getPbestFit().get(pos)
        ? candidate
        : state.getPbestSolution().get(pos);
      double candidatePbestFit = Math.max(candidateFit, state.getPbestFit().get(pos));
      candidatePbestSolutions[k] = candidatePbest;
      candidatePbestFits[k] = candidatePbestFit;
    }

    int survivorStrategy = AlgorithmConstants.getBaseSurvivorSelectStrategy(strSurvive);
    SurvivorSelect.SurvivorSelectResult selected = SurvivorSelect.survivorSelect(
      candidates,
      parentSubset,
      candidateFits,
      parentFitSubset,
      positions.size(),
      survivorStrategy);

    double currentGbestFit = state.getGbestFit();
    int gbestCandidatePos = -1;
    double gbestCandidateFit = currentGbestFit;

    for (int k = 0; k < positions.size(); k++) {
      int pos = positions.get(k);
      double[] selectedIndividual = selected.pop.get(k);
      double selectedFit = selected.popFit.get(k);
      int sourceCode = resolveSurvivorSourceCode(selectedIndividual, candidates, parentSubset);
      if (sourceCode == SURVIVOR_SOURCE_PARENT
        && isNoOpParentSelection(selectedIndividual, selectedFit, pop, popFit, pos)) {
        if (selectedFit > gbestCandidateFit) {
          gbestCandidateFit = selectedFit;
          gbestCandidatePos = pos;
        }
        continue;
      }
      pop.set(pos, selectedIndividual);
      popFit.set(pos, selectedFit);
      state.invalidateFitnessCache();
      if (sourceCode == SURVIVOR_SOURCE_OA1
        && parentAlg == AlgorithmConstants.ALGORITHM_INDEX_ACO
        && state.getACOWorkspace().isCachedEliteIndex(pos)) {
        state.getACOWorkspace().invalidateDistributionCache();
      }
      if (sourceCode == SURVIVOR_SOURCE_OA1) {
        state.getPbestSolution().set(pos, Arrays.copyOf(candidatePbestSolutions[k], D));
        state.getPbestFit().set(pos, candidatePbestFits[k]);
      } else if (sourceCode == SURVIVOR_SOURCE_PARENT) {
        // Parent source keeps existing pbest.
      } else if (selectedFit > state.getPbestFit().get(pos)) {
        state.getPbestSolution().set(pos, Arrays.copyOf(selectedIndividual, D));
        state.getPbestFit().set(pos, selectedFit);
      }
      if (selectedFit > gbestCandidateFit) {
        gbestCandidateFit = selectedFit;
        gbestCandidatePos = pos;
      }
    }

    if (gbestCandidatePos >= 0) {
      state.setGbestFit(gbestCandidateFit);
      state.setGbestSolution(Arrays.copyOf(pop.get(gbestCandidatePos), D));
    }
  }

  /**
   * Fast path entry for OA1 (when cnt < N).
   */
  public void processParent1UpdateFastPath(int parent1) throws Exception {
    processParentUpdateFastPath(parent1, 1,
      state.getIndMask1(), state.getUnitMask1(), state.getResUnitMask1());
  }

  /**
   * Batched fast path for OA1 when count is small.
   *
   * Generates {@code batchSize} candidates from the current population state,
   * evaluates them all at once (enabling parallel evaluation), then applies
   * the configured G5 survivor policy to the target-local candidate batch.
   *
   * Trade-off: all candidates are mutations of the same base individual (no
   * sequential dependency between candidates within a batch). This is equivalent
   * to (μ+λ) selection with λ=batchSize, which is a standard EA strategy.
   *
   * @param parentAlg Parent algorithm index
   * @param batchSize Number of candidates to generate and evaluate in parallel
   * @return actual number of evaluations performed
   */
  public int processParent1UpdateFastPathBatched(int parentAlg, int batchSize) throws Exception {
    List<Boolean> indToBeUpdated = state.getIndMask1();
    List<boolean[]> unitMask = state.getUnitMask1();

    List<double[]> pop = state.getPop();
    List<Double> popFit = state.getPopFit();
    int[] selectIndices = computeSelectIndices(0, indToBeUpdated);

    // Find the target position (the individual being mutated)
    int targetPos = -1;
    for (int i = 0; i < indToBeUpdated.size(); i++) {
      if (indToBeUpdated.get(i)) { targetPos = i; break; }
    }
    if (targetPos < 0) return 0;

    boolean[] mask = unitMask.get(targetPos);
    double[] original = pop.get(targetPos);

    // 1. Generate batchSize candidates
    List<double[]> allCandidates = new ArrayList<>(batchSize);
    List<Double> allCachedFits = new ArrayList<>(batchSize);
    boolean allFitnessCached = true;
    for (int b = 0; b < batchSize; b++) {
      ParentUpdateResult updateResult = newPopulationGetter.getNewPopUpdated(
        state.getPbestSolution(), state.getGbestSolution(),
        parentAlg, indToBeUpdated, selectIndices, pop, popFit);
      List<double[]> mutated = updateResult.getUpdatedPopulation();
      if (mutated.isEmpty()) continue;
      double[] candidate = mutated.get(0);
      boolean canReuseFitness = updateResult.hasEvaluatedFitness()
        && maskCoversAllDimensions(mask);
      // Apply component mask
      for (int j = 0; j < D; j++) {
        if (!mask[j]) candidate[j] = original[j];
      }
      allCandidates.add(copyCandidateForIsolation(parentAlg, candidate));
      if (canReuseFitness) {
        allCachedFits.add(updateResult.getEvaluatedFitness().get(0));
      } else {
        allFitnessCached = false;
      }
    }

    if (allCandidates.isEmpty()) return 0;

    // 2. Evaluate all candidates at once unless the parent already evaluated them.
    List<Double> cachedFitness = allFitnessCached && allCachedFits.size() == allCandidates.size()
      ? allCachedFits
      : null;
    int evaluated = cachedFitness == null ? allCandidates.size() : 0;
    List<Double> allFits = evaluateOrReuseFitness(allCandidates, cachedFitness, 1);

    // 3. Apply configured G5 survivor policy to the target-local candidate batch.
    applyBatchedLocalSurvivorSelection(parentAlg, allCandidates, allFits, targetPos);

    applyPopulationResizing();
    return evaluated;
  }

  private void applyBatchedLocalSurvivorSelection(
    int parentAlg,
    List<double[]> candidates,
    List<Double> candidateFits,
    int targetPos) {
    List<double[]> pop = state.getPop();
    List<Double> popFit = state.getPopFit();
    int survivorStrategy = AlgorithmConstants.getBaseSurvivorSelectStrategy(strSurvive);

    if (survivorStrategy == AlgorithmConstants.SURVIVOR_SELECT_STRATEGY_PAIRWISE_GREEDY) {
      applyBatchedPairwiseGreedySurvivor(parentAlg, candidates, candidateFits, targetPos);
      return;
    }

    double[][] candidatePbestSolutions = new double[candidates.size()][];
    double[] candidatePbestFits = new double[candidates.size()];
    for (int i = 0; i < candidates.size(); i++) {
      double candidateFit = candidateFits.get(i);
      double[] candidate = candidates.get(i);
      double[] candidatePbest = candidateFit > state.getPbestFit().get(targetPos)
        ? candidate
        : state.getPbestSolution().get(targetPos);
      double candidatePbestFit = Math.max(candidateFit, state.getPbestFit().get(targetPos));
      candidatePbestSolutions[i] = candidatePbest;
      candidatePbestFits[i] = candidatePbestFit;
    }

    SurvivorSelect.SurvivorSelectResult selected = SurvivorSelect.survivorSelect(
      candidates,
      List.of(pop.get(targetPos)),
      candidateFits,
      List.of(popFit.get(targetPos)),
      1,
      survivorStrategy);

    double selectedFit = selected.popFit.get(0);
    double[] selectedIndividual = selected.pop.get(0);
    List<double[]> parentCandidate = List.of(pop.get(targetPos));
    int sourceCode = resolveSurvivorSourceCode(selectedIndividual, candidates, parentCandidate);
    if (sourceCode == SURVIVOR_SOURCE_PARENT
      && isNoOpParentSelection(selectedIndividual, selectedFit, pop, popFit, targetPos)) {
      if (selectedFit > state.getGbestFit()) {
        state.setGbestFit(selectedFit);
        state.setGbestSolution(Arrays.copyOf(selectedIndividual, D));
      }
      return;
    }
    int candidateIndex = sourceCode == SURVIVOR_SOURCE_OA1
      ? resolveSurvivorIndex(selectedIndividual, candidates)
      : -1;
    pop.set(targetPos, selectedIndividual);
    popFit.set(targetPos, selectedFit);
    state.invalidateFitnessCache();
    if (sourceCode == SURVIVOR_SOURCE_OA1
      && parentAlg == AlgorithmConstants.ALGORITHM_INDEX_ACO
      && state.getACOWorkspace().isCachedEliteIndex(targetPos)) {
      state.getACOWorkspace().invalidateDistributionCache();
    }
    if (candidateIndex >= 0) {
      state.getPbestSolution().set(targetPos, Arrays.copyOf(candidatePbestSolutions[candidateIndex], D));
      state.getPbestFit().set(targetPos, candidatePbestFits[candidateIndex]);
    } else if (sourceCode == SURVIVOR_SOURCE_PARENT) {
      // Parent source keeps existing pbest.
    } else if (selectedFit > state.getPbestFit().get(targetPos)) {
      state.getPbestSolution().set(targetPos, Arrays.copyOf(selectedIndividual, D));
      state.getPbestFit().set(targetPos, selectedFit);
    }
    if (selectedFit > state.getGbestFit()) {
      state.setGbestFit(selectedFit);
      state.setGbestSolution(Arrays.copyOf(selectedIndividual, D));
    }
  }

  private void applyBatchedPairwiseGreedySurvivor(
    int parentAlg,
    List<double[]> candidates,
    List<Double> candidateFits,
    int targetPos) {
    List<double[]> pop = state.getPop();
    List<Double> popFit = state.getPopFit();
    double bestFit = popFit.get(targetPos);
    int bestCandidateIndex = -1;

    for (int i = 0; i < candidateFits.size(); i++) {
      if (candidateFits.get(i) > bestFit) {
        bestFit = candidateFits.get(i);
        bestCandidateIndex = i;
      }
    }

    if (bestCandidateIndex < 0) {
      return;
    }

    double[] winner = candidates.get(bestCandidateIndex);
    pop.set(targetPos, winner);
    popFit.set(targetPos, bestFit);
    state.invalidateFitnessCache();
    if (parentAlg == AlgorithmConstants.ALGORITHM_INDEX_ACO
      && state.getACOWorkspace().isCachedEliteIndex(targetPos)) {
      state.getACOWorkspace().invalidateDistributionCache();
    }
    if (bestFit > state.getPbestFit().get(targetPos)) {
      state.getPbestSolution().set(targetPos, Arrays.copyOf(winner, D));
      state.getPbestFit().set(targetPos, bestFit);
    }
    if (bestFit > state.getGbestFit()) {
      state.setGbestFit(bestFit);
      state.setGbestSolution(Arrays.copyOf(winner, D));
    }
  }

  private boolean isNoOpParentSelection(
    double[] selectedIndividual,
    double selectedFit,
    List<double[]> pop,
    List<Double> popFit,
    int position) {
    return selectedIndividual == pop.get(position)
      && Double.compare(selectedFit, popFit.get(position)) == 0;
  }

  private double[] copyCandidateForIsolation(int parentAlg, double[] candidate) {
    return parentAlg == AlgorithmConstants.ALGORITHM_INDEX_ACO
      ? candidate
      : Arrays.copyOf(candidate, D);
  }

  private int resolveSurvivorSourceCode(
    double[] selectedIndividual,
    List<double[]> oa1Candidates,
    List<double[]> parentCandidates
  ) {
    if (resolveSurvivorIndex(selectedIndividual, oa1Candidates) >= 0) {
      return SURVIVOR_SOURCE_OA1;
    }
    if (resolveSurvivorIndex(selectedIndividual, parentCandidates) >= 0) {
      return SURVIVOR_SOURCE_PARENT;
    }
    return SURVIVOR_SOURCE_OA2;
  }

  private int resolveSurvivorIndex(double[] selectedIndividual, List<double[]> candidates) {
    for (int i = 0; i < candidates.size(); i++) {
      if (selectedIndividual == candidates.get(i)) {
        return i;
      }
    }
    return -1;
  }

  /** Fast path for parent 2 update. */
  public void processParent2UpdateFastPath(int parent2) throws Exception {
    processParentUpdateFastPath(parent2, 2,
      state.getIndMask2(), state.getUnitMask2(), state.getResUnitMask2());
  }

  /**
   * Handling Parent Algorithm Update Results in Parallel Mode (Unified Method for OA1 and OA2)
   *
   * This is a simplified version for parallel mode that:
   * 1. Combines pre-generated offspring with component masks
   * 2. Evaluates fitness
   * 3. Updates SHADE probabilities if needed
   */
  public void processParallelUpdate(
    int parentAlg, ParentUpdateResult updateResult,
    int parentNum,
    List<Boolean> indToBeUpdated,
    List<double[]> X, List<Double> popFitX,
    List<boolean[]> unitMask, List<boolean[]> resUnitWhetherUpdate) throws Exception {

    // Validate input parameters
    List<double[]> newPop = updateResult == null ? null : updateResult.getUpdatedPopulation();
    if (newPop == null || indToBeUpdated == null || X == null ||
      popFitX == null || unitMask == null || resUnitWhetherUpdate == null) {
      throw new IllegalArgumentException("Input parameters cannot be null");
    }

    // Combine updates with component masks
    ListUtils.assignRows(X, newPop, indToBeUpdated);
    List<double[]> pop = state.getPop();
    X = ListUtils.combineByMask(X, pop, unitMask, resUnitWhetherUpdate);

    // Evaluate fitness only when the parent did not already evaluate the full candidate.
    List<Double> cachedFitness = selectUsableCachedFitness(updateResult, indToBeUpdated, unitMask);
    List<Double> newPopFit;
    if (cachedFitness != null) {
      newPopFit = cachedFitness;
      ListUtils.assignElements(popFitX, newPopFit, indToBeUpdated);
      state.addTotalUpdatedInd(newPopFit.size());
    } else {
      newPopFit = evaluateAndUpdateCounters(X, indToBeUpdated, popFitX, parentNum);
    }

    // Update SHADE probabilities if applicable
    updateSHADEProbabilities(parentAlg, indToBeUpdated, newPopFit);
  }

  /**
   * Handling OA1 Parallel Update Results
   */
  public void processParallel1(int parent1, ParentUpdateResult newPop1) throws Exception {
    processParallelUpdate(parent1, newPop1, 1, state.getIndMask1(),
      state.getX1(), state.getPopFit1(),
      state.getUnitMask1(), state.getResUnitMask1());
  }

  /**
   * Handling OA2 Parallel Update Results
   */
  public void processParallel2(int parent2, ParentUpdateResult newPop2) throws Exception {
    processParallelUpdate(parent2, newPop2, 2, state.getIndMask2(),
      state.getX2(), state.getPopFit2(),
      state.getUnitMask2(), state.getResUnitMask2());
  }

  /**
   * Survivor Combination in Parallel Mode
   */
  public void combineSurvivorsParallel() {
    List<double[]> popPar = null;
    List<Double> popFitPar = null;
    int survivorStrategy = AlgorithmConstants.getBaseSurvivorSelectStrategy(strSurvive);

    int[] oLReal = state.getOLReal();
    List<double[]> X1 = state.getX1();
    List<double[]> X2 = state.getX2();
    List<Double> popFit1 = state.getPopFit1();
    List<Double> popFit2 = state.getPopFit2();
    List<boolean[]> unitWhetherUpdate1 = state.getUnitMask1();
    List<boolean[]> unitWhetherUpdate2 = state.getUnitMask2();
    int currentNPop = (state.getPop() != null) ? state.getPop().size() : 0;

    if (oLReal[0] == oLReal[1]) {
      switch (state.getHybLevel()) {
        case AlgorithmConstants.HYB_LEVEL_POPULATION:
          SurvivorSelect.SurvivorSelectResult result1 =
          SurvivorSelect.survivorSelectElitistBestN(
            X1, X2, popFit1, popFit2, currentNPop);
          popPar = result1.pop;
          popFitPar = result1.popFit;
          break;

        case AlgorithmConstants.HYB_LEVEL_SUBPOPULATION:
          popPar = ListUtils.combineByMask(X1, X2, unitWhetherUpdate1, unitWhetherUpdate2);

          popFitPar = new ArrayList<>(currentNPop);
          for (int i = 0; i < currentNPop; i++) {
            double fit1 = popFit1.get(i);
            double fit2 = popFit2.get(i);
            boolean fromX1 = unitWhetherUpdate1.get(i)[0];
            popFitPar.add(fromX1 ? fit1 : fit2);
        }
        break;

        case AlgorithmConstants.HYB_LEVEL_INDIVIDUAL:
          SurvivorSelect.SurvivorSelectResult result3 =
          SurvivorSelect.survivorSelectPairwiseGreedy(
            X1, X2, popFit1, popFit2);
          popPar = result3.pop;
          popFitPar = result3.popFit;
          break;

        default:
          throw new IllegalStateException(
            "combineSurvivorsParallel: unexpected hybLevel=" + state.getHybLevel()
            + ", oLReal0=" + oLReal[0]
            + ", oLReal1=" + oLReal[1]);
      }
    } else {
      // Cross-level: offspring1 vs offspring2 first
      SurvivorSelect.SurvivorSelectResult result =
      SurvivorSelect.survivorSelect(
        X1, X2, popFit1, popFit2, currentNPop, survivorStrategy);
      popPar = result.pop;
      popFitPar = result.popFit;
    }


    List<double[]> pop = state.getPop();
    List<Double> popFit = state.getPopFit();
    SurvivorSelect.SurvivorSelectResult finalResult =
    SurvivorSelect.survivorSelect(
      popPar, pop, popFitPar, popFit, currentNPop, survivorStrategy);
    populationSetter.setPop(finalResult.pop);
    populationSetter.setPopFit(finalResult.popFit);

    if (state.getFlagPar()[AlgorithmIndex.L_SHADE.getIndex()] == 1) {
      int currentPopSize = (state.getPop() != null) ? state.getPop().size() : currentNPop;
      PopulationResizer.ResizeResult resizeResult = populationResizer.resizePopulation(currentPopSize);
      if (resizeResult.needsParameterReset()) {
        populationSetter.reSetParameters(D, resizeResult.getNewNPop());
      }
      populationSetter.setPop(resizeResult.getResizeResult().pop);
      populationSetter.setPopFit(resizeResult.getResizeResult().popFit);
      populationSetter.setPbestSolution(resizeResult.getResizeResult().pbestS);
      populationSetter.setPbestFit(resizeResult.getResizeResult().pbestF);
    }
  }

}
