package method.rkhma.legacy.algorithm.alns;

import method.rkhma.legacy.common.AlgorithmConstants;
import method.rkhma.legacy.core.config.ConfigValidatable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import method.rkhma.legacy.model.Satellite;
import method.rkhma.legacy.model.SchedulingPlan;
import method.rkhma.legacy.model.Task;
import method.rkhma.legacy.operator.insertion.InsertionMode;
import method.rkhma.legacy.utils.PlanUtils;

/**
 * Adaptive large neighborhood search over random-key task sequences.
 *
 * <p>This implementation keeps ALNS inside the single-satellite HybCec parent
 * algorithm contract. It destroys and repairs decoded task orders, then encodes
 * the best accepted order back into the existing continuous chromosome format.</p>
 */
public final class ALNS {

  private static final double INITIAL_OPERATOR_SCORE = 1.0;
  private static final double MIN_TEMPERATURE = 1.0e-9;
  private static final int MAX_DENSE_TASK_ID_LOOKUP_LENGTH = 1_000_000;
  public static final String INNER_ITERATIONS_RATIO_PROPERTY = "hybcec.alns.innerIterationsRatio";

  private ALNS() {}

  public static final class ALNSResult {
    private final List<double[]> updatedPopulation;
    private final List<Double> evaluatedFitness;
    private final int evaluationCount;

    private ALNSResult(
      List<double[]> updatedPopulation,
      List<Double> evaluatedFitness,
      int evaluationCount
    ) {
      this.updatedPopulation = updatedPopulation;
      this.evaluatedFitness = evaluatedFitness;
      this.evaluationCount = evaluationCount;
    }

    public List<double[]> updatedPopulation() {
      return updatedPopulation;
    }

    public List<Double> evaluatedFitness() {
      return evaluatedFitness;
    }

    public int evaluationCount() {
      return evaluationCount;
    }
  }

  public static final class ALNSConfig implements ConfigValidatable {
    public final int innerIterations;
    public final int weightUpdateInterval;
    public final int minDestroyCount;
    public final double maxDestroyRatio;
    public final double sigmaGlobal;
    public final double sigmaLocal;
    public final double sigmaBad;
    public final double reactionFactor;
    public final double initialTemperatureRatio;
    public final double maxInitialTemperature;
    public final double coolingRate;
    public final double lowerBound;
    public final double upperBound;
    public final InsertionMode insertionMode;
    /** Opt-in repair operator; default false keeps the original 3-operator repair pool. */
    public final boolean enableRegret2Repair;
    public final double[] destroyWeights;
    public final double[] repairWeights;

    public ALNSConfig(
      int innerIterations,
      int weightUpdateInterval,
      int minDestroyCount,
      double maxDestroyRatio,
      double sigmaGlobal,
      double sigmaLocal,
      double sigmaBad,
      double reactionFactor,
      double initialTemperatureRatio,
      double maxInitialTemperature,
      double coolingRate,
      double lowerBound,
      double upperBound,
      InsertionMode insertionMode
    ) {
      this(
        innerIterations,
        weightUpdateInterval,
        minDestroyCount,
        maxDestroyRatio,
        sigmaGlobal,
        sigmaLocal,
        sigmaBad,
        reactionFactor,
        initialTemperatureRatio,
        maxInitialTemperature,
        coolingRate,
        lowerBound,
        upperBound,
        insertionMode,
        false,
        null,
        null
      );
    }

    public ALNSConfig(
      int innerIterations,
      int weightUpdateInterval,
      int minDestroyCount,
      double maxDestroyRatio,
      double sigmaGlobal,
      double sigmaLocal,
      double sigmaBad,
      double reactionFactor,
      double initialTemperatureRatio,
      double maxInitialTemperature,
      double coolingRate,
      double lowerBound,
      double upperBound,
      InsertionMode insertionMode,
      boolean enableRegret2Repair
    ) {
      this(
        innerIterations,
        weightUpdateInterval,
        minDestroyCount,
        maxDestroyRatio,
        sigmaGlobal,
        sigmaLocal,
        sigmaBad,
        reactionFactor,
        initialTemperatureRatio,
        maxInitialTemperature,
        coolingRate,
        lowerBound,
        upperBound,
        insertionMode,
        enableRegret2Repair,
        null,
        null
      );
    }

    public ALNSConfig(
      int innerIterations,
      int weightUpdateInterval,
      int minDestroyCount,
      double maxDestroyRatio,
      double sigmaGlobal,
      double sigmaLocal,
      double sigmaBad,
      double reactionFactor,
      double initialTemperatureRatio,
      double maxInitialTemperature,
      double coolingRate,
      double lowerBound,
      double upperBound,
      InsertionMode insertionMode,
      boolean enableRegret2Repair,
      double[] destroyWeights,
      double[] repairWeights
    ) {
      this.innerIterations = innerIterations;
      this.weightUpdateInterval = weightUpdateInterval;
      this.minDestroyCount = minDestroyCount;
      this.maxDestroyRatio = maxDestroyRatio;
      this.sigmaGlobal = sigmaGlobal;
      this.sigmaLocal = sigmaLocal;
      this.sigmaBad = sigmaBad;
      this.reactionFactor = reactionFactor;
      this.initialTemperatureRatio = initialTemperatureRatio;
      this.maxInitialTemperature = maxInitialTemperature;
      this.coolingRate = coolingRate;
      this.lowerBound = lowerBound;
      this.upperBound = upperBound;
      this.insertionMode = Objects.requireNonNull(insertionMode, "insertionMode cannot be null");
      this.enableRegret2Repair = enableRegret2Repair;
      this.destroyWeights = destroyWeights == null ? null : destroyWeights.clone();
      this.repairWeights = repairWeights == null ? null : repairWeights.clone();
    }

