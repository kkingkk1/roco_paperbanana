package method.rkhma.legacy.main;

import method.rkhma.legacy.algorithm.pso.PSO;
import method.rkhma.legacy.algorithm.sa.SACont;
import method.rkhma.legacy.common.AlgorithmConstants;
import method.rkhma.legacy.core.config.HybCecConfigManager;
import method.rkhma.legacy.core.state.HybCecState;
import method.rkhma.legacy.core.state.StateKey;
import method.rkhma.legacy.core.state.StateUpdater;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import method.rkhma.legacy.model.Satellite;
import method.rkhma.legacy.model.Task;
import method.rkhma.legacy.operator.insertion.InsertionMode;
import method.rkhma.legacy.strategy.ABCStrategy;
import method.rkhma.legacy.strategy.ACOStrategy;
import method.rkhma.legacy.strategy.ALNSStrategy;
import method.rkhma.legacy.strategy.DEBest1Strategy;
import method.rkhma.legacy.strategy.DEBest2Strategy;
import method.rkhma.legacy.strategy.DECurrentToBestStrategy;
import method.rkhma.legacy.strategy.DECurrentToRandStrategy;
import method.rkhma.legacy.strategy.DERand1Strategy;
import method.rkhma.legacy.strategy.DERand2Strategy;
import method.rkhma.legacy.strategy.DERandToBestStrategy;
import method.rkhma.legacy.strategy.EDAStrategy;
import method.rkhma.legacy.strategy.GAStrategy;
import method.rkhma.legacy.strategy.ParentContext;
import method.rkhma.legacy.strategy.ParentStrategy;
import method.rkhma.legacy.strategy.ParentUpdateResult;
import method.rkhma.legacy.strategy.PSOStrategy;
import method.rkhma.legacy.strategy.SAStrategy;
import method.rkhma.legacy.strategy.SHADEStrategy;
import method.rkhma.legacy.strategy.StrategyInput;
import method.rkhma.legacy.utils.DecodeWorkspace;
import method.rkhma.legacy.utils.ListUtils;
import method.rkhma.legacy.utils.PlanUtils;

final class SingleAlgorithmOptimizer implements StateUpdater {

  static final class Result {
    private final double[] bestSolution;
    private final double bestFitness;
    private final int generations;
    private final List<Double> bestFitnessByGeneration;
    private final List<Integer> evalsByGeneration;

    private Result(
      double[] bestSolution,
      double bestFitness,
      int generations,
      List<Double> bestFitnessByGeneration,
      List<Integer> evalsByGeneration
    ) {
      this.bestSolution = bestSolution;
      this.bestFitness = bestFitness;
      this.generations = generations;
      this.bestFitnessByGeneration = new ArrayList<>(bestFitnessByGeneration);
      this.evalsByGeneration = new ArrayList<>(evalsByGeneration);
    }

    double[] getBestSolution() {
      return bestSolution;
    }

    double getBestFitness() {
      return bestFitness;
    }

    int getGenerations() {
      return generations;
    }

    List<Double> getBestFitnessByGeneration() {
      return new ArrayList<>(bestFitnessByGeneration);
    }

    List<Integer> getEvalsByGeneration() {
      return new ArrayList<>(evalsByGeneration);
    }
  }

  private final HybCecConfigManager configManager = new HybCecConfigManager();
  private final HybCecState state = new HybCecState();
  private final DecodeWorkspace singleDecodeWorkspace = new DecodeWorkspace();
  private final DecodeWorkspace sequenceDecodeWorkspace = new DecodeWorkspace();
  private List<Double> limitABC;
  private List<double[]> sigma;
  private List<double[]> psoVelocity;
  private int shadeGG;
  private List<double[]> shadeSF = new ArrayList<>();
  private List<Double> shadeCR = new ArrayList<>();
  private List<Double> shadeFreq = new ArrayList<>();
  private final List<Double> goodFit1All = new ArrayList<>();
  private final List<Double> goodFit2All = new ArrayList<>();
  private final List<Double> badFit1All = new ArrayList<>();
  private final List<Double> badFit2All = new ArrayList<>();
  private boolean flag1Shade;
  private boolean flag2Shade;
  private int iterationCount;

  private SingleAlgorithmOptimizer() {}

