package method.rkhma.legacy.algorithm.abc;

import method.rkhma.legacy.common.AlgorithmConstants;
import method.rkhma.legacy.common.AlgorithmParameterValidator;
import method.rkhma.legacy.common.BoundaryHandler;
import method.rkhma.legacy.core.config.ConfigValidatable;
import method.rkhma.legacy.algorithm.workspace.ABCWorkspace;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import method.rkhma.legacy.model.Satellite;
import method.rkhma.legacy.model.Task;
import method.rkhma.legacy.operator.insertion.InsertionMode;
import method.rkhma.legacy.utils.PlanUtils;

public class ABC {

  // =====================================================
  // ABC Algorithm Constants
  // =====================================================

  /** ABC Default No Improvement Limit */
  public static final int ABC_DEFAULT_NO_IMPROVEMENT_LIMIT = 100;

  /** ABC phi Parameter Scale Factor (for generating [-1, 1] random numbers) */
  public static final double ABC_PHI_SCALE = 2.0;

  /** ABC phi Parameter Offset */
  public static final double ABC_PHI_OFFSET = 1.0;

  /**
   * ABC parameter configuration class, centralizing the management of algorithm hyperparameters.
   */
  public static class ABCConfig implements ConfigValidatable {
    /** Stagnation counter threshold; exceeding this value triggers the scout bee phase. */
    public final int numNoImp;
    /** Upper bound of the decision variables */
    public final double upperBound;

    /** Lower bound of the decision variables */
    public final double lowerBound;
    /** Scaling factor for the ABC phi parameter (used to generate random numbers in the range [-1, 1]) */
    public final double phiScale;
    /** Offset of the ABC phi parameter */
    public final double phiOffset;

    public ABCConfig(int numNoImp, double upperBound, double lowerBound, double phiScale, double phiOffset) {
      this.numNoImp = numNoImp;
      this.upperBound = upperBound;
      this.lowerBound = lowerBound;
      this.phiScale = phiScale;
      this.phiOffset = phiOffset;
      ConfigValidatable.ValidationResult result = validate();
      try {
        result.throwIfInvalid();
      } catch (ConfigValidatable.ConfigValidationException e) {
        throw new IllegalArgumentException(e.getMessage(), e);
      }
    }

    @Override
    public ConfigValidatable.ValidationResult validate() {
      ConfigValidatable.ValidationResultBuilder builder = new ConfigValidatable.ValidationResultBuilder();

      builder.checkPositiveInt("numNoImp", numNoImp);
      builder.checkPositive("phiScale", phiScale);

      if (lowerBound >= upperBound) {
        builder.addError(String.format(
          "lowerBound must be less than upperBound, got lowerBound=%.4f, upperBound=%.4f",
          lowerBound, upperBound));
      }

      return builder.build();
    }

    /**
     * Provide a set of default parameters for convenient direct use.
     */
    public static ABCConfig defaultConfig() {
      return new ABCConfig(
        ABC_DEFAULT_NO_IMPROVEMENT_LIMIT,
        AlgorithmConstants.DEFAULT_BOUND,
        -AlgorithmConstants.DEFAULT_BOUND,
        ABC_PHI_SCALE,
        ABC_PHI_OFFSET
      );
    }
  }

  /**
   * Encapsulation class for one iteration result of ABC
   */
  public static class ABCIterationResult {
    public List<double[]> pop; // Updated population (complete)
    public List<Double> popFit;   // Updated fitness values (complete)
    public List<Double> limit;  // Updated stagnation counter for each solution (complete)
    public int scoutEvaluations; // Scout-phase evaluations consumed (for budget accounting)

    public ABCIterationResult(List<double[]> pop, List<Double> popFit, List<Double> limit) {
      this(pop, popFit, limit, 0);
    }

    public ABCIterationResult(List<double[]> pop, List<Double> popFit, List<Double> limit, int scoutEvaluations) {
      this.pop = pop;
      this.popFit = popFit;
      this.limit = limit;
      this.scoutEvaluations = scoutEvaluations;
    }
  }

  @FunctionalInterface
  public interface ObjectiveEvaluator {
    List<Double> evaluate(List<double[]> population) throws Exception;
  }

  @FunctionalInterface
  public interface ObjectiveArrayEvaluator {
    void evaluateInto(List<double[]> population, double[] out) throws Exception;
  }