    public static ALNSConfig defaultConfig() {
      int defaultInnerIterations = 3;
      return new ALNSConfig(
        resolveInnerIterations(defaultInnerIterations),
        3,
        1,
        0.15,
        30.0,
        20.0,
        10.0,
        0.5,
        0.032,
        100.0,
        0.99975,
        -AlgorithmConstants.DEFAULT_BOUND,
        AlgorithmConstants.DEFAULT_BOUND,
        InsertionMode.GREEDY,
        false
      );
    }

    private static int resolveInnerIterations(int defaultInnerIterations) {
      String configured = System.getProperty(INNER_ITERATIONS_RATIO_PROPERTY);
      if (configured == null || configured.isBlank()) {
        return defaultInnerIterations;
      }
      double ratio;
      try {
        ratio = Double.parseDouble(configured.trim());
      } catch (NumberFormatException ex) {
        throw new IllegalArgumentException(
          INNER_ITERATIONS_RATIO_PROPERTY + " must be in (0, 1], got: " + configured, ex);
      }
      if (ratio <= 0.0 || ratio > 1.0) {
        throw new IllegalArgumentException(
          INNER_ITERATIONS_RATIO_PROPERTY + " must be in (0, 1], got: " + configured);
      }
      if (ratio == 1.0) {
        return defaultInnerIterations;
      }
      return Math.max(1, (int) Math.round(defaultInnerIterations * ratio));
    }

    @Override
    public ValidationResult validate() {
      ValidationResultBuilder builder = ConfigValidatable.builder();
      builder.checkPositiveInt("innerIterations", innerIterations);
      builder.checkPositiveInt("weightUpdateInterval", weightUpdateInterval);
      builder.checkPositiveInt("minDestroyCount", minDestroyCount);
      builder.checkRange("maxDestroyRatio", maxDestroyRatio, 0.0, 1.0);
      builder.checkPositive("sigmaGlobal", sigmaGlobal);
      builder.checkPositive("sigmaLocal", sigmaLocal);
      builder.checkPositive("sigmaBad", sigmaBad);
      builder.checkRange("reactionFactor", reactionFactor, 0.0, 1.0);
      builder.checkPositive("initialTemperatureRatio", initialTemperatureRatio);
      builder.checkPositive("maxInitialTemperature", maxInitialTemperature);
      builder.checkRange("coolingRate", coolingRate, 0.0, 1.0);
      builder.checkCondition(upperBound > lowerBound, "upperBound must be greater than lowerBound");
      builder.checkNotNull("insertionMode", insertionMode);
      checkWeightVector(builder, "destroyWeights", destroyWeights, 3);
      checkWeightVector(builder, "repairWeights", repairWeights, enableRegret2Repair ? 4 : 3);
      return builder.build();
    }

    private static void checkWeightVector(
      ValidationResultBuilder builder,
      String name,
      double[] weights,
      int expectedLength
    ) {
      if (weights == null) {
        return;
      }
      builder.checkCondition(
        weights.length == expectedLength,
        name + " length must be " + expectedLength + ", got " + weights.length);
      double sum = 0.0;
      for (int i = 0; i < weights.length; i++) {
        builder.checkCondition(
          Double.isFinite(weights[i]),
          name + "[" + i + "] must be finite");
        builder.checkPositive(name + "[" + i + "]", weights[i]);
        sum += weights[i];
      }
      builder.checkCondition(Double.isFinite(sum), name + " sum must be finite");
      builder.checkPositive(name + " sum", sum);
    }
  }

  public static List<double[]> run(
    List<double[]> population,
    int[] selectedIndices,
    List<Double> populationFitness,
    ALNSConfig config,
    Satellite satellite,
    List<Task> allTasks,
    Random random,
    Function<List<double[]>, List<Double>> evaluator
  ) {
    return runWithResult(population, selectedIndices, populationFitness, config, satellite, allTasks,
      random, evaluator, null, null,
      createDestroyOperators(config), createRepairOperators(config)).updatedPopulation();
  }

  public static List<double[]> run(
    List<double[]> population,
    int[] selectedIndices,
    List<Double> populationFitness,
    ALNSConfig config,
    Satellite satellite,
    List<Task> allTasks,
    Random random,
    Function<List<double[]>, List<Double>> evaluator,
    ToDoubleFunction<double[]> singleEvaluator,
    List<OperatorState> destroyOperators,
    List<OperatorState> repairOperators
  ) {
    return runWithResult(population, selectedIndices, populationFitness, config, satellite, allTasks,
      random, evaluator, singleEvaluator, null, destroyOperators, repairOperators).updatedPopulation();
  }

  public static List<double[]> run(
    List<double[]> population,
    int[] selectedIndices,
    List<Double> populationFitness,
    ALNSConfig config,
    Satellite satellite,
    List<Task> allTasks,
    Random random,
    Function<List<double[]>, List<Double>> evaluator,
    List<OperatorState> destroyOperators,
    List<OperatorState> repairOperators
  ) {
    return runWithResult(population, selectedIndices, populationFitness, config, satellite, allTasks,
      random, evaluator, null, null, destroyOperators, repairOperators).updatedPopulation();
  }

  public static ALNSResult runWithResult(
    List<double[]> population,
    int[] selectedIndices,
    List<Double> populationFitness,
    ALNSConfig config,
    Satellite satellite,
    List<Task> allTasks,
    Random random,
    Function<List<double[]>, List<Double>> evaluator,
    ToDoubleFunction<double[]> singleEvaluator,
    ToDoubleFunction<List<Task>> sequenceEvaluator,
    List<OperatorState> destroyOperators,
    List<OperatorState> repairOperators
  ) {
    return runInternal(population, selectedIndices, populationFitness, config, satellite, allTasks,
      random, evaluator, singleEvaluator, sequenceEvaluator, destroyOperators, repairOperators);
  }