  static Result optimize(
    int algorithmIndex,
    int dimension,
    int nPop,
    int maxFes,
    Satellite satellite,
    List<Task> orderedTasks,
    List<double[]> initialPopulation,
    Random rand,
    InsertionMode insertionMode
  ) throws Exception {
    return new SingleAlgorithmOptimizer().run(
      algorithmIndex,
      dimension,
      nPop,
      maxFes,
      satellite,
      orderedTasks,
      initialPopulation,
      rand,
      insertionMode
    );
  }

  private Result run(
    int algorithmIndex,
    int dimension,
    int nPop,
    int maxFes,
    Satellite satellite,
    List<Task> orderedTasks,
    List<double[]> initialPopulation,
    Random rand,
    InsertionMode insertionMode
  ) throws Exception {
    validateInputs(
      algorithmIndex,
      dimension,
      nPop,
      maxFes,
      satellite,
      orderedTasks,
      initialPopulation,
      rand
    );
    int maxGenerations = Math.max(1, maxFes - nPop + 1);
    int[] flagPar = new int[AlgorithmConstants.HYBCEC_PARENT_ALGORITHM_COUNT];
    flagPar[algorithmIndex] = 1;
    state.setNPop(nPop);
    state.setFlagPar(flagPar);
    configManager.initialize(flagPar, maxGenerations, nPop);
    initializeAlgorithmState(flagPar, nPop, dimension, rand);

    List<double[]> population = copyPopulation(initialPopulation);
    List<Double> fitness = evaluatePopulation(population, satellite, orderedTasks, insertionMode);
    List<double[]> pbestSolution = copyPopulation(population);
    List<Double> pbestFit = new ArrayList<>(fitness);
    double[] gbestSolution = null;
    double gbestFit = Double.NEGATIVE_INFINITY;
    for (int i = 0; i < population.size(); i++) {
      if (fitness.get(i) > gbestFit) {
        gbestFit = fitness.get(i);
        gbestSolution = population.get(i).clone();
      }
    }

    iterationCount = population.size();
    int generation = 0;
    List<Double> bestFitnessByGeneration = new ArrayList<>();
    List<Integer> evalsByGeneration = new ArrayList<>();
    bestFitnessByGeneration.add(gbestFit);
    evalsByGeneration.add(iterationCount);
    ParentStrategy strategy = createStrategy(
      algorithmIndex,
      satellite,
      orderedTasks,
      insertionMode
    );

    while (iterationCount < maxFes) {
      generation++;
      int batchSize = Math.min(population.size(), maxFes - iterationCount);
      int[] selectedIndices = buildSelectedIndices(batchSize);
      List<Boolean> updateMask = buildUpdateMask(population.size(), batchSize);
      StrategyInput input = StrategyInput.builder()
        .population(population)
        .selectedIndices(selectedIndices)
        .populationFitness(fitness)
        .pbestSolution(pbestSolution)
        .gbestSolution(gbestSolution)
        .indToBeUpdated(updateMask)
        .build();
      ParentContext context = buildContext(
        algorithmIndex,
        dimension,
        satellite,
        orderedTasks,
        rand,
        generation,
        maxFes,
        batchSize,
        insertionMode
      );
      int evaluationsBeforeStrategy = iterationCount;
      ParentUpdateResult updateResult = strategy.applyWithResult(context, input);
      List<double[]> candidates = updateResult.getUpdatedPopulation();
      if (candidates.size() != selectedIndices.length) {
        throw new IllegalStateException(
          "Single algorithm candidate count mismatch: expected=" + selectedIndices.length
            + ", actual=" + candidates.size()
        );
      }
      List<Double> candidateFitness;
      if (updateResult.hasEvaluatedFitness()) {
        candidateFitness = new ArrayList<>(updateResult.getEvaluatedFitness());
        if (iterationCount == evaluationsBeforeStrategy) {
          incrementIteration(candidateFitness.size());
        }
      } else {
        candidateFitness = evaluatePopulation(candidates, satellite, orderedTasks, insertionMode);
        incrementIteration(candidateFitness.size());
      }

      for (int i = 0; i < candidates.size(); i++) {
        int popIndex = selectedIndices[i];
        if (candidateFitness.get(i) >= fitness.get(popIndex)) {
          population.set(popIndex, candidates.get(i).clone());
          fitness.set(popIndex, candidateFitness.get(i));
        }
        if (candidateFitness.get(i) > pbestFit.get(popIndex)) {
          pbestSolution.set(popIndex, candidates.get(i).clone());
          pbestFit.set(popIndex, candidateFitness.get(i));
        }
        if (fitness.get(popIndex) > gbestFit) {
          gbestFit = fitness.get(popIndex);
          gbestSolution = population.get(popIndex).clone();
        }
      }

      bestFitnessByGeneration.add(gbestFit);
      evalsByGeneration.add(iterationCount);
      logGenerationIfEnabled(algorithmIndex, satellite.getId(), generation, iterationCount, gbestFit);
    }

    return new Result(
      gbestSolution,
      gbestFit,
      generation,
      bestFitnessByGeneration,
      evalsByGeneration
    );
  }