  /**
   * Perform one iteration of ABC algorithm using configuration object.
   *
   * @param pop Full population
   * @param selectIndices Indices of individuals to operate on
   * @param popFit Full fitness values
   * @param config ABC configuration
   * @param limit Full limit array (stagnation counters)
   * @param satellite Satellite for evaluation
   * @param allTasks All tasks
   * @param rand Random generator
   * @return Updated population, fitness, and limit
   */
  public static ABCIterationResult run(
    List<double[]> pop,
    int[] selectIndices,
    List<Double> popFit,
    ABCConfig config,
    List<Double> limit,
    Satellite satellite,
    List<Task> allTasks,
    Random rand) throws Exception {

    List<Task> referenceTasks = resolveReferenceTasks(satellite, allTasks);
    ObjectiveEvaluator evaluator =
      population -> PlanUtils.evaluatePop(population, satellite, referenceTasks, InsertionMode.GREEDY);
    return run(pop, selectIndices, popFit, config, limit, satellite, allTasks, evaluator, rand);
  }

  public static ABCIterationResult run(
    List<double[]> pop,
    int[] selectIndices,
    List<Double> popFit,
    ABCConfig config,
    List<Double> limit,
    Satellite satellite,
    List<Task> allTasks,
    Random rand,
    ABCWorkspace workspace) throws Exception {

    List<Task> referenceTasks = resolveReferenceTasks(satellite, allTasks);
    ObjectiveEvaluator evaluator =
      population -> PlanUtils.evaluatePop(population, satellite, referenceTasks, InsertionMode.GREEDY);
    return run(pop, selectIndices, popFit, config, limit, satellite, allTasks, evaluator, rand, workspace);
  }

  public static ABCIterationResult run(
    List<double[]> pop,
    int[] selectIndices,
    List<Double> popFit,
    ABCConfig config,
    List<Double> limit,
    Satellite satellite,
    List<Task> allTasks,
    ObjectiveEvaluator evaluator,
    Random rand) throws Exception {
    return run(pop, selectIndices, popFit, config, limit, satellite, allTasks, evaluator, rand, null);
  }

  public static ABCIterationResult run(
    List<double[]> pop,
    int[] selectIndices,
    List<Double> popFit,
    ABCConfig config,
    List<Double> limit,
    Satellite satellite,
    List<Task> allTasks,
    ObjectiveEvaluator evaluator,
    Random rand,
    ABCWorkspace workspace) throws Exception {

    ObjectiveArrayEvaluator arrayEvaluator = evaluator == null
      ? null
      : (population, out) -> copyListToArray(evaluator.evaluate(population), out);
    return run(pop, selectIndices, popFit, config, limit, satellite, allTasks, evaluator, arrayEvaluator, rand, workspace);
  }