  private static ALNSResult runInternal(
    List<double[]> population,
    int[] selectedIndices,
    List<Double> populationFitness,
    ALNSConfig config,
    Satellite satellite,
    List<Task> allTasks,
    Random random,
    Function<List<double[]>, List<Double>> evaluator,
    ToDoubleFunction<double[]> singleEvaluator,
    ToDoubleFunction<List<Task>> sequenceEvaluator,
    List<OperatorState> destroyOperators,
    List<OperatorState> repairOperators
  ) {
    validateInputs(population, selectedIndices, populationFitness, config, random);
    Objects.requireNonNull(destroyOperators, "destroyOperators cannot be null");
    Objects.requireNonNull(repairOperators, "repairOperators cannot be null");

    List<Task> referenceTasks = resolveReferenceTasks(satellite, allTasks);
    if (referenceTasks.isEmpty()) {
      List<double[]> clones = cloneSelected(population, selectedIndices);
      List<Double> fitness = new ArrayList<>(selectedIndices.length);
      for (int selectedIndex : selectedIndices) {
        fitness.add(populationFitness.get(selectedIndex));
      }
      return new ALNSResult(clones, fitness, 0);
    }

    config.validate().throwIfInvalidAsRuntimeException();
    TaskIndexLookup taskIndexLookup = TaskIndexLookup.create(referenceTasks);
    SequenceScratch sequenceScratch = new SequenceScratch(referenceTasks.size(), taskIndexLookup);

    List<double[]> result = new ArrayList<>(selectedIndices.length);
    List<Double> resultFitness = new ArrayList<>(selectedIndices.length);
    EvalCounter evalCounter = new EvalCounter();

    for (int selectedIndex : selectedIndices) {
      double[] original = population.get(selectedIndex);
      double originalFitness = populationFitness.get(selectedIndex);
      RefineResult improved = refineIndividual(
        original,
        originalFitness,
        referenceTasks,
        config,
        satellite,
        random,
        evaluator,
        singleEvaluator,
        sequenceEvaluator,
        destroyOperators,
        repairOperators,
        taskIndexLookup,
        sequenceScratch,
        evalCounter
      );
      result.add(improved.chromosome);
      resultFitness.add(improved.fitness);
    }

    return new ALNSResult(result, resultFitness, evalCounter.count);
  }

  private static RefineResult refineIndividual(
    double[] original,
    double originalFitness,
    List<Task> referenceTasks,
    ALNSConfig config,
    Satellite satellite,
    Random random,
    Function<List<double[]>, List<Double>> evaluator,
    ToDoubleFunction<double[]> singleEvaluator,
    ToDoubleFunction<List<Task>> sequenceEvaluator,
    List<OperatorState> destroyOperators,
    List<OperatorState> repairOperators,
    TaskIndexLookup taskIndexLookup,
    SequenceScratch sequenceScratch,
    EvalCounter evalCounter
  ) {
    List<Task> currentSequence = decodeSequence(original, referenceTasks, sequenceScratch);
    double currentFitness = ensureFiniteFitness(
      originalFitness, currentSequence, referenceTasks, satellite, config,
      evaluator, singleEvaluator, sequenceEvaluator, taskIndexLookup, evalCounter);

    List<Task> bestSequence = new ArrayList<>(currentSequence);
    double bestFitness = currentFitness;

    double temperature = Math.min(
      config.maxInitialTemperature,
      Math.max(MIN_TEMPERATURE, Math.abs(currentFitness) * config.initialTemperatureRatio)
    );

    for (int iter = 1; iter <= config.innerIterations; iter++) {
      OperatorState destroy = selectOperator(destroyOperators, random);
      OperatorState repair = selectOperator(repairOperators, random);
      destroy.calledTimes++;
      repair.calledTimes++;

      List<Task> candidateSequence = buildCandidate(
        currentSequence,
        referenceTasks,
        config,
        satellite,
        evaluator,
        singleEvaluator,
        sequenceEvaluator,
        destroy.destroyKind,
        repair.repairKind,
        random,
        taskIndexLookup,
        sequenceScratch,
        evalCounter
      );
      double candidateFitness = evaluateSequence(candidateSequence, referenceTasks, satellite,
        config, evaluator, singleEvaluator, sequenceEvaluator, taskIndexLookup, evalCounter);

      if (candidateFitness > bestFitness) {
        currentSequence = candidateSequence;
        currentFitness = candidateFitness;
        bestSequence = new ArrayList<>(candidateSequence);
        bestFitness = candidateFitness;
        destroy.score += config.sigmaGlobal;
        repair.score += config.sigmaGlobal;
      } else if (candidateFitness >= currentFitness) {
        currentSequence = candidateSequence;
        currentFitness = candidateFitness;
        destroy.score += config.sigmaLocal;
        repair.score += config.sigmaLocal;
      } else if (acceptWorse(candidateFitness, currentFitness, temperature, random)) {
        currentSequence = candidateSequence;
        currentFitness = candidateFitness;
        destroy.score += config.sigmaBad;
        repair.score += config.sigmaBad;
      }

      temperature = Math.max(MIN_TEMPERATURE, temperature * config.coolingRate);
      if (iter % config.weightUpdateInterval == 0) {
        updateWeights(destroyOperators, config.reactionFactor);
        updateWeights(repairOperators, config.reactionFactor);
      }
    }

    double[] bestChromosome = clampChromosome(
      encodeSequence(bestSequence, referenceTasks, taskIndexLookup),
      config);
    return new RefineResult(bestChromosome, bestFitness);
  }