  private static void validateInputs(
    int algorithmIndex,
    int dimension,
    int nPop,
    int maxFes,
    Satellite satellite,
    List<Task> orderedTasks,
    List<double[]> initialPopulation,
    Random rand
  ) {
    if (algorithmIndex < 0 || algorithmIndex >= AlgorithmConstants.HYBCEC_PARENT_ALGORITHM_COUNT) {
      throw new IllegalArgumentException("Invalid algorithm index: " + algorithmIndex);
    }
    if (dimension <= 0 || nPop <= 0 || maxFes <= 0) {
      throw new IllegalArgumentException("dimension, nPop, and maxFes must be positive");
    }
    if (satellite == null || orderedTasks == null || initialPopulation == null || rand == null) {
      throw new IllegalArgumentException(
        "satellite, orderedTasks, initialPopulation, and rand cannot be null"
      );
    }
    if (initialPopulation.isEmpty()) {
      throw new IllegalArgumentException("initialPopulation cannot be empty");
    }
    if (maxFes < initialPopulation.size()) {
      throw new IllegalArgumentException(
        "maxFes must cover initial population: maxFes=" + maxFes
          + ", initialPopulation=" + initialPopulation.size()
      );
    }
  }

  private void initializeAlgorithmState(int[] flagPar, int nPop, int dimension, Random rand) {
    if (flagPar[AlgorithmConstants.ALGORITHM_INDEX_ABC] == 1) {
      limitABC = ListUtils.zeros1DList(nPop);
    }
    if (flagPar[AlgorithmConstants.ALGORITHM_INDEX_SA] == 1) {
      double sigmaInit = SACont.SA_INITIAL_SIGMA_RATIO * (2 * AlgorithmConstants.DEFAULT_BOUND);
      sigma = ListUtils.fill2DList(nPop, dimension, sigmaInit);
    }
    if (flagPar[AlgorithmConstants.ALGORITHM_INDEX_PSO] == 1) {
      PSO.PSOConfig cfg = configManager.getPsoConfig();
      psoVelocity = PSO.initVelocity(nPop, dimension, cfg, rand);
    }
  }

  private static List<double[]> copyPopulation(List<double[]> population) {
    List<double[]> copy = new ArrayList<>(population.size());
    for (double[] individual : population) {
      copy.add(individual.clone());
    }
    return copy;
  }

  private static List<Double> evaluatePopulation(
    List<double[]> population,
    Satellite satellite,
    List<Task> orderedTasks,
    InsertionMode insertionMode
  ) {
    return PlanUtils.evaluatePop(population, satellite, orderedTasks, insertionMode);
  }

  private static int[] buildSelectedIndices(int batchSize) {
    int[] selectedIndices = new int[batchSize];
    for (int i = 0; i < batchSize; i++) {
      selectedIndices[i] = i;
    }
    return selectedIndices;
  }

  private static List<Boolean> buildUpdateMask(int populationSize, int batchSize) {
    List<Boolean> updateMask = new ArrayList<>(populationSize);
    for (int i = 0; i < populationSize; i++) {
      updateMask.add(i < batchSize);
    }
    return updateMask;
  }