  public static ABCIterationResult run(
    List<double[]> pop,
    int[] selectIndices,
    List<Double> popFit,
    ABCConfig config,
    List<Double> limit,
    Satellite satellite,
    List<Task> allTasks,
    ObjectiveEvaluator evaluator,
    ObjectiveArrayEvaluator arrayEvaluator,
    Random rand,
    ABCWorkspace workspace) throws Exception {

    // ═══════════════════════════════════════════════════════
    // 0. Parameter checks
    // ═══════════════════════════════════════════════════════

    // Use common validator for standard checks
    AlgorithmParameterValidator.validateCommonParameters(pop, selectIndices, popFit, config, rand);

    // ABC-specific validation: limit array
    if (limit == null) {
      throw new IllegalArgumentException("Limit cannot be null");
    }
    if (limit.size() != pop.size()) {
      throw new IllegalArgumentException(
        String.format("Limit size (%d) does not match population size (%d)",
        limit.size(), pop.size()));
    }

    int nPop = pop.size();
    int nSelect = selectIndices.length;
    int D = pop.get(0).length;

    // Cache redundant computations: search space range (calculate once outside the loop)
    double searchRange = config.upperBound - config.lowerBound;

    if (workspace != null) {
      workspace.ensureCapacity(Math.max(nPop, nSelect), D);
    }

    // Make a working copy of references and scalar state. Individual rows are cloned only when accepted.
    List<double[]> qDecCopy = new ArrayList<>(nPop);
    for (int i = 0; i < nPop; i++) {
      qDecCopy.add(pop.get(i));
    }
    List<Double> qObjCopy = new ArrayList<>(popFit);
    List<Double> limitCopy = new ArrayList<>(limit);

    // ═══════════════════════════════════════════════════════
    // 1. Employed Bee Phase
    // ═══════════════════════════════════════════════════════

    List<double[]> qDecEmployed = workspace != null
      ? fixedArrayView(workspace.employedBuffer, nSelect)
      : new ArrayList<>(nSelect);

    for (int i = 0; i < nSelect; i++) {
      int targetIdx = selectIndices[i];

      // Randomly choose another individual (Ensure that k != targetIdx)
      int k = (targetIdx + rand.nextInt(nPop - 1) + 1) % nPop;

      double[] newSolution = workspace != null ? workspace.employedBuffer[i] : null;
      newSolution = generateNewSolution(
        qDecCopy.get(targetIdx), qDecCopy.get(k), D, config, rand, newSolution);
      if (workspace == null) {
        qDecEmployed.add(newSolution);
      }
    }

    // ⭐⭐⭐ Evaluate new solutions (key step)
    double[] qObjEmployed = workspace != null ? workspace.employedFitness : new double[nSelect];
    evaluateInto(qDecEmployed, qObjEmployed, evaluator, arrayEvaluator);

    // Greedy selection
    for (int i = 0; i < nSelect; i++) {
      int targetIdx = selectIndices[i];
      if (qObjEmployed[i] > qObjCopy.get(targetIdx)) {
        qDecCopy.set(targetIdx, qDecEmployed.get(i).clone());
        qObjCopy.set(targetIdx, qObjEmployed[i]);
        limitCopy.set(targetIdx, 0.0);
      } else {
        limitCopy.set(targetIdx, limitCopy.get(targetIdx) + 1);
      }
    }

    // ═══════════════════════════════════════════════════════
    // 2. Onlooker Bee Phase
    // ═══════════════════════════════════════════════════════

    // Compute selection probabilities based on selected individuals only
    double[] selectedFitness = workspace != null ? workspace.selectedFitness : new double[nSelect];
    for (int i = 0; i < nSelect; i++) {
      selectedFitness[i] = qObjCopy.get(selectIndices[i]);
    }
    double[] probabilities = workspace != null ? workspace.probabilities : new double[nSelect];
    calculateProbabilities(selectedFitness, probabilities, nSelect);

    // Roulette-wheel selection (Optimized using binary search)
    List<Integer> selectIdxForOnlook = new ArrayList<>(nSelect);
    for (int i = 0; i < nSelect; i++) {
      double r = rand.nextDouble();
      int selectedIdx = binarySearchRoulette(probabilities, nSelect, r);
      selectIdxForOnlook.add(selectIndices[selectedIdx]); // Map back to global index
    }

    // Generate new solutions based on selected individuals
    List<double[]> qDecOnlooker = workspace != null
      ? fixedArrayView(workspace.onlookerBuffer, nSelect)
      : new ArrayList<>(nSelect);
    for (int i = 0; i < nSelect; i++) {
      int targetIdx = selectIdxForOnlook.get(i);

      // Randomly choose another individual (Ensure that k != targetIdx)
      int k = (targetIdx + rand.nextInt(nPop - 1) + 1) % nPop;

      double[] newSolution = workspace != null ? workspace.onlookerBuffer[i] : null;
      newSolution = generateNewSolution(
        qDecCopy.get(targetIdx), qDecCopy.get(k), D, config, rand, newSolution);
      if (workspace == null) {
        qDecOnlooker.add(newSolution);
      }
    }

    // ⭐⭐⭐ Evaluate new solutions (key step)
    double[] qObjOnlooker = workspace != null ? workspace.onlookerFitness : new double[nSelect];
    evaluateInto(qDecOnlooker, qObjOnlooker, evaluator, arrayEvaluator);

    // Greedy selection
    for (int i = 0; i < nSelect; i++) {
      int targetIdx = selectIdxForOnlook.get(i);
      if (qObjOnlooker[i] > qObjCopy.get(targetIdx)) {
        qDecCopy.set(targetIdx, qDecOnlooker.get(i).clone());
        qObjCopy.set(targetIdx, qObjOnlooker[i]);
        limitCopy.set(targetIdx, 0.0);
      } else {
        limitCopy.set(targetIdx, limitCopy.get(targetIdx) + 1);
      }
    }

    // ═══════════════════════════════════════════════════════
    // 3. Scout Bee Phase
    // ═══════════════════════════════════════════════════════

    List<Integer> scoutTargets = new ArrayList<>();
    List<double[]> scoutPop = workspace != null
      ? fixedArrayView(workspace.scoutBuffer, 0)
      : new ArrayList<>();
    int scoutCount = 0;
    for (int i = 0; i < nSelect; i++) {
      int targetIdx = selectIndices[i];
      if (limitCopy.get(targetIdx) > config.numNoImp) {
        double[] newSolution = workspace != null ? workspace.scoutBuffer[scoutCount] : new double[D];
        for (int j = 0; j < D; j++) {
          newSolution[j] = config.lowerBound + rand.nextDouble() * searchRange;
        }
        scoutTargets.add(targetIdx);
        if (workspace == null) {
          scoutPop.add(newSolution);
        }
        scoutCount++;
      }
    }

    if (scoutCount > 0) {
      List<double[]> scoutEvalPop = workspace != null
        ? fixedArrayView(workspace.scoutBuffer, scoutCount)
        : scoutPop;
      double[] scoutFit = workspace != null ? workspace.scoutFitness : new double[scoutCount];
      evaluateInto(scoutEvalPop, scoutFit, evaluator, arrayEvaluator);
      for (int i = 0; i < scoutCount; i++) {
        int targetIdx = scoutTargets.get(i);
        qDecCopy.set(targetIdx, scoutEvalPop.get(i).clone());
        qObjCopy.set(targetIdx, scoutFit[i]);
        limitCopy.set(targetIdx, 0.0);
      }
    }

    // ═══════════════════════════════════════════════════════
    // 4. Return results
    // ═══════════════════════════════════════════════════════

    return new ABCIterationResult(qDecCopy, qObjCopy, limitCopy, scoutCount);
  }