  private static final class RefineResult {
    private final double[] chromosome;
    private final double fitness;

    private RefineResult(double[] chromosome, double fitness) {
      this.chromosome = chromosome;
      this.fitness = fitness;
    }
  }

  private static final class EvalCounter {
    private int count;
  }

  private static final class TaskIndexLookup {
    private final int[] idToIndex;

    private TaskIndexLookup(int[] idToIndex) {
      this.idToIndex = idToIndex;
    }

    private static TaskIndexLookup create(List<Task> referenceTasks) {
      int maxId = -1;
      for (Task task : referenceTasks) {
        if (task == null || task.getId() < 0) {
          return new TaskIndexLookup(null);
        }
        maxId = Math.max(maxId, task.getId());
      }
      long lookupLength = (long) maxId + 1L;
      if (lookupLength <= 0L || lookupLength > MAX_DENSE_TASK_ID_LOOKUP_LENGTH) {
        return new TaskIndexLookup(null);
      }

      int[] dense = new int[(int) lookupLength];
      Arrays.fill(dense, -1);
      for (int i = 0; i < referenceTasks.size(); i++) {
        int taskId = referenceTasks.get(i).getId();
        if (dense[taskId] >= 0) {
          return new TaskIndexLookup(null);
        }
        dense[taskId] = i;
      }
      return new TaskIndexLookup(dense);
    }

    private boolean isDense() {
      return idToIndex != null;
    }

    private int indexOf(int taskId) {
      if (idToIndex == null || taskId < 0 || taskId >= idToIndex.length) {
        return -1;
      }
      return idToIndex[taskId];
    }
  }

  private static final class SequenceScratch {
    private final boolean[] presentByReferenceIndex;
    private final boolean[] removedPositions;
    private final int[] rankedIndices;
    private final int[] rankKeys;
    private final int[] sortScratch;
    private final int[] decodeOrder;
    private final int[] decodeScratch;

    private SequenceScratch(int size, TaskIndexLookup taskIndexLookup) {
      this.presentByReferenceIndex = taskIndexLookup.isDense() ? new boolean[size] : null;
      this.removedPositions = new boolean[size];
      this.rankedIndices = new int[size];
      this.rankKeys = new int[size];
      this.sortScratch = new int[size];
      this.decodeOrder = new int[size];
      this.decodeScratch = new int[size];
    }
  }

  private static List<Task> decodeSequence(
    double[] chromosome,
    List<Task> referenceTasks,
    SequenceScratch scratch
  ) {
    int n = chromosome.length;
    if (n != referenceTasks.size()) {
      throw new IllegalArgumentException(
        "Vector length (" + n + ") must match task list size (" + referenceTasks.size() + ")");
    }
    for (int i = 0; i < n; i++) {
      scratch.decodeOrder[i] = i;
    }
    mergeSortDecodeOrder(chromosome, scratch.decodeOrder, scratch.decodeScratch, 0, n);
    List<Task> sequence = new ArrayList<>(n);
    for (int i = 0; i < n; i++) {
      sequence.add(referenceTasks.get(scratch.decodeOrder[i]));
    }
    return sequence;
  }

  private static void mergeSortDecodeOrder(
    double[] chromosome,
    int[] order,
    int[] scratch,
    int left,
    int right
  ) {
    int length = right - left;
    if (length <= 1) {
      return;
    }
    int mid = left + (length >>> 1);
    mergeSortDecodeOrder(chromosome, order, scratch, left, mid);
    mergeSortDecodeOrder(chromosome, order, scratch, mid, right);
    int i = left;
    int j = mid;
    int k = left;
    while (i < mid && j < right) {
      int leftIndex = order[i];
      int rightIndex = order[j];
      if (compareDecodeOrder(chromosome, leftIndex, rightIndex) <= 0) {
        scratch[k++] = leftIndex;
        i++;
      } else {
        scratch[k++] = rightIndex;
        j++;
      }
    }
    while (i < mid) {
      scratch[k++] = order[i++];
    }
    while (j < right) {
      scratch[k++] = order[j++];
    }
    System.arraycopy(scratch, left, order, left, length);
  }

  private static int compareDecodeOrder(double[] chromosome, int leftIndex, int rightIndex) {
    int byValue = Double.compare(chromosome[rightIndex], chromosome[leftIndex]);
    if (byValue != 0) {
      return byValue;
    }
    return Integer.compare(leftIndex, rightIndex);
  }

  private static List<Task> buildCandidate(
    List<Task> currentSequence,
    List<Task> referenceTasks,
    ALNSConfig config,
    Satellite satellite,
    Function<List<double[]>, List<Double>> evaluator,
    ToDoubleFunction<double[]> singleEvaluator,
    ToDoubleFunction<List<Task>> sequenceEvaluator,
    DestroyKind destroyKind,
    RepairKind repairKind,
    Random random,
    TaskIndexLookup taskIndexLookup,
    SequenceScratch sequenceScratch,
    EvalCounter evalCounter
  ) {
    int destroyCount = computeDestroyCount(currentSequence.size(), config, random);
    boolean[] removedPositions = chooseRemovedPositions(
      currentSequence,
      destroyCount,
      destroyKind,
      random,
      sequenceScratch
    );

    List<Task> repaired = new ArrayList<>(currentSequence.size());
    List<Task> bank = new ArrayList<>(destroyCount);
    for (int i = 0; i < currentSequence.size(); i++) {
      Task task = currentSequence.get(i);
      if (removedPositions[i]) {
        bank.add(task);
      } else {
        repaired.add(task);
      }
    }
    bank.sort(Comparator.comparingInt(Task::getId));
    if (repairKind == RepairKind.REGRET_2) {
      repairByRegret2(repaired, bank, referenceTasks, satellite, config, evaluator,
        singleEvaluator, sequenceEvaluator, taskIndexLookup, sequenceScratch, evalCounter);
    } else {
      sortRepairBank(bank, repairKind, random);
      for (Task task : bank) {
        int insertIndex = chooseInsertionIndex(repaired, task, repairKind, random);
        repaired.add(insertIndex, task);
      }
    }
    appendMissingTasks(repaired, referenceTasks);
    return repaired;
  }