  private ParentContext buildContext(
    int algorithmIndex,
    int dimension,
    Satellite satellite,
    List<Task> orderedTasks,
    Random rand,
    int generation,
    int maxFes,
    int count,
    InsertionMode insertionMode
  ) {
    ParentContext context = ParentContext.builder()
      .setD(dimension)
      .setSatellite(satellite)
      .setAllTasks(orderedTasks)
      .setRand(rand)
      .setGenSupplier(() -> generation)
      .setItera(iterationCount)
      .setMaxItera(maxFes)
      .setCount(count)
      .setStateUpdater(this)
      .setHybCecState(state)
      .setSingleEvaluator(chromosome -> PlanUtils.evaluateVector(
        chromosome,
        orderedTasks,
        satellite,
        insertionMode,
        singleDecodeWorkspace
      ))
      .setSequenceEvaluator(sequence -> PlanUtils.evaluateUniqueTaskSequence(
        sequence,
        satellite,
        insertionMode,
        sequenceDecodeWorkspace
      ))
      .build();
    if (algorithmIndex == AlgorithmConstants.ALGORITHM_INDEX_ABC) {
      return context.withABCContext(new ParentContext.ABCContext(
        limitABC,
        configManager.getAbcConfig(),
        pop -> evaluatePopulation(pop, satellite, orderedTasks, insertionMode)
      ));
    }
    if (algorithmIndex == AlgorithmConstants.ALGORITHM_INDEX_SA) {
      return context.withSAContext(new ParentContext.SAContext(
        sigma,
        configManager.getSaConfig(),
        pop -> evaluatePopulation(pop, satellite, orderedTasks, insertionMode)
      ));
    }
    if (algorithmIndex == AlgorithmConstants.ALGORITHM_INDEX_SHADE
        || algorithmIndex == AlgorithmConstants.ALGORITHM_INDEX_LSHADE) {
      return context.withSHADEContext(new ParentContext.SHADEContext(
        configManager.getShadeConfig(),
        shadeGG,
        state.getNPop(),
        goodFit1All,
        goodFit2All,
        badFit1All,
        badFit2All
      ));
    }
    return context;
  }

  private ParentStrategy createStrategy(
    int algorithmIndex,
    Satellite satellite,
    List<Task> orderedTasks,
    InsertionMode insertionMode
  ) {
    switch (algorithmIndex) {
      case AlgorithmConstants.ALGORITHM_INDEX_GA:
        return new GAStrategy(configManager.getGaConfig());
      case AlgorithmConstants.ALGORITHM_INDEX_PSO:
        return new PSOStrategy(configManager.getPsoConfig(), psoVelocity);
      case AlgorithmConstants.ALGORITHM_INDEX_EDA:
        return new EDAStrategy(configManager.getEdaConfig());
      case AlgorithmConstants.ALGORITHM_INDEX_ACO:
        return new ACOStrategy(configManager.getAcoConfig());
      case AlgorithmConstants.ALGORITHM_INDEX_DE_RAND_1:
        return new DERand1Strategy(configManager.getDeConfig());
      case AlgorithmConstants.ALGORITHM_INDEX_DE_RAND_2:
        return new DERand2Strategy(configManager.getDeConfig());
      case AlgorithmConstants.ALGORITHM_INDEX_DE_BEST_1:
        return new DEBest1Strategy(configManager.getDeConfig());
      case AlgorithmConstants.ALGORITHM_INDEX_DE_BEST_2:
        return new DEBest2Strategy(configManager.getDeConfig());
      case AlgorithmConstants.ALGORITHM_INDEX_DE_RAND_TO_BEST:
        return new DERandToBestStrategy(configManager.getDeConfig());
      case AlgorithmConstants.ALGORITHM_INDEX_DE_CURRENT_TO_BEST:
        return new DECurrentToBestStrategy(configManager.getDeConfig());
      case AlgorithmConstants.ALGORITHM_INDEX_DE_CURRENT_TO_RAND:
        return new DECurrentToRandStrategy(configManager.getDeConfig());
      case AlgorithmConstants.ALGORITHM_INDEX_ABC:
        return new ABCStrategy();
      case AlgorithmConstants.ALGORITHM_INDEX_SA:
        return new SAStrategy();
      case AlgorithmConstants.ALGORITHM_INDEX_ALNS:
        return new ALNSStrategy(
          configManager.getAlnsConfig(),
          pop -> evaluatePopulation(pop, satellite, orderedTasks, insertionMode)
        );
      case AlgorithmConstants.ALGORITHM_INDEX_SHADE:
      case AlgorithmConstants.ALGORITHM_INDEX_LSHADE:
        return new SHADEStrategy();
      default:
        throw new IllegalArgumentException("Unknown algorithm index: " + algorithmIndex);
    }
  }