  private static void evaluateInto(
    List<double[]> population,
    double[] out,
    ObjectiveEvaluator evaluator,
    ObjectiveArrayEvaluator arrayEvaluator) throws Exception {
    if (arrayEvaluator != null) {
      arrayEvaluator.evaluateInto(population, out);
      return;
    }
    copyListToArray(evaluator.evaluate(population), out);
  }

  private static List<Task> resolveReferenceTasks(Satellite satellite, List<Task> allTasks) {
    if (allTasks != null && !allTasks.isEmpty()) {
      return allTasks;
    }
    return satellite.getAssignedTasks();
  }

  private static void copyListToArray(List<Double> source, double[] out) {
    for (int i = 0; i < source.size(); i++) {
      out[i] = source.get(i);
    }
  }

  private static void calculateProbabilities(double[] popFitSelect, double[] probabilities, int nSelect) {
    if (nSelect <= 0) {
      return;
    }
    double maxObj = Double.NEGATIVE_INFINITY;
    double minObj = Double.POSITIVE_INFINITY;
    for (int i = 0; i < nSelect; i++) {
      double val = popFitSelect[i];
      if (val > maxObj) {
        maxObj = val;
      }
      if (val < minObj) {
        minObj = val;
      }
    }
    double scale = Math.max(1.0e-9, maxObj - minObj);

    double fitnessSum = 0;
    for (int i = 0; i < nSelect; i++) {
      double exponent = Math.max(-60.0, Math.min(0.0, (popFitSelect[i] - maxObj) / scale));
      double f = Math.exp(exponent);
      probabilities[i] = f;
      fitnessSum += f;
    }

    double cumulativeProb = 0;
    for (int i = 0; i < nSelect; i++) {
      cumulativeProb += probabilities[i] / fitnessSum;
      probabilities[i] = cumulativeProb;
    }
    probabilities[nSelect - 1] = 1.0;
  }

  private static int binarySearchRoulette(double[] cumulativeProb, int n, double randomValue) {
    int left = 0;
    int right = n - 1;
    while (left < right) {
      int mid = (left + right) >>> 1;
      if (randomValue <= cumulativeProb[mid]) {
        right = mid;
      } else {
        left = mid + 1;
      }
    }
    return left;
  }

  private static double[] generateNewSolution(
    double[] curSolution, double[] otherSolution, int D,
    ABCConfig config, Random rand) {
    return generateNewSolution(curSolution, otherSolution, D, config, rand, null);
  }

  private static double[] generateNewSolution(
    double[] curSolution, double[] otherSolution, int D,
    ABCConfig config, Random rand, double[] target) {
    double[] newSolution = target != null ? target : new double[D];
    for (int j = 0; j < D; j++) {
      double phi = rand.nextDouble() * config.phiScale - config.phiOffset;
      newSolution[j] = curSolution[j] + phi * (curSolution[j] - otherSolution[j]);
      newSolution[j] = BoundaryHandler.clip(newSolution[j], config.lowerBound, config.upperBound);
    }
    return newSolution;
  }

  private static List<double[]> fixedArrayView(double[][] rows, int size) {
    return new AbstractList<double[]>() {
      @Override
      public double[] get(int index) {
        if (index < 0 || index >= size) {
          throw new IndexOutOfBoundsException("index=" + index + ", size=" + size);
        }
        return rows[index];
      }

      @Override
      public int size() {
        return size;
      }
    };
  }
}