  private static void repairByRegret2(
    List<Task> repaired,
    List<Task> bank,
    List<Task> referenceTasks,
    Satellite satellite,
    ALNSConfig config,
    Function<List<double[]>, List<Double>> evaluator,
    ToDoubleFunction<double[]> singleEvaluator,
    ToDoubleFunction<List<Task>> sequenceEvaluator,
    TaskIndexLookup taskIndexLookup,
    SequenceScratch sequenceScratch,
    EvalCounter evalCounter
  ) {
    while (!bank.isEmpty()) {
      RegretChoice bestChoice = null;
      for (int taskIdx = 0; taskIdx < bank.size(); taskIdx++) {
        RegretChoice choice = evaluateRegretChoice(
          repaired, bank.get(taskIdx), taskIdx, referenceTasks, satellite, config, evaluator,
          singleEvaluator, sequenceEvaluator, taskIndexLookup, sequenceScratch, evalCounter);
        if (bestChoice == null || choice.regret > bestChoice.regret
          || (choice.regret == bestChoice.regret && choice.bestDelta > bestChoice.bestDelta)) {
          bestChoice = choice;
        }
      }
      Task selected = bank.remove(bestChoice.taskIndex);
      repaired.add(bestChoice.insertIndex, selected);
    }
  }

  private static RegretChoice evaluateRegretChoice(
    List<Task> sequence,
    Task task,
    int taskIndex,
    List<Task> referenceTasks,
    Satellite satellite,
    ALNSConfig config,
    Function<List<double[]>, List<Double>> evaluator,
    ToDoubleFunction<double[]> singleEvaluator,
    ToDoubleFunction<List<Task>> sequenceEvaluator,
    TaskIndexLookup taskIndexLookup,
    SequenceScratch sequenceScratch,
    EvalCounter evalCounter
  ) {
    double bestDelta = Double.NEGATIVE_INFINITY;
    double secondDelta = Double.NEGATIVE_INFINITY;
    int bestInsertIndex = 0;

    for (int pos = 0; pos <= sequence.size(); pos++) {
      List<Task> trial = new ArrayList<>(sequence);
      trial.add(pos, task);
      double insertionFitness = evaluateSequenceWithMissing(trial, referenceTasks, satellite,
        config, evaluator, singleEvaluator, sequenceEvaluator, taskIndexLookup,
        sequenceScratch, evalCounter);
      if (insertionFitness > bestDelta) {
        secondDelta = bestDelta;
        bestDelta = insertionFitness;
        bestInsertIndex = pos;
      } else if (insertionFitness > secondDelta) {
        secondDelta = insertionFitness;
      }
    }

    double regret = (secondDelta == Double.NEGATIVE_INFINITY) ? bestDelta : bestDelta - secondDelta;
    return new RegretChoice(taskIndex, bestInsertIndex, bestDelta, regret);
  }

  private static int computeDestroyCount(int sequenceSize, ALNSConfig config, Random random) {
    if (sequenceSize <= 1) {
      return 0;
    }
    int maxDestroy = Math.max(config.minDestroyCount, (int) Math.ceil(sequenceSize * config.maxDestroyRatio));
    maxDestroy = Math.min(maxDestroy, sequenceSize - 1);
    if (maxDestroy <= config.minDestroyCount) {
      return maxDestroy;
    }
    return config.minDestroyCount + random.nextInt(maxDestroy - config.minDestroyCount + 1);
  }

  private static boolean[] chooseRemovedPositions(
    List<Task> sequence,
    int destroyCount,
    DestroyKind kind,
    Random random,
    SequenceScratch sequenceScratch
  ) {
    boolean[] removedPositions = sequenceScratch.removedPositions;
    Arrays.fill(removedPositions, 0, sequence.size(), false);
    if (destroyCount <= 0 || sequence.isEmpty()) {
      return removedPositions;
    }

    switch (kind) {
      case RANDOM:
        int selected = 0;
        while (selected < destroyCount) {
          int index = random.nextInt(sequence.size());
          if (!removedPositions[index]) {
            removedPositions[index] = true;
            selected++;
          }
        }
        return removedPositions;
      case LOW_PRIORITY:
        markRankedRemovedPositions(
          removedPositions,
          sequence,
          destroyCount,
          random,
          kind,
          sequenceScratch
        );
        break;
      case MANY_WINDOWS:
        markRankedRemovedPositions(
          removedPositions,
          sequence,
          destroyCount,
          random,
          kind,
          sequenceScratch
        );
        break;
      default:
        throw new IllegalArgumentException("Unsupported destroy kind: " + kind);
    }
    return removedPositions;
  }

  private static void markRankedRemovedPositions(
    boolean[] removedPositions,
    List<Task> sequence,
    int destroyCount,
    Random random,
    DestroyKind kind,
    SequenceScratch sequenceScratch
  ) {
    int size = sequence.size();
    int[] rankedIndices = sequenceScratch.rankedIndices;
    int[] rankKeys = sequenceScratch.rankKeys;
    int[] scratch = sequenceScratch.sortScratch;
    for (int i = 0; i < size; i++) {
      rankedIndices[i] = i;
      rankKeys[i] = rankKey(sequence.get(i), kind);
    }
    shuffle(rankedIndices, size, random);
    stableSortByRankKey(rankedIndices, scratch, rankKeys, size, kind == DestroyKind.MANY_WINDOWS);
    for (int i = 0; i < destroyCount; i++) {
      removedPositions[rankedIndices[i]] = true;
    }
  }