  private static void logGenerationIfEnabled(
    int algorithmIndex,
    int satelliteId,
    int generation,
    int evaluations,
    double bestFitness
  ) {
    boolean enabled = Boolean.getBoolean("autohyb.generationLog")
      || Boolean.getBoolean("autohyb.generationLog.enabled");
    int interval = Integer.getInteger("autohyb.logInterval", 1);
    if (enabled && generation % Math.max(1, interval) == 0) {
      System.out.printf(
        "[SingleAlgorithmCurve] algorithm=%d satellite=%d gen=%d evals=%d bestFitness=%.6f%n",
        algorithmIndex,
        satelliteId,
        generation,
        evaluations,
        bestFitness
      );
    }
  }

  @Override
  public <T> void updateState(StateKey stateKey, List<T> stateData, List<Boolean> updateMask) {
    if (stateKey == StateKey.ABC_LIMIT) {
      updateABCState(stateData, updateMask);
    } else if (stateKey == StateKey.SA_SIGMA) {
      updateSAState(stateData, updateMask);
    } else {
      throw new IllegalArgumentException("Unsupported state key: " + stateKey);
    }
  }

  @SuppressWarnings("unchecked")
  private <T> void updateABCState(List<T> stateData, List<Boolean> updateMask) {
    if (updateMask == null || updateMask.isEmpty()) {
      limitABC = new ArrayList<>((List<Double>) stateData);
      return;
    }
    int updateIndex = 0;
    for (int i = 0; i < updateMask.size(); i++) {
      if (updateMask.get(i)) {
        limitABC.set(i, ((List<Double>) stateData).get(updateIndex++));
      }
    }
  }

  @SuppressWarnings("unchecked")
  private <T> void updateSAState(List<T> stateData, List<Boolean> updateMask) {
    if (updateMask == null || updateMask.isEmpty()) {
      sigma = copyPopulation((List<double[]>) stateData);
      return;
    }
    int updateIndex = 0;
    for (int i = 0; i < updateMask.size(); i++) {
      if (updateMask.get(i)) {
        sigma.set(i, ((List<double[]>) stateData).get(updateIndex++).clone());
      }
    }
  }

  @Override
  public void updateStates(Map<String, Object> states) {
    if (states.containsKey(StateKey.SHADE_GG.getKey())) {
      shadeGG = (Integer) states.get(StateKey.SHADE_GG.getKey());
    }
    if (states.containsKey(StateKey.SHADE_SF.getKey())) {
      shadeSF = castDoubleArrayList(states.get(StateKey.SHADE_SF.getKey()));
    }
    if (states.containsKey(StateKey.SHADE_CR.getKey())) {
      shadeCR = castDoubleList(states.get(StateKey.SHADE_CR.getKey()));
    }
    if (states.containsKey(StateKey.SHADE_FREQ.getKey())) {
      shadeFreq = castDoubleList(states.get(StateKey.SHADE_FREQ.getKey()));
    }
    if (states.containsKey(StateKey.FLAG1_SHADE.getKey())) {
      flag1Shade = (Boolean) states.get(StateKey.FLAG1_SHADE.getKey());
    }
    if (states.containsKey(StateKey.FLAG2_SHADE.getKey())) {
      flag2Shade = (Boolean) states.get(StateKey.FLAG2_SHADE.getKey());
    }
  }

  @SuppressWarnings("unchecked")
  private static List<double[]> castDoubleArrayList(Object value) {
    return value == null ? new ArrayList<>() : copyPopulation((List<double[]>) value);
  }

  @SuppressWarnings("unchecked")
  private static List<Double> castDoubleList(Object value) {
    return value == null ? new ArrayList<>() : new ArrayList<>((List<Double>) value);
  }

  @Override
  public void incrementIteration(int increment) {
    iterationCount += increment;
  }
}