  private static int rankKey(Task task, DestroyKind kind) {
    switch (kind) {
      case LOW_PRIORITY:
        return task.getPriority();
      case MANY_WINDOWS:
        return getWindowCount(task, -1);
      default:
        throw new IllegalArgumentException("Unsupported ranked destroy kind: " + kind);
    }
  }

  private static void stableSortByRankKey(
    int[] indices,
    int[] scratch,
    int[] rankKeys,
    int length,
    boolean descending
  ) {
    for (int width = 1; width < length; width <<= 1) {
      for (int left = 0; left < length; left += width << 1) {
        int middle = Math.min(left + width, length);
        int right = Math.min(left + (width << 1), length);
        mergeByRankKey(indices, scratch, rankKeys, left, middle, right, descending);
      }
      System.arraycopy(scratch, 0, indices, 0, length);
    }
  }

  private static void mergeByRankKey(
    int[] indices,
    int[] scratch,
    int[] rankKeys,
    int left,
    int middle,
    int right,
    boolean descending
  ) {
    int i = left;
    int j = middle;
    int out = left;
    while (i < middle && j < right) {
      int leftIndex = indices[i];
      int rightIndex = indices[j];
      boolean takeLeft = descending
        ? rankKeys[leftIndex] >= rankKeys[rightIndex]
        : rankKeys[leftIndex] <= rankKeys[rightIndex];
      if (takeLeft) {
        scratch[out++] = indices[i++];
      } else {
        scratch[out++] = indices[j++];
      }
    }
    while (i < middle) {
      scratch[out++] = indices[i++];
    }
    while (j < right) {
      scratch[out++] = indices[j++];
    }
  }

  private static void sortRepairBank(List<Task> bank, RepairKind kind, Random random) {
    switch (kind) {
      case HIGH_PRIORITY:
        shuffle(bank, random);
        bank.sort(Comparator.comparingInt(Task::getPriority).reversed());
        break;
      case FEW_WINDOWS:
        shuffle(bank, random);
        bank.sort(Comparator.comparingInt((Task task) -> getWindowCount(task, -1)));
        break;
      case RANDOM:
        shuffle(bank, random);
        break;
      case REGRET_2:
        break;
      default:
        throw new IllegalArgumentException("Unsupported repair kind: " + kind);
    }
  }

  private static int chooseInsertionIndex(
    List<Task> sequence,
    Task task,
    RepairKind kind,
    Random random
  ) {
    if (sequence.isEmpty()) {
      return 0;
    }
    if (kind == RepairKind.RANDOM) {
      return random.nextInt(sequence.size() + 1);
    }
    if (kind == RepairKind.HIGH_PRIORITY) {
      for (int i = 0; i < sequence.size(); i++) {
        if (task.getPriority() > sequence.get(i).getPriority()) {
          return i;
        }
      }
    }
    return Math.min(sequence.size(), random.nextInt(sequence.size() + 1));
  }

  private static void appendMissingTasks(List<Task> sequence, List<Task> referenceTasks) {
    if (sequence.size() == referenceTasks.size()) {
      return;
    }
    Set<Integer> existingIds = new HashSet<>();
    for (Task task : sequence) {
      existingIds.add(task.getId());
    }
    for (Task task : referenceTasks) {
      if (existingIds.add(task.getId())) {
        sequence.add(task);
      }
    }
  }

  private static void appendMissingTasks(
    List<Task> sequence,
    List<Task> referenceTasks,
    TaskIndexLookup taskIndexLookup,
    SequenceScratch scratch
  ) {
    if (sequence.size() == referenceTasks.size()) {
      return;
    }
    if (taskIndexLookup == null || !taskIndexLookup.isDense()
      || scratch == null || scratch.presentByReferenceIndex == null) {
      appendMissingTasks(sequence, referenceTasks);
      return;
    }

    boolean[] presentByReferenceIndex = scratch.presentByReferenceIndex;
    Arrays.fill(presentByReferenceIndex, false);
    for (Task task : sequence) {
      int referenceIndex = taskIndexLookup.indexOf(task.getId());
      if (referenceIndex < 0) {
        appendMissingTasks(sequence, referenceTasks);
        return;
      }
      presentByReferenceIndex[referenceIndex] = true;
    }
    for (int i = 0; i < referenceTasks.size(); i++) {
      if (!presentByReferenceIndex[i]) {
        sequence.add(referenceTasks.get(i));
      }
    }
  }

  private static double[] encodeSequence(
    List<Task> sequence,
    List<Task> referenceTasks,
    TaskIndexLookup taskIndexLookup
  ) {
    if (sequence == null) {
      throw new IllegalArgumentException("Task sequence cannot be null");
    }
    int geneLength = referenceTasks.size();
    if (sequence.size() != geneLength) {
      throw new IllegalArgumentException(
        "Plan sequence size must match task list size (" + geneLength + ")");
    }
    if (taskIndexLookup == null || !taskIndexLookup.isDense()) {
      return PlanUtils.encodePlan(new SchedulingPlan(sequence), referenceTasks);
    }

    double[] chromosome = new double[geneLength];
    for (int rank = 0; rank < geneLength; rank++) {
      Task task = sequence.get(rank);
      if (task == null) {
        throw new IllegalArgumentException("Task in sequence cannot be null");
      }
      int geneIndex = taskIndexLookup.indexOf(task.getId());
      if (geneIndex < 0) {
        throw new IllegalArgumentException(
          "Task ID " + task.getId() + " not found in reference task list");
      }
      chromosome[geneIndex] = (double) (geneLength - rank) / geneLength;
    }
    return chromosome;
  }

  private static double ensureFiniteFitness(
    double originalFitness,
    List<Task> currentSequence,
    List<Task> referenceTasks,
    Satellite satellite,
    ALNSConfig config,
    Function<List<double[]>, List<Double>> evaluator,
    ToDoubleFunction<double[]> singleEvaluator,
    ToDoubleFunction<List<Task>> sequenceEvaluator,
    TaskIndexLookup taskIndexLookup,
    EvalCounter evalCounter
  ) {
    if (Double.isFinite(originalFitness)) {
      return originalFitness;
    }
    return evaluateSequence(currentSequence, referenceTasks, satellite, config,
      evaluator, singleEvaluator, sequenceEvaluator, taskIndexLookup, evalCounter);
  }

  private static double evaluateSequence(
    List<Task> sequence,
    List<Task> referenceTasks,
    Satellite satellite,
    ALNSConfig config,
    Function<List<double[]>, List<Double>> evaluator,
    ToDoubleFunction<double[]> singleEvaluator,
    ToDoubleFunction<List<Task>> sequenceEvaluator,
    TaskIndexLookup taskIndexLookup,
    EvalCounter evalCounter
  ) {
    if (sequenceEvaluator != null) {
      evalCounter.count++;
      return sequenceEvaluator.applyAsDouble(sequence);
    }
    double[] chromosome = encodeSequence(sequence, referenceTasks, taskIndexLookup);
    return evaluateChromosome(chromosome, referenceTasks, satellite, config, evaluator,
      singleEvaluator, evalCounter);
  }

  private static double evaluateSequenceWithMissing(
    List<Task> sequence,
    List<Task> referenceTasks,
    Satellite satellite,
    ALNSConfig config,
    Function<List<double[]>, List<Double>> evaluator,
    ToDoubleFunction<double[]> singleEvaluator,
    ToDoubleFunction<List<Task>> sequenceEvaluator,
    TaskIndexLookup taskIndexLookup,
    SequenceScratch sequenceScratch,
    EvalCounter evalCounter
  ) {
    List<Task> completed = new ArrayList<>(sequence);
    appendMissingTasks(completed, referenceTasks, taskIndexLookup, sequenceScratch);
    return evaluateSequence(completed, referenceTasks, satellite, config, evaluator,
      singleEvaluator, sequenceEvaluator, taskIndexLookup, evalCounter);
  }

  private static double evaluateChromosome(
    double[] chromosome,
    List<Task> referenceTasks,
    Satellite satellite,
    ALNSConfig config,
    Function<List<double[]>, List<Double>> evaluator,
    ToDoubleFunction<double[]> singleEvaluator,
    EvalCounter evalCounter
  ) {
    evalCounter.count++;
    if (singleEvaluator != null) {
      return singleEvaluator.applyAsDouble(chromosome);
    }
    if (evaluator != null) {
      List<double[]> one = new ArrayList<>(1);
      one.add(chromosome);
      return evaluator.apply(one).get(0);
    }
    SchedulingPlan plan = PlanUtils.decode(chromosome, referenceTasks);
    return PlanUtils.evaluatePlan(plan, satellite, config.insertionMode);
  }

  private static boolean acceptWorse(
    double candidateFitness,
    double currentFitness,
    double temperature,
    Random random
  ) {
    if (temperature <= MIN_TEMPERATURE) {
      return false;
    }
    double denominator = Math.max(1.0, Math.abs(currentFitness));
    double probability = Math.exp((candidateFitness - currentFitness) / (temperature * denominator));
    return random.nextDouble() < probability;
  }

  private static OperatorState selectOperator(List<OperatorState> operators, Random random) {
    double totalWeight = 0.0;
    for (OperatorState operator : operators) {
      totalWeight += operator.weight;
    }
    double threshold = random.nextDouble() * totalWeight;
    double cumulative = 0.0;
    for (OperatorState operator : operators) {
      cumulative += operator.weight;
      if (threshold <= cumulative) {
        return operator;
      }
    }
    return operators.get(operators.size() - 1);
  }

  private static void updateWeights(List<OperatorState> operators, double reactionFactor) {
    double totalScore = 0.0;
    for (OperatorState operator : operators) {
      if (operator.calledTimes != 0) {
        operator.score = INITIAL_OPERATOR_SCORE
          + (operator.score - INITIAL_OPERATOR_SCORE) / operator.calledTimes;
      } else {
        operator.score = INITIAL_OPERATOR_SCORE;
      }
      totalScore += operator.score;
      operator.calledTimes = 0;
    }
    if (totalScore <= 0.0) {
      return;
    }
    for (OperatorState operator : operators) {
      double normalizedScore = operator.score / totalScore;
      operator.weight = operator.weight * reactionFactor + (1.0 - reactionFactor) * normalizedScore;
      operator.score = INITIAL_OPERATOR_SCORE;
    }
  }

  public static List<OperatorState> createDestroyOperators() {
    return createDestroyOperators(null);
  }

  public static List<OperatorState> createDestroyOperators(ALNSConfig config) {
    double[] weights = normalizeWeights(config == null ? null : config.destroyWeights, 3);
    return List.of(
      new OperatorState(DestroyKind.RANDOM, weights[0]),
      new OperatorState(DestroyKind.LOW_PRIORITY, weights[1]),
      new OperatorState(DestroyKind.MANY_WINDOWS, weights[2])
    );
  }

  public static List<OperatorState> createRepairOperators() {
    return createRepairOperators(null);
  }

  public static List<OperatorState> createRepairOperators(ALNSConfig config) {
    boolean enableRegret2 = config != null && config.enableRegret2Repair;
    if (enableRegret2) {
      double[] weights = normalizeWeights(config.repairWeights, 4);
      return List.of(
        new OperatorState(RepairKind.HIGH_PRIORITY, weights[0]),
        new OperatorState(RepairKind.FEW_WINDOWS, weights[1]),
        new OperatorState(RepairKind.RANDOM, weights[2]),
        new OperatorState(RepairKind.REGRET_2, weights[3])
      );
    }
    double[] weights = normalizeWeights(config == null ? null : config.repairWeights, 3);
    return List.of(
      new OperatorState(RepairKind.HIGH_PRIORITY, weights[0]),
      new OperatorState(RepairKind.FEW_WINDOWS, weights[1]),
      new OperatorState(RepairKind.RANDOM, weights[2])
    );
  }

  private static double[] normalizeWeights(double[] configured, int length) {
    double[] weights = new double[length];
    if (configured == null) {
      Arrays.fill(weights, 1.0 / length);
      return weights;
    }
    if (configured.length != length) {
      throw new IllegalArgumentException("ALNS operator weight length must be " + length);
    }
    double sum = 0.0;
    for (double weight : configured) {
      if (weight <= 0.0 || !Double.isFinite(weight)) {
        throw new IllegalArgumentException("ALNS operator weights must be positive finite values");
      }
      sum += weight;
    }
    if (!Double.isFinite(sum)) {
      throw new IllegalArgumentException("ALNS operator weight sum must be finite");
    }
    for (int i = 0; i < length; i++) {
      weights[i] = configured[i] / sum;
    }
    return weights;
  }

  private static List<Task> resolveReferenceTasks(Satellite satellite, List<Task> allTasks) {
    if (allTasks != null && !allTasks.isEmpty()) {
      return allTasks;
    }
    if (satellite != null && satellite.getAssignedTasks() != null) {
      return satellite.getAssignedTasks();
    }
    return List.of();
  }

  private static List<double[]> cloneSelected(List<double[]> population, int[] selectedIndices) {
    List<double[]> clones = new ArrayList<>(selectedIndices.length);
    for (int selectedIndex : selectedIndices) {
      clones.add(population.get(selectedIndex).clone());
    }
    return clones;
  }

  private static double[] clampChromosome(double[] chromosome, ALNSConfig config) {
    for (int i = 0; i < chromosome.length; i++) {
      chromosome[i] = Math.max(config.lowerBound, Math.min(config.upperBound, chromosome[i]));
    }
    return chromosome;
  }

  private static int getWindowCount(Task task, int satelliteId) {
    if (task == null) {
      return 0;
    }
    if (satelliteId >= 0) {
      return task.getTimeWindowsForSatellite(satelliteId).size();
    }
    return task.getTotalTimeWindowCount();
  }

  private static <T> void shuffle(List<T> list, Random random) {
    for (int i = list.size() - 1; i > 0; i--) {
      int j = random.nextInt(i + 1);
      T temp = list.get(i);
      list.set(i, list.get(j));
      list.set(j, temp);
    }
  }

  private static void shuffle(int[] values, int length, Random random) {
    for (int i = length - 1; i > 0; i--) {
      int j = random.nextInt(i + 1);
      int temp = values[i];
      values[i] = values[j];
      values[j] = temp;
    }
  }

  private static void validateInputs(
    List<double[]> population,
    int[] selectedIndices,
    List<Double> populationFitness,
    ALNSConfig config,
    Random random
  ) {
    Objects.requireNonNull(population, "population cannot be null");
    Objects.requireNonNull(selectedIndices, "selectedIndices cannot be null");
    Objects.requireNonNull(populationFitness, "populationFitness cannot be null");
    Objects.requireNonNull(config, "config cannot be null");
    Objects.requireNonNull(random, "random cannot be null");
    if (population.size() != populationFitness.size()) {
      throw new IllegalArgumentException("population and populationFitness sizes must match");
    }
    for (int selectedIndex : selectedIndices) {
      if (selectedIndex < 0 || selectedIndex >= population.size()) {
        throw new IllegalArgumentException("selected index out of range: " + selectedIndex);
      }
    }
  }

  private enum DestroyKind {
    RANDOM,
    LOW_PRIORITY,
    MANY_WINDOWS
  }

  private enum RepairKind {
    HIGH_PRIORITY,
    FEW_WINDOWS,
    RANDOM,
    REGRET_2
  }

  private static final class RegretChoice {
    private final int taskIndex;
    private final int insertIndex;
    private final double bestDelta;
    private final double regret;

    private RegretChoice(int taskIndex, int insertIndex, double bestDelta, double regret) {
      this.taskIndex = taskIndex;
      this.insertIndex = insertIndex;
      this.bestDelta = bestDelta;
      this.regret = regret;
    }
  }

  public static final class OperatorState {
    private final DestroyKind destroyKind;
    private final RepairKind repairKind;
    private double weight;
    private double score;
    private int calledTimes;

    private OperatorState(DestroyKind kind, double initialWeight) {
      this.destroyKind = kind;
      this.repairKind = null;
      this.weight = initialWeight;
      this.score = INITIAL_OPERATOR_SCORE;
    }

    private OperatorState(RepairKind kind, double initialWeight) {
      this.destroyKind = null;
      this.repairKind = kind;
      this.weight = initialWeight;
      this.score = INITIAL_OPERATOR_SCORE;
    }

    public double getWeight() {
      return weight;
    }
  }
}
