package method.rkhma.legacy.core;
import method.rkhma.legacy.common.SearchDeadline;
import method.rkhma.legacy.core.state.HybCecState;
import method.rkhma.legacy.core.state.StateKey;
import method.rkhma.legacy.core.exception.HybCecExecutionException;
import method.rkhma.legacy.utils.ListUtils;
import method.rkhma.legacy.algorithm.de.DEConfig;
import method.rkhma.legacy.algorithm.aco.ACOGaussGenerate;
import method.rkhma.legacy.core.manager.FitnessManager;
import method.rkhma.legacy.core.calculator.HybridLevelCalculator;
import method.rkhma.legacy.core.manager.CreditManager;
import method.rkhma.legacy.algorithm.ga.GeneticAlgorithmNoSelect;
import method.rkhma.legacy.common.AlgorithmConstants;
import method.rkhma.legacy.strategy.GAStrategy;
import method.rkhma.legacy.core.config.HybCecConfigManager;
import method.rkhma.legacy.core.state.StateUpdater;
import method.rkhma.legacy.strategy.PSOStrategy;
import method.rkhma.legacy.algorithm.pso.PSO;
import method.rkhma.legacy.strategy.SHADEStrategy;
import method.rkhma.legacy.strategy.SAStrategy;
import method.rkhma.legacy.strategy.ParentContext;
import method.rkhma.legacy.strategy.EDAStrategy;
import method.rkhma.legacy.algorithm.shade.SHADELoopBrief;
import method.rkhma.legacy.core.manager.PopulationResizer;
import method.rkhma.legacy.core.manager.PopulationManager;
import method.rkhma.legacy.strategy.ALNSStrategy;
import method.rkhma.legacy.strategy.DEBest1Strategy;
import method.rkhma.legacy.strategy.DEBest2Strategy;
import method.rkhma.legacy.algorithm.abc.ABC;
import method.rkhma.legacy.strategy.ABCStrategy;
import method.rkhma.legacy.strategy.DECurrentToBestStrategy;
import method.rkhma.legacy.strategy.DECurrentToRandStrategy;
import method.rkhma.legacy.algorithm.eda.EDAGaussSam;
import method.rkhma.legacy.strategy.ParentStrategy;
import method.rkhma.legacy.strategy.ParentUpdateResult;
import method.rkhma.legacy.algorithm.sa.SACont;
import method.rkhma.legacy.strategy.ACOStrategy;
import method.rkhma.legacy.strategy.DERand1Strategy;
import method.rkhma.legacy.strategy.DERand2Strategy;
import method.rkhma.legacy.strategy.DERandToBestStrategy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import method.rkhma.legacy.model.Satellite;
import method.rkhma.legacy.model.Task;
import method.rkhma.legacy.operator.insertion.InsertionMode;
import method.rkhma.legacy.operator.mutation.DEUtils;
import method.rkhma.legacy.utils.CandidateArchive;
import method.rkhma.legacy.utils.PlanUtils;

/**
 * HybCec - Hybrid Evolutionary Computation Main Controller
 * 
 * After restructuring, the responsibilities of HybCec have been simplified to:
 * 1. Coordinate various managers (State, Config, PopulationResizer, Fitness, Credit)
 * 2. Execute the main loop (run method)
 * 3. Select execution mode (serial or parallel)
 * 4. Strategy selection and invocation (via the ParentStrategy interface)
 * 5. Hybrid-level computation and population preparation (core coordination logic)
 * 
 * All specific business logic has been migrated to the corresponding manager classes:
 * - HybCecState: State management
 * - HybCecConfigManager: Configuration management
 * - CreditManager: Adaptive-credit routing probability updates
 * - PopulationResizer: Population Adjustment
 * - FitnessManager: Fitness management
 * 
 * This design makes:
 * - Clear responsibilities, the main class only responsible for coordination
 * - Easy to test, each module can be tested independently
 * - Easy to maintain, modify a function only needs to modify the corresponding manager
 * - Easy to extend, can easily add new managers or strategies
 */
public class HybCec implements StateUpdater {
  private SearchDeadline searchDeadline = SearchDeadline.unlimited();

  public void setSearchDeadline(SearchDeadline deadline) {
    searchDeadline = java.util.Objects.requireNonNull(deadline);
  }

  @FunctionalInterface
  public interface FitnessEvaluator {
    List<Double> evaluate(List<double[]> pop);
  }

  private final class HybCecPopulationSetter implements PopulationManager.PopulationSetter {
    @Override
    public void setPop(List<double[]> pop) {
      HybCec.this.setPop(pop);
    }

    @Override
    public void setPopFit(List<Double> popFit) {
      HybCec.this.setPopFit(popFit);
    }

    @Override
    public void setPbestSolution(List<double[]> pbestSolution) {
      HybCec.this.setPbestSolution(pbestSolution);
    }

    @Override
    public void setPbestFit(List<Double> pbestFit) {
      HybCec.this.setPbestFit(pbestFit);
    }

    @Override
    public void reSetParameters(int D, int newNPop) {
      HybCec.this.reSetParameters(D, newNPop);
    }
  }

  /** State manager, centralized management of all runtime states*/
  private final HybCecState state;

  /** Configuration manager, centralized management of all algorithm configurations.*/
  private final HybCecConfigManager configManager;

  /** Population size adjuster, handles the dynamic adjustment of population size.*/
  private final PopulationResizer populationResizer;

  /** Fitness manager, responsible for fitness evaluation and optimal solution updates. */
  private final FitnessManager fitnessManager;

  /** Mixed-level calculator, handles mixed-level calculations and updates masks.*/
  private HybridLevelCalculator hybridLevelCalculator;

  /** Population manager, responsible for selection, preparation, and updating of the population. */
  private PopulationManager populationManager;

  /** Credit manager for adaptive OA routing. */
  private final CreditManager creditManager;

  private Random rand;
  private boolean enableGenerationLog = true;
  private int generationLogInterval = Integer.getInteger("autohyb.logInterval", 1);
  private boolean enableInitialPopLog = false;
  private final List<Integer> evalRecord = new ArrayList<>();

  // ----------------------------------------------------------
  // Input parameters
  // ----------------------------------------------------------
  private int parent1, parent2, D, oLRand, oO, strSurvive, maxItera;
  private int nPopInit;
  private int[] oL;
  private Satellite satellite;
  private List<Task> allTasks;

  private final FitnessEvaluator fitnessEvaluator;
  private final boolean hasExternalFitnessEvaluator;

  /** Candidate archive for storing top solutions during optimization */
  private final CandidateArchive archive;

  /** Elite evaluation configuration */
  private final boolean enableEliteEval;
  private final double eliteRatio;

  /** Phase transition ratio configuration
   *  0.0 = never transition (always use earlyPhaseMode)
   *  0.0-1.0 = phase-based (switch to latePhaseMode when progress >= ratio)
   *  1.0 = always use latePhaseMode */
  private final double phaseTransitionRatio;

  /** Insertion mode for early phase (before transition) */
  private final InsertionMode earlyPhaseMode;

  /** Insertion mode for late phase (after transition) */
  private final InsertionMode latePhaseMode;

  /** Tracks whether the current iteration used only fast-path updates.
   *  When true, updateBestSolutions() can be skipped (fast path already handles pbest/gbest). */
  private boolean fastPathOnly;

  /** Last evaluation counter where the current search process showed progress. */
  private int lastProcessImproveItera;

  /** Cached normalized diversity for G4 diversity switching. */
  private double lastNormalizedDiversity;

  /** Evaluation counter at the last diversity check. */
  private int lastDiversityCheckItera;

  private long fastPathEvalNanosTotal;
  private int fastPathEvalSamples;
  private final method.rkhma.legacy.utils.DecodeWorkspace sequenceEvalWorkspace = new method.rkhma.legacy.utils.DecodeWorkspace();

  /** Legacy batch size for parallel evaluation in fast path. */
  private static final int LEGACY_FAST_PATH_BATCH_SIZE =
    Math.max(8, 2 * Runtime.getRuntime().availableProcessors());

  private static final String FAST_PATH_BATCH_SIZE_PROPERTY = "hybcec.fastPathBatchSize";
  private static final String FAST_PATH_BATCH_SIZE_SETTING =
    System.getProperty(FAST_PATH_BATCH_SIZE_PROPERTY, "auto").trim();

  // ----------------------------------------------------------
  // Performance optimization: cached computed values
  // ----------------------------------------------------------

  private int maxPopSize, minPopSize, maxG, nPar;
  private double kElite;

  // ==========================================================
  // Result class
  // ==========================================================

  /**
   * Wrapper class for hybrid algorithm results.
   */
  public static class HybResult {
    private double[] gbestSolution;
    private double gbestFit;
    private double initialGbestFit;
    private List<Double> bestFitRecord;
    private List<Integer> evalRecord;
    private int gen;
    private CandidateArchive archive;

    public HybResult(double[] gbestSolution, double gbestFit, double initialGbestFit,
      List<Double> bestFitRecord, List<Integer> evalRecord, int gen, CandidateArchive archive) {
      this.gbestSolution = gbestSolution;
      this.gbestFit = gbestFit;
      this.initialGbestFit = initialGbestFit;
      this.bestFitRecord = bestFitRecord;
      this.evalRecord = evalRecord;
      this.gen = gen;
      this.archive = archive;
    }

    public double[] getGbestSolution() {
      return gbestSolution;
    }

    public double getGbestFit() {
      return gbestFit;
    }

    public double getInitialGbestFit() {
      return initialGbestFit;
    }

    public List<Double> getBestFitRecord() {
      return bestFitRecord;
    }

    public List<Integer> getEvalRecord() {
      return evalRecord;
    }

    public int getGen() {
      return gen;
    }

    public CandidateArchive getArchive() {
      return archive;
    }
  }

  /**
   * HybCec constructor.
   * 
   * @param parent1 Parent algorithm 1 index
   * @param parent2 Parent algorithm 2 index
   * @param D Problem dimension
   * @param nPop Initial population size
   * @param oL Operation level array
   * @param oLRand Random operation level
   * @param oO Operation order
   * @param strSurvive Survival strategy
   * @param maxItera Maximum iterations
   */
  public HybCec(int parent1, int parent2, int D, int nPop,
    int[] oL, int oLRand, int oO, int strSurvive,
    int maxItera,
    Satellite satellite, List<double[]> popIni, Random rand,
    InsertionMode insertionMode) {

    this(parent1, parent2, D, nPop, oL, oLRand, oO, strSurvive,
      maxItera, satellite, popIni, rand, null, false, 0.2, 0.0, insertionMode, insertionMode);
  }

  public HybCec(int parent1, int parent2, int D, int nPop,
    int[] oL, int oLRand, int oO, int strSurvive,
    int maxItera,
    Satellite satellite, List<double[]> popIni, Random rand,
    FitnessEvaluator fitnessEvaluator) {

    this(parent1, parent2, D, nPop, oL, oLRand, oO, strSurvive,
      maxItera, satellite, popIni, rand, fitnessEvaluator, false, 0.2, 0.0, InsertionMode.GREEDY, InsertionMode.GREEDY);
  }

  public HybCec(int parent1, int parent2, int D, int nPop,
    int[] oL, int oLRand, int oO, int strSurvive,
    int maxItera,
    Satellite satellite, List<double[]> popIni, Random rand,
    FitnessEvaluator fitnessEvaluator, boolean enableEliteEval, double eliteRatio) {

    this(parent1, parent2, D, nPop, oL, oLRand, oO, strSurvive,
      maxItera, satellite, popIni, rand, fitnessEvaluator,
      enableEliteEval, eliteRatio, 0.0, InsertionMode.GREEDY, InsertionMode.GREEDY);
  }

  public HybCec(int parent1, int parent2, int D, int nPop,
    int[] oL, int oLRand, int oO, int strSurvive,
    int maxItera,
    Satellite satellite, List<double[]> popIni, Random rand,
    FitnessEvaluator fitnessEvaluator, boolean enableEliteEval, double eliteRatio,
    double optimalRatio) {

    this(parent1, parent2, D, nPop, oL, oLRand, oO, strSurvive,
      maxItera, satellite, popIni, rand, fitnessEvaluator,
      enableEliteEval, eliteRatio, optimalRatio, InsertionMode.GREEDY, InsertionMode.MIN_TRANSITION);
  }

  public HybCec(int parent1, int parent2, int D, int nPop,
    int[] oL, int oLRand, int oO, int strSurvive,
    int maxItera,
    Satellite satellite, List<double[]> popIni, Random rand,
    FitnessEvaluator fitnessEvaluator, boolean enableEliteEval, double eliteRatio,
    double phaseTransitionRatio, InsertionMode earlyPhaseMode, InsertionMode latePhaseMode) {

    // ======================================================
    // 0. Initialize managers
    // ======================================================
    this.state = new HybCecState();
    this.configManager = new HybCecConfigManager();

    // ======================================================
    // 1. Store input parameters
    // ======================================================
    setPop(popIni);
    this.rand = rand;
    this.parent1 = parent1;
    this.parent2 = parent2;
    this.D = D;
    this.nPopInit = nPop;
    this.oL = oL;
    this.oLRand = oLRand;
    this.oO = oO;
    this.strSurvive = strSurvive;
    this.maxItera = maxItera;
    this.satellite = satellite;

    // Initialize phase transition configuration
    this.phaseTransitionRatio = phaseTransitionRatio;
    this.earlyPhaseMode = earlyPhaseMode;
    this.latePhaseMode = latePhaseMode;

    // Determine fitness evaluator based on insertion mode
    this.hasExternalFitnessEvaluator = fitnessEvaluator != null;
    if (hasExternalFitnessEvaluator) {
      this.fitnessEvaluator = fitnessEvaluator;
    } else {
      // Use dynamic insertion mode based on phase
      this.fitnessEvaluator = pop -> evaluatePopulation(pop, getCurrentInsertionMode());
    }

    // Initialize candidate archive for top-10 solutions
    this.archive = new CandidateArchive(10);

    // Initialize elite evaluation configuration
    this.enableEliteEval = enableEliteEval;
    this.eliteRatio = eliteRatio;

    // ======================================================
    // 2. Initialize main loop parameters
    // ======================================================

    this.nPar = AlgorithmConstants.HYBCEC_PARENT_ALGORITHM_COUNT;
    int[] flagPar = new int[this.nPar];
    flagPar[this.parent1] = 1;
    flagPar[this.parent2] = 1;
    state.setFlagPar(flagPar);

    // Initialize the main loop parameters in the state manager.
    state.setEnableParents(Arrays.asList(true, false));
    state.setOLReal(new int[2]);
    state.setFlagImprove(1);
    state.setCountNoImprove(0);
    state.setFlagDispersity(0);
    state.setTotalUpdatedInd(0);
    state.setGen(0);
    state.setItera(0);
    this.lastProcessImproveItera = 0;
    this.lastNormalizedDiversity = 1.0;
    this.lastDiversityCheckItera = -1;
    this.fastPathEvalNanosTotal = 0L;
    this.fastPathEvalSamples = 0;

    this.maxPopSize = this.nPopInit;
    this.minPopSize = AlgorithmConstants.HYBCEC_MIN_POP_SIZE;
    this.maxG = (maxItera - this.nPopInit) + 1;

    // Initialize the manager (needs to be done after setting maxPopSize, minPopSize, maxItera, and D)
    this.populationResizer = new PopulationResizer(state, configManager, maxItera,
      maxPopSize, minPopSize, rand);
    this.fitnessManager = new FitnessManager(state, D);
    this.creditManager = new CreditManager(AlgorithmConstants.CREDIT_ASSIGNMENT_WINDOW);

    // Records
    state.setBestFitRecord(ListUtils.zeros1DList(this.maxG));

    this.kElite = EDAGaussSam.EDA_DEFAULT_ELITE_RATIO;
    state.setCreditParent1(0.0);
    state.setCreditParent2(0.0);
    state.setCreditRatioParent1(AlgorithmConstants.CREDIT_ROUTING_DEFAULT_RATIO);
    state.resetIterationEvalCosts();
    // Initialize all algorithm configurations using the configuration manager.
    configManager.initialize(state.getFlagPar(), this.maxG, this.nPopInit);

    // Initialize SHADE runtime status parameters (if enabled)
    if (configManager.isShadeActive()) {
      state.setGoodFit1All(new ArrayList<>());
      state.setGoodFit2All(new ArrayList<>());
      state.setBadFit1All(new ArrayList<>());
      state.setBadFit2All(new ArrayList<>());

      state.setMemoryPos(1);
      state.setShadeGG(0);
    }

    // ======================================================
    // 7. Initialize data structures
    // ======================================================

    initializeDataStructures(D, this.nPopInit);
  }

  /**
   * Initialize all data structures.
   */
  private void initializeDataStructures(int D, int nPopInit) {
    // Population and fitness
    setPopFit(ListUtils.zeros1DList(nPopInit));
    state.setPbestSolution(ListUtils.zeros2DList(nPopInit, D));
    state.setPbestFit(ListUtils.fill1DListDouble(nPopInit, Double.NEGATIVE_INFINITY));
    state.setGbestSolution(new double[D]);
    state.setGbestFit(Double.NEGATIVE_INFINITY);

    // These will be reused across iterations to reduce GC pressure
    state.setX1(new ArrayList<>(nPopInit));
    state.setX2(new ArrayList<>(nPopInit));
    state.setPopFit1(new ArrayList<>(nPopInit));
    state.setPopFit2(new ArrayList<>(nPopInit));

    // Initialize the updated population list.
    state.setNewPopUpdated1(new ArrayList<>(nPopInit));
    state.setNewPopUpdated2(new ArrayList<>(nPopInit));

    // PSO params
    int[] flagPar = state.getFlagPar();
    if (flagPar[AlgorithmConstants.ALGORITHM_INDEX_PSO] == 1) {
      PSO.PSOConfig cfg = configManager.getPsoConfig();
      state.setV(PSO.initVelocity(nPopInit, D, cfg, rand));
      PSO.validateStateDimensionsForHybCec(
        state.getPbestSolution(),
        state.getGbestSolution(),
        state.getV(),
        D
      );
    }

    // ABC params
    if (flagPar[AlgorithmConstants.ALGORITHM_INDEX_ABC] == 1) {
      state.setLimitABC(ListUtils.zeros1DList(nPopInit));
    }

    // SA params
    if (flagPar[AlgorithmConstants.ALGORITHM_INDEX_SA] == 1) {
      double sigmaInit = SACont.SA_INITIAL_SIGMA_RATIO *
      (2 * AlgorithmConstants.DEFAULT_BOUND);
      state.setSigma(ListUtils.fill2DList(nPopInit, D, sigmaInit));
    }

    // Boolean flags
    rebuildBooleanFlags(nPopInit, D);
  }

  // ==========================================================
  // Parameter reset method
  // ==========================================================

  public void reSetParameters(int D, int newNPop) {

    // ======================================================
    // 1. Determine state
    // ======================================================

    int oldSize = state.getNPop();
    boolean isFirstInit = (state.getPop() == null || state.getPop().isEmpty());
    boolean sizeChanged = (oldSize != newNPop);

    if (!isFirstInit && !sizeChanged) {
      // Size unchanged, only rebuild necessary boolean flags
      rebuildBooleanFlags(newNPop, D);
      return;
    }

    // ======================================================
    // 2. Update basic parameters
    // ======================================================

    this.maxPopSize = newNPop;

    // ======================================================
    // 3. Adjust populations
    // ======================================================

    setPop(ListUtils.resize2DList(state.getPop(), newNPop, D));
    setPopFit(ListUtils.resize1DList(state.getPopFit(), newNPop, 0.0));
    state.setPbestSolution(ListUtils.resize2DList(state.getPbestSolution(), newNPop, D));
    state.setPbestFit(ListUtils.resize1DList(state.getPbestFit(), newNPop, 0.0));

    // ======================================================
    // 4. Rebuild boolean flags
    // ======================================================

    rebuildBooleanFlags(newNPop, D);

    // ======================================================
    // 5. Adjust DE parameters (depend on maxG)
    // ======================================================

    if (configManager.isDEActive()) {
      // Using Configuration Manager to Modify DE Settings
      configManager.adjustDEConfig(this.maxG);
    }

    // ======================================================
    // 6. PSO parameters
    // ======================================================

    int[] flagPar = state.getFlagPar();
    if (flagPar[AlgorithmConstants.ALGORITHM_INDEX_PSO] == 1) {
      PSO.PSOConfig cfg = configManager.getPsoConfig();
      state.setV(PSO.initVelocity(newNPop, D, cfg, rand));
      PSO.validateStateDimensionsForHybCec(
        state.getPbestSolution(),
        state.getGbestSolution(),
        state.getV(),
        D
      );
    }

    // ======================================================
    // 8. ABC parameters
    // ======================================================

    if (flagPar[AlgorithmConstants.ALGORITHM_INDEX_ABC] == 1) {
      state.setLimitABC(ListUtils.resize1DList(state.getLimitABC(), newNPop, 0.0));
    }

    // ======================================================
    // 9. SA parameters
    // ======================================================

    if (flagPar[AlgorithmConstants.ALGORITHM_INDEX_SA] == 1) {
      List<double[]> sigma = ListUtils.resize2DList(state.getSigma(), newNPop, D);
      state.setSigma(sigma);

      // Initialize sigma for newly added individuals
      if (newNPop > oldSize) {
        double sigmaInit = SACont.SA_INITIAL_SIGMA_RATIO *
        (2 * AlgorithmConstants.DEFAULT_BOUND);
        for (int i = oldSize; i < newNPop; i++) {
          for (int j = 0; j < D; j++) {
            sigma.get(i)[j] = sigmaInit;
          }
        }
      }
    }

    // ======================================================
    // 10. SHADE parameters
    // ======================================================

    if (configManager.isShadeActive()) {
      configManager.adjustSHADEConfig(newNPop);
    }
  }

  /**
   * Rebuild boolean flags (must be rebuilt on each adjustment).
   */
  private void rebuildBooleanFlags(int nPop, int D) {
    state.setIndMask1(ListUtils.fill1DListBoolean(nPop, true));
    state.setIndMask2(ListUtils.fill1DListBoolean(nPop, true));

    state.setUnitMask1(ListUtils.falseMatrix(nPop, D));
    state.setUnitMask2(ListUtils.falseMatrix(nPop, D));
    state.setResUnitMask1(ListUtils.falseMatrix(nPop, D));
    state.setResUnitMask2(ListUtils.falseMatrix(nPop, D));
  }

  // ==========================================================
  // Strategy selection and invocation (HybCec's core responsibility)
  // ==========================================================

  // HybCec's responsibility has been simplified to:
  // 1. Create strategy execution context (ParentContext)
  // 2. Select the appropriate strategy (through getParentAlgorithm)
  // 3. Call the strategy and return the result

  /**
   * Generate updated population based on the parent algorithm.
   *
   * This is the main interface for HybCec to interact with strategies, responsible for:
   * - Creating strategy execution context (ParentContext)
   * - Selecting and calling the appropriate strategy
   * - Returning the result of strategy execution
   *
   * @param pbestSolution Personal best solutions
   * @param gbestSolution Global best solution
   * @param parent Parent algorithm index
   * @param indToBeUpdated Flags of individuals to be updated
   * @param selectIndices Indices of selected individuals
   * @param X Current full population
   * @param popFit Current full fitness
   */
  public ParentUpdateResult getNewPopUpdated(
    List<double[]> pbestSolution,
    double[] gbestSolution,
    int parent,
    List<Boolean> indToBeUpdated,
    int[] selectIndices,
    List<double[]> X,
    List<Double> popFit) throws HybCecExecutionException {

    return runParentAlgorithm(
      parent, pbestSolution, gbestSolution, indToBeUpdated,
      selectIndices, X, popFit);
  }

  /**
   * This is the core method for strategy execution, responsible for:
   * 1. Creating strategy execution context (ParentContext)
   * 2. Building strategy input (StrategyInput)
   * 3. Getting strategy instance
   * 4. Calling strategy and returning result
   */
  private ParentUpdateResult runParentAlgorithm(
    int parent,
    List<double[]> pbestSolution,
    double[] gbestSolution,
    List<Boolean> indToBeUpdated,
    int[] selectIndices,
    List<double[]> X,
    List<Double> popFit) throws HybCecExecutionException {

    try {
      // Create context pairs
      ParentContext context = createParentContext(parent, indToBeUpdated);
      int bestIndex = popFit == state.getPopFit()
        ? state.getCachedBestIndex()
        : DEUtils.findBestIndexMax(popFit);
      int[] fitnessRankDesc = popFit == state.getPopFit()
        ? state.getCachedFitnessRankDesc()
        : null;

      // Build strategy input using builder pattern
      method.rkhma.legacy.strategy.StrategyInput input = method.rkhma.legacy.strategy.StrategyInput.builder()
      .population(X)
      .selectedIndices(selectIndices)
      .populationFitness(popFit)
      .bestIndex(bestIndex)
      .fitnessRankDesc(fitnessRankDesc)
      .pbestSolution(pbestSolution)
      .gbestSolution(gbestSolution)
      .indToBeUpdated(indToBeUpdated)
      .build();

      // Delegate to strategy obtained from factory; allows swapping implementations.
      ParentStrategy handler = getParentAlgorithm(parent);
      return handler.applyWithResult(context, input);
    } catch (Exception e) {
      throw new HybCecExecutionException("Failed to execute parent algorithm " + parent, e);
    }
  }

  /**
   * Create a strategy execution context object
   * 
   * Create the corresponding context based on the algorithm type, 
   *      encompassing all states and dependencies required for strategy execution.
   * This is the key to the decoupling strategy and HybCec: 
   *      the strategy obtains the required information through the context,
   *      rather than accessing HybCec directly.
   */
  private ParentContext createParentContext(int parent, List<Boolean> indToBeUpdated) {
    java.util.function.Supplier<Integer> currentGeneration = state::getGen;
    int updateCount = (int) indToBeUpdated.stream().filter(Boolean::booleanValue).count();

    // When fitnessEvaluator is provided, satellite/allTasks may be null
    // (e.g., in unit tests using synthetic fitness functions).
    // Provide safe defaults so strategies that don't need them can proceed.
    Satellite effectiveSatellite = (satellite != null) ? satellite : new Satellite();
    List<Task> effectiveAllTasks = (allTasks != null) ? allTasks : new java.util.ArrayList<>();

    // Create basic context using Builder pattern
    ParentContext baseContext = ParentContext.builder()
    .setD(D)
    .setSatellite(effectiveSatellite)
    .setAllTasks(effectiveAllTasks)
    .setRand(rand)
    .setGenSupplier(currentGeneration)
    .setItera(state.getItera())
    .setMaxItera(maxItera)
    .setCount(updateCount)
    .setStateUpdater(this)
    .setHybCecState(state)
    .setSingleEvaluator(this::evaluateSingleChromosome)
    .setSequenceEvaluator(hasExternalFitnessEvaluator ? null : this::evaluateTaskSequence)
    .build();

    // Add specific context based on the algorithm type.
    switch (parent) {
      case AlgorithmConstants.ALGORITHM_INDEX_ABC: // ABC
        ParentContext.ABCContext abcCtx = new ParentContext.ABCContext(
          state.getLimitABC(), configManager.getAbcConfig(),
          fitnessEvaluator::evaluate);
        return baseContext.withABCContext(abcCtx);
      case AlgorithmConstants.ALGORITHM_INDEX_SA: // SA
        ParentContext.SAContext saCtx = new ParentContext.SAContext(
          state.getSigma(),
          configManager.getSaConfig(),
          fitnessEvaluator::evaluate);
        return baseContext.withSAContext(saCtx);
      case AlgorithmConstants.ALGORITHM_INDEX_PSO: // PSO
        ParentContext.PSOContext psoCtx = new ParentContext.PSOContext(
          state.getV(), configManager.getPsoConfig());
        return baseContext.withPSOContext(psoCtx);
      case AlgorithmConstants.ALGORITHM_INDEX_LSHADE:
      case AlgorithmConstants.ALGORITHM_INDEX_SHADE:
        ParentContext.SHADEContext shadeCtx = new ParentContext.SHADEContext(
          configManager.getShadeConfig(),
          state.getShadeGG(),
          state.getNPop(),
          state.getGoodFit1All(),
          state.getGoodFit2All(),
          state.getBadFit1All(),
          state.getBadFit2All());
        return baseContext.withSHADEContext(shadeCtx);
      default:
        // GA, EDA, ACO, DE variants etc. only need the base context
        return baseContext;
    }
  }

  // ==========================================================
  // StateUpdater Interface Implementation
  // ==========================================================

  /**
   * Generic state update method with optional partial update mask
   */
  @Override
  public <T> void updateState(StateKey stateKey, List<T> stateData, List<Boolean> updateMask) {
    if (updateMask == null || updateMask.isEmpty()) {
      // Full update
      updateStateFull(stateKey, stateData);
    } else {
      // Partial update based on mask
      updateStatePartial(stateKey, stateData, updateMask);
    }
  }

  /**
   * Batch update multiple states
   */
  @Override
  public void updateStates(java.util.Map<String, Object> states) {
    for (java.util.Map.Entry<String, Object> entry : states.entrySet()) {
      String key = entry.getKey();
      Object value = entry.getValue();

      try {
        StateKey stateKey = StateKey.fromString(key);

        // Handle special cases for non-list types
        if (stateKey == StateKey.SHADE_GG) {
          state.setShadeGG((Integer) value);
        } else if (stateKey == StateKey.FLAG1_SHADE) {
          state.setFlag1Shade((Boolean) value);
        } else if (stateKey == StateKey.FLAG2_SHADE) {
          state.setFlag2Shade((Boolean) value);
        } else if (value instanceof List) {
          // For list types, use generic full update
          updateStateFull(stateKey, (List<?>) value);
        }
      } catch (IllegalArgumentException e) {
        // Log or handle unknown state key
        System.err.println("Warning: Unknown state key '" + key + "' in updateStates");
      }
    }
  }

  /**
   * Increment iteration counter
   */
  @Override
  public void incrementIteration(int increment) {
    state.incrementItera(increment);
  }

  // ==========================================================
  // Private Helper Methods
  // ==========================================================

  /**
   * Full update - replace entire state list
   */
  @SuppressWarnings("unchecked")
  private <T> void updateStateFull(StateKey stateKey, List<T> stateData) {
    if (stateKey == StateKey.ABC_LIMIT) {
      state.setLimitABC(new ArrayList<>((List<Double>) stateData));
    } else if (stateKey == StateKey.SA_SIGMA) {
      // Deep copy for double arrays
      List<double[]> sigmaCopy = new ArrayList<>();
      for (double[] arr : (List<double[]>) stateData) {
        sigmaCopy.add(Arrays.copyOf(arr, arr.length));
      }
      state.setSigma(sigmaCopy);
    } else if (stateKey == StateKey.SHADE_SF) {
      state.setShadeSF((List<double[]>) stateData);
    } else if (stateKey == StateKey.SHADE_CR) {
      state.setShadeCR((List<Double>) stateData);
    } else if (stateKey == StateKey.SHADE_FREQ) {
      state.setShadeFreq((List<Double>) stateData);
    } else {
      throw new IllegalArgumentException("Unknown state key: " + stateKey);
    }
  }

  /**
   * Partial update - update only elements where mask is true
   */
  @SuppressWarnings("unchecked")
  private <T> void updateStatePartial(StateKey stateKey, List<T> stateData, List<Boolean> updateMask) {
    int updateIndex = 0;
    int maskSize = updateMask.size();

    // Count expected updates
    int expectedUpdates = 0;
    for (Boolean mask : updateMask) {
      if (mask) expectedUpdates++;
    }

    // Validate stateData size matches expected updates
    if (stateData.size() != expectedUpdates) {
      throw new IllegalStateException(
        "State data size mismatch: stateData.size()=" + stateData.size()
        + ", but updateMask has " + expectedUpdates + " true values (maskSize=" + maskSize + ")"
        + ", stateKey=" + stateKey);
    }

    if (stateKey == StateKey.ABC_LIMIT) {
      List<Double> limitABC = state.getLimitABC();
      for (int i = 0; i < maskSize; i++) {
        if (updateMask.get(i)) {
          limitABC.set(i, ((List<Double>) stateData).get(updateIndex++));
        }
      }
    } else if (stateKey == StateKey.SA_SIGMA) {
      List<double[]> sigma = state.getSigma();
      for (int i = 0; i < maskSize; i++) {
        if (updateMask.get(i)) {
          double[] arr = ((List<double[]>) stateData).get(updateIndex++);
          sigma.set(i, Arrays.copyOf(arr, arr.length));
        }
      }
    } else {
      throw new IllegalArgumentException("Partial update not supported for state key: " + stateKey);
    }
  }

  /**
   * Strategy Factory Method: Returns the corresponding strategy instance based on the algorithm index.
   */
  private ParentStrategy getParentAlgorithm(final int parent) {
    switch (parent) {
      case AlgorithmConstants.ALGORITHM_INDEX_GA:
        return new GAStrategy(configManager.getGaConfig());
      case AlgorithmConstants.ALGORITHM_INDEX_PSO:
        return new PSOStrategy(configManager.getPsoConfig(), state.getV());
      case AlgorithmConstants.ALGORITHM_INDEX_EDA:
        return new EDAStrategy(configManager.getEdaConfig());
      case AlgorithmConstants.ALGORITHM_INDEX_ACO:
        return new ACOStrategy(configManager.getAcoConfig());
      case AlgorithmConstants.ALGORITHM_INDEX_DE_RAND_1:
        return new DERand1Strategy(configManager.getDeConfig());
      case AlgorithmConstants.ALGORITHM_INDEX_DE_BEST_1:
        return new DEBest1Strategy(configManager.getDeConfig());
      case AlgorithmConstants.ALGORITHM_INDEX_DE_CURRENT_TO_RAND:
        return new DECurrentToRandStrategy(configManager.getDeConfig());
      case AlgorithmConstants.ALGORITHM_INDEX_DE_RAND_2:
        return new DERand2Strategy(configManager.getDeConfig());
      case AlgorithmConstants.ALGORITHM_INDEX_DE_BEST_2:
        return new DEBest2Strategy(configManager.getDeConfig());
      case AlgorithmConstants.ALGORITHM_INDEX_DE_CURRENT_TO_BEST:
        return new DECurrentToBestStrategy(configManager.getDeConfig());
      case AlgorithmConstants.ALGORITHM_INDEX_DE_RAND_TO_BEST:
        return new DERandToBestStrategy(configManager.getDeConfig());
      case AlgorithmConstants.ALGORITHM_INDEX_ABC:
        return new ABCStrategy();
      case AlgorithmConstants.ALGORITHM_INDEX_SA:
        return new SAStrategy();
      case AlgorithmConstants.ALGORITHM_INDEX_ALNS:
        return new ALNSStrategy(
          configManager.getAlnsConfig(),
          fitnessEvaluator::evaluate);
      case AlgorithmConstants.ALGORITHM_INDEX_SHADE:
      case AlgorithmConstants.ALGORITHM_INDEX_LSHADE:
        return new SHADEStrategy();
      default:
        throw new IllegalArgumentException("Unknown parent algorithm index: " + parent);
    }
  }

  // ==========================================================
  // Main run method
  // ==========================================================

  /**
   * Main Operator - Core Coordination Logic of HybCec
   *
   * Responsibilities
   * 1. Initialization running environment
   * 2. Execute the main loop, selecting serial or combined execution based on the mode.
   * 3. Coordinate various managers to perform fitness updates and post-iteration processing.
   * 4. Return the final result
   *
   * @return HybResult containing the best solution and statistics
   * @throws HybCecExecutionException if execution fails
   */
  private void printGenerationLog(
    int generationIndex,
    int currentEvaluations,
    double bestFitness,
    long generationElapsedMillis) {
    if (!enableGenerationLog) {
      return;
    }
    int interval = Math.max(1, generationLogInterval);
    if (generationIndex != 1 && generationIndex != maxItera && generationIndex % interval != 0) {
      return;
    }
    int percent = maxItera <= 0 ? 100 : (int) Math.round(currentEvaluations * 100.0 / maxItera);
    System.out.printf(
      "[HybCecCurve][sat=%d] gen=%d | eval=%d/%d | pop=%d | best fitness=%.6f | gen elapsed=%d ms | progress=%d%%%n",
      satellite != null ? satellite.getId() : -1,
      generationIndex,
      Math.max(0, currentEvaluations),
      maxItera,
      state.getNPop(),
      bestFitness,
      generationElapsedMillis,
      Math.max(0, Math.min(100, percent)));
  }

  private void printInitialPopulationDiagnostics(List<Double> popFit, int indexBest, double bestValue) {
    int satId = satellite != null ? satellite.getId() : -1;
    int n = popFit.size();
    double sum = 0, min = Double.MAX_VALUE, max = Double.NEGATIVE_INFINITY;
    for (double f : popFit) {
      sum += f;
      if (f < min) min = f;
      if (f > max) max = f;
    }
    double mean = sum / n;
    double sqSum = 0;
    for (double f : popFit) {
      sqSum += (f - mean) * (f - mean);
    }
    double std = Math.sqrt(sqSum / n);

    // Seed individual fitness (position 0=real baseline, 1=heuristic baseline, 2=inherited/random)
    StringBuilder seedInfo = new StringBuilder();
    String[] seedLabels = {"realBaseline", "heuristicBaseline", "inherited"};
    int seedCount = Math.min(3, n);
    for (int i = 0; i < seedCount; i++) {
      if (i > 0) seedInfo.append(", ");
      String label = i < seedLabels.length ? seedLabels[i] : "seed" + i;
      seedInfo.append(String.format("%s[%d]=%.1f", label, i, popFit.get(i)));
    }

    // Best among random individuals (position 3 and beyond)
    double bestRandom = Double.NEGATIVE_INFINITY;
    int bestRandomIdx = -1;
    for (int i = 3; i < n; i++) {
      if (popFit.get(i) > bestRandom) {
        bestRandom = popFit.get(i);
        bestRandomIdx = i;
      }
    }
  }

  public HybResult run() throws HybCecExecutionException {

    // ======================================================
    // I. Initialization phase
    // ======================================================
    try {
      searchDeadline.check();
      initializeRun();

      // ======================================================
      // II. Main iteration loop (core coordination logic)
      // ======================================================
      while (state.getItera() < maxItera) {
        searchDeadline.check();
        long generationStartMillis = System.currentTimeMillis();
        int generationIndex = state.getGen() + 1;
        int iteraBefore = state.getItera();
        double gbestBeforeGeneration = state.getGbestFit();
        double meanFitBeforeGeneration = computeMeanFitness();
        state.resetIterationEvalCosts();
        if (state.getOOReal() == AlgorithmConstants.OPERATION_ORDER_REAL_PARALLEL) {
          fastPathOnly = false;
          executeCombinedMode(); // Combined mode: Prepare both OA1 and OA2, then combine results
        } else {
          executeSerialMode();  // Serial/adaptive mode: execute one or both parents in sequence.
        }

        // Skip updateBestSolutions when only fast path was used —
        // the fast path already updates pbest/gbest inline (O(cnt) instead of O(N)).
        if (!fastPathOnly) {
          fitnessManager.updateBestSolutions();
        }
        fitnessManager.afterIterationUpdate(false);
        evalRecord.add(state.getItera());
        updateProcessImprovementState(gbestBeforeGeneration, meanFitBeforeGeneration);
        recordAdaptiveCredit(gbestBeforeGeneration);

        // Apply phase-based re-evaluation if phase transition is enabled
        // This ensures the population is re-evaluated with the new insertion mode after transition
        if (phaseTransitionRatio > 0.0 && phaseTransitionRatio < 1.0 && !hasExternalFitnessEvaluator) {
          InsertionMode currentMode = getCurrentInsertionMode();
          // Check if we just transitioned (current mode is late phase, but previous wasn't)
          double progress = (double) state.getItera() / maxItera;
          double prevProgress = (double) (state.getItera() - 1) / maxItera;
          boolean justTransitioned = (progress >= phaseTransitionRatio) && (prevProgress < phaseTransitionRatio);

          if (justTransitioned) {
            // Re-evaluate entire population with new insertion mode
            List<Double> newFit = evaluatePopulation(state.getPop(), currentMode);
            setPopFit(newFit);
            // Re-update best solutions after re-evaluation
            fitnessManager.updateBestSolutions();
          }
        }

        // Apply elite evaluation if enabled (only when not using phase-based transition)
        if (enableEliteEval && phaseTransitionRatio <= 0.0 && !hasExternalFitnessEvaluator) {
          PlanUtils.applyEliteEvaluation(state.getPop(), state.getPopFit(), satellite, eliteRatio);
          // Re-update best solutions after elite evaluation
          fitnessManager.updateBestSolutions();
        }

        // Update archive with current gbest (archive handles duplicates)
        archive.addCandidate(state.getGbestSolution(), state.getGbestFit());
        long generationElapsedMillis = System.currentTimeMillis() - generationStartMillis;
        printGenerationLog(
          generationIndex,
          state.getItera(),
          state.getGbestFit(),
          generationElapsedMillis);

        // Safety guard: if no individual was updated in this iteration, throw error.
        if (state.getItera() == iteraBefore) {
          int[] oLReal = state.getOLReal();
          String oOMode = getOperationOrderModeLabel(state.getOOReal());

          throw new HybCecExecutionException(
            "No individuals were updated in iteration. This indicates a critical algorithm error. "
            + "itera=" + iteraBefore
            + ", gen=" + state.getGen()
            + ", nPop=" + state.getNPop()
            + ", hybLevel=" + state.getHybLevel()
            + ", oOReal=" + state.getOOReal() + " (" + oOMode + ")"
            + ", oL[0]=" + oL[0]
            + ", oL[1]=" + oL[1]
            + ", oLReal[0]=" + oLReal[0]
            + ", oLReal[1]=" + oLReal[1]);
        }
      }

      // ======================================================
      // III. Final elite evaluation on best solution
      // ======================================================
      searchDeadline.check();
      if (enableEliteEval && !hasExternalFitnessEvaluator) {
        // Apply elite evaluation one final time to ensure gbest is optimally evaluated
        PlanUtils.applyEliteEvaluation(state.getPop(), state.getPopFit(), satellite, eliteRatio);
        fitnessManager.updateBestSolutions();
        // Update archive with final gbest
        archive.addCandidate(state.getGbestSolution(), state.getGbestFit());
      }

      // ======================================================
      // IV. Return results
      // ======================================================
      return new HybResult(
        getGbestSolution(),
        getGbestFit(),
        getInitialGbestFit(),
        state.getBestFitRecord(),
        new ArrayList<>(evalRecord),
        state.getGen(),
        archive);
    } catch (HybCecExecutionException e) {
      throw e;
    } catch (Exception e) {
      throw new HybCecExecutionException("Unexpected error during HybCec execution", e);
    }
  }

  /**
   * Determine which insertion mode should be used based on current iteration progress.
   * @return the insertion mode for the current phase
   */
  private InsertionMode getCurrentInsertionMode() {
    if (phaseTransitionRatio <= 0.0) {
      return earlyPhaseMode;  // Never transition
    }
    if (phaseTransitionRatio >= 1.0) {
      return latePhaseMode;   // Always use late phase mode
    }
    // Use late phase mode when current iteration progress exceeds the ratio
    double progress = (double) state.getItera() / maxItera;
    boolean shouldTransition = progress >= phaseTransitionRatio;

    // Debug output (only once when first transitioning)
    if (shouldTransition && state.getItera() > 0) {
      double prevProgress = (double) (state.getItera() - 1) / maxItera;
      if (prevProgress < phaseTransitionRatio) {
        System.out.printf("[Phase Transition] Switching from %s to %s at iter %d/%d (%.1f%% >= %.1f%%)%n",
          earlyPhaseMode, latePhaseMode, state.getItera(), maxItera, progress * 100, phaseTransitionRatio * 100);
      }
    }

    return shouldTransition ? latePhaseMode : earlyPhaseMode;
  }

  private List<Double> evaluatePopulation(List<double[]> population, InsertionMode mode) {
    double[] raw = evaluatePopulationArray(population, mode);
    List<Double> results = new ArrayList<>(raw.length);
    for (double v : raw) {
      results.add(v);
    }
    return results;
  }

  private double evaluateSingleChromosome(double[] chromosome) {
    if (hasExternalFitnessEvaluator) {
      return fitnessEvaluator.evaluate(List.of(chromosome)).get(0);
    }
    List<Task> referenceTasks = allTasks != null ? allTasks : satellite.getAssignedTasks();
    return PlanUtils.evaluateVector(
      chromosome,
      referenceTasks,
      satellite,
      getCurrentInsertionMode(),
      new method.rkhma.legacy.utils.DecodeWorkspace()
    );
  }

  private double evaluateTaskSequence(List<Task> sequence) {
    return PlanUtils.evaluateUniqueTaskSequence(
      sequence,
      satellite,
      getCurrentInsertionMode(),
      sequenceEvalWorkspace
    );
  }

  private double[] evaluatePopulationArray(List<double[]> population, InsertionMode mode) {
    if (population == null || population.isEmpty()) {
      return new double[0];
    }
    double[] out = new double[population.size()];
    if (allTasks != null) {
      PlanUtils.evaluatePopInto(population, satellite, allTasks, mode, out);
    } else {
      PlanUtils.evaluatePopInto(population, satellite, mode, out);
    }
    return out;
  }

  /**
   * Extracted initialization logic from run().
   * @throws HybCecExecutionException if initialization fails
   */
  private void initializeRun() throws HybCecExecutionException {
    // Diagnostic print preserved
    List<double[]> pop = state.getPop();
    state.setNeutralRatio(0.0);
    state.setCreditParent1(0.0);
    state.setCreditParent2(0.0);
    state.setCreditRatioParent1(AlgorithmConstants.CREDIT_ROUTING_DEFAULT_RATIO);
    state.resetIterationEvalCosts();
    evalRecord.clear();
    creditManager.reset();

    // Determine current insertion mode based on phase
    InsertionMode currentMode = getCurrentInsertionMode();

    if (hasExternalFitnessEvaluator) {
      setPopFit(fitnessEvaluator.evaluate(pop));
    } else {
      setPopFitArray(evaluatePopulationArray(pop, currentMode));
    }

    // Apply elite evaluation if enabled (only when not using phase-based transition)
    if (enableEliteEval && phaseTransitionRatio <= 0.0 && !hasExternalFitnessEvaluator) {
      PlanUtils.applyEliteEvaluation(pop, state.getPopFit(), satellite, eliteRatio);
    }

    // Note: nPop is automatically synchronized via setPop() which calls state.setNPop()
    state.incrementItera(state.getNPop());

    // Initialize pbest and find gbest in a single pass
    int indexBest = 0;
    double bestValue = Double.NEGATIVE_INFINITY;
    List<Double> popFit = state.getPopFit();
    List<double[]> pbestSolution = state.getPbestSolution();

    for (int i = 0; i < state.getNPop(); i++) {
      // Initialize pbest
      pbestSolution.set(i, Arrays.copyOf(pop.get(i), D));
      // Find maximum fitness for gbest
      if (popFit.get(i) > bestValue) {
        bestValue = popFit.get(i);
        indexBest = i;
      }
    }

    setPbestFit(new ArrayList<>(popFit));

    // Initialize gbest
    updateGbestSolution(pop.get(indexBest), bestValue);
    validatePsoStateAfterBestInitialization();
    setInitialGbestFit(state.getGbestFit());
    lastProcessImproveItera = state.getItera();
    lastNormalizedDiversity = computeNormalizedPopulationDiversity();
    lastDiversityCheckItera = state.getItera();

    // Initial population diagnostics log
    if (enableInitialPopLog) {
      printInitialPopulationDiagnostics(popFit, indexBest, bestValue);
    }

    // Set operation mode
    state.setOOReal(AlgorithmConstants.getOperationOrderMode(oO));
    int[] oLReal = new int[] {
      AlgorithmConstants.getUpdateRatioOperationLevel(oL[0]),
      AlgorithmConstants.getUpdateRatioOperationLevel(oL[1])
    };
    state.setOLReal(oLReal);
    state.setHybLevel(Math.max(oLReal[0], oLReal[1]));

    this.hybridLevelCalculator = new HybridLevelCalculator(state, D, oL, maxItera, oLRand, oO, rand);

    this.populationManager = new PopulationManager(
      state, configManager, populationResizer, hybridLevelCalculator,
      satellite, D, state.getNPop(), oL, oO, strSurvive,
      this::getNewPopUpdated,
      new HybCecPopulationSetter(),
      this.fitnessEvaluator);
  }

  private void validatePsoStateAfterBestInitialization() {
    int[] flagPar = state.getFlagPar();
    if (flagPar == null || flagPar[AlgorithmConstants.ALGORITHM_INDEX_PSO] != 1) {
      return;
    }
    PSO.validateStateDimensionsForHybCec(
      state.getPbestSolution(),
      state.getGbestSolution(),
      state.getV(),
      D
    );
  }

  private void executeSerialMode() throws HybCecExecutionException {
    // Decide whether to enable OA2
    updateEnableParents();

    boolean oa1Enabled = state.getEnableParents().get(0);
    boolean oa2Enabled = state.getEnableParents().get(1);

    // Assume fast path until proven otherwise
    fastPathOnly = true;

    // Execute OA1
    if (oa1Enabled) {
      int count = hybridLevelCalculator.determineHybridLevel1();
      state.setCount(count);

      // Batched fast path: when only OA1 runs and count is small,
      // batch multiple candidates to enable parallel evaluation.
      int fastPathBatchSize = resolveFastPathBatchSize(count);
      if (count < state.getNPop() && !oa2Enabled && count <= fastPathBatchSize) {
        int remaining = maxItera - state.getItera();
        int batchSize = Math.min(fastPathBatchSize, remaining);
        try {
          long startNanos = System.nanoTime();
          int evaluated = populationManager.processParent1UpdateFastPathBatched(parent1, batchSize);
          recordFastPathEvalNanos(System.nanoTime() - startNanos, evaluated);
        } catch (Exception e) {
          throw new HybCecExecutionException("Failed batched fast path for OA1", e);
        }
        return;
      }

      // Non-batched path (OA2 also enabled, or count >= nPop)
      try {
        if (count < state.getNPop()) {
          populationManager.processParent1UpdateFastPath(parent1);
        } else {
          fastPathOnly = false;
          populationManager.preparePopSelect1();
          populationManager.processParent1Update(parent1);
        }
      } catch (Exception e) {
        throw new HybCecExecutionException("Failed to execute parent algorithm " + parent1 + " (parent index 0)", e);
      }
    }

    // Execute OA2
    if (oa2Enabled) {
      executeParentSerial(1, parent2);
    }
  }

  /**
   * Execute a parent algorithm in serial mode.
   *
   * @param parentIndex Index of the parent (0 or 1)
   * @param parentAlgorithm The algorithm to execute
   * @throws HybCecExecutionException if execution fails
   */
  private void executeParentSerial(int parentIndex, int parentAlgorithm) throws HybCecExecutionException {
    try {
      if (parentIndex == 0) {
        int count = hybridLevelCalculator.determineHybridLevel1();
        state.setCount(count);
        if (count < state.getNPop()) {
          // Fast path: skip deep copy, O(cnt) mask/eval/select
          populationManager.processParent1UpdateFastPath(parentAlgorithm);
        } else {
          fastPathOnly = false;
          populationManager.preparePopSelect1();
          populationManager.processParent1Update(parentAlgorithm);
        }
      } else {
        int count = hybridLevelCalculator.determineHybridLevel2();
        state.setCount(count);
        if (count < state.getNPop()) {
          // Fast path: skip deep copy, O(cnt) mask/eval/select
          populationManager.processParent2UpdateFastPath(parentAlgorithm);
        } else {
          fastPathOnly = false;
          populationManager.preparePopSelect2();
          populationManager.processParent2Update(parentAlgorithm);
        }
      }
    } catch (Exception e) {
      throw new HybCecExecutionException(
        "Failed to execute parent algorithm " + parentAlgorithm + " (parent index " + parentIndex + ")", e);
    }
  }

  private int resolveFastPathBatchSize(int selectCount) {
    if (!FAST_PATH_BATCH_SIZE_SETTING.equalsIgnoreCase("auto")) {
      try {
        return Math.max(1, Integer.parseInt(FAST_PATH_BATCH_SIZE_SETTING));
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException(
          FAST_PATH_BATCH_SIZE_PROPERTY + " must be auto or a positive integer: "
            + FAST_PATH_BATCH_SIZE_SETTING, e);
      }
    }

    int legacy = LEGACY_FAST_PATH_BATCH_SIZE;
    if (fastPathEvalSamples < legacy) {
      return legacy;
    }

    long avgNanos = fastPathEvalNanosTotal / Math.max(1, fastPathEvalSamples);
    int dimensionFactor = D >= 512 ? 2 : 1;
    int costFactor = avgNanos >= 1_000_000L ? 2 : 1;
    int adaptive = legacy * dimensionFactor * costFactor;
    int selectBound = Math.max(legacy, selectCount * legacy);
    return Math.max(1, Math.min(adaptive, selectBound));
  }

  private void recordFastPathEvalNanos(long elapsedNanos, int evaluated) {
    if (evaluated <= 0 || elapsedNanos <= 0) {
      return;
    }
    fastPathEvalNanosTotal += elapsedNanos;
    fastPathEvalSamples += evaluated;
  }

  /**
   * Update enableParents flags.
   */
  private void updateEnableParents() {
    List<Boolean> enableParents = state.getEnableParents();
    switch (oO) {
      case AlgorithmConstants.OPERATION_ORDER_SEQUENTIAL:
        enableParents.set(0, true);
        enableParents.set(1, true);
        break;
      case AlgorithmConstants.OPERATION_ORDER_PERIODIC_SWITCH:
        if (isPeriodicParent1Phase()) {
          enableOnlyParent(enableParents, 0);
        } else {
          enableOnlyParent(enableParents, 1);
        }
        break;
      case AlgorithmConstants.OPERATION_ORDER_STAGE_SWITCH:
        if (state.getItera() <= AlgorithmConstants.PROBABILITY_HALF * maxItera) {
          enableOnlyParent(enableParents, 0);
        } else {
          enableOnlyParent(enableParents, 1);
        }
        break;
      case AlgorithmConstants.OPERATION_ORDER_BEST_NO_IMPROVEMENT_SWITCH:
        int bestNoImproveThreshold = Math.max(1, (int) Math.ceil(
          AlgorithmConstants.OPERATION_ORDER_SWITCH_INTERVAL_RATIO * maxG));
        if (state.getCountNoImprove() >= bestNoImproveThreshold) {
          enableOnlyParent(enableParents, 1);
        } else {
          enableOnlyParent(enableParents, 0);
        }
        break;
      case AlgorithmConstants.OPERATION_ORDER_PROCESS_NO_IMPROVEMENT_SWITCH:
        if (state.getItera() - lastProcessImproveItera >= resolveSwitchIntervalItera()) {
          enableOnlyParent(enableParents, 1);
        } else {
          enableOnlyParent(enableParents, 0);
        }
        break;
      case AlgorithmConstants.OPERATION_ORDER_DIVERSITY_SWITCH:
        if (getCachedNormalizedDiversity() < AlgorithmConstants.HYBCEC_NORMALIZED_DIVERSITY_THRESHOLD) {
          enableOnlyParent(enableParents, 1);
        } else {
          enableOnlyParent(enableParents, 0);
        }
        break;
      case AlgorithmConstants.OPERATION_ORDER_ADAPTIVE_CREDIT:
        double parent1Probability = Math.max(0.0, Math.min(1.0, creditManager.getParent1Ratio()));
        state.setCreditRatioParent1(parent1Probability);
        if (rand.nextDouble() < parent1Probability) {
          enableOnlyParent(enableParents, 0);
        } else {
          enableOnlyParent(enableParents, 1);
        }
        break;
      case AlgorithmConstants.OPERATION_ORDER_PARALLEL:
        enableParents.set(0, true);
        enableParents.set(1, true);
        break;
      default:
        throw new IllegalStateException("Unsupported operation order: " + oO);
    }
  }

  private void recordAdaptiveCredit(double gbestBeforeGeneration) {
    if (oO != AlgorithmConstants.OPERATION_ORDER_ADAPTIVE_CREDIT) {
      return;
    }

    double totalImprovement = Math.max(0.0, state.getGbestFit() - gbestBeforeGeneration);
    boolean parent1Enabled = state.getEnableParents().size() > 0 && state.getEnableParents().get(0);
    boolean parent2Enabled = state.getEnableParents().size() > 1 && state.getEnableParents().get(1);
    int enabledCount = (parent1Enabled ? 1 : 0) + (parent2Enabled ? 1 : 0);

    double parent1Reward = 0.0;
    double parent2Reward = 0.0;
    if (enabledCount == 1) {
      if (parent1Enabled) {
        parent1Reward = totalImprovement;
      } else if (parent2Enabled) {
        parent2Reward = totalImprovement;
      }
    } else if (enabledCount == 2) {
      parent1Reward = totalImprovement * 0.5;
      parent2Reward = totalImprovement * 0.5;
    }

    creditManager.recordGeneration(
      parent1Reward,
      state.getIterationEvalCostParent1(),
      parent2Reward,
      state.getIterationEvalCostParent2()
    );
    state.setCreditParent1(creditManager.getParent1Credit());
    state.setCreditParent2(creditManager.getParent2Credit());
    state.setCreditRatioParent1(creditManager.getParent1Ratio());
  }

  private int resolveSwitchIntervalItera() {
    return Math.max(1, (int) Math.ceil(
      AlgorithmConstants.OPERATION_ORDER_SWITCH_INTERVAL_RATIO * maxItera));
  }

  private int resolveDiversityCheckIntervalItera() {
    return Math.max(1, (int) Math.ceil(
      AlgorithmConstants.DIVERSITY_CHECK_INTERVAL_RATIO * maxItera));
  }

  private boolean isPeriodicParent1Phase() {
    int period = resolveSwitchIntervalItera();
    int phase = (state.getItera() / period) % 2;
    return phase == 0;
  }

  private double computeMeanFitness() {
    List<Double> popFit = state.getPopFit();
    if (popFit == null || popFit.isEmpty()) {
      return Double.NEGATIVE_INFINITY;
    }
    double sum = 0.0;
    for (double fit : popFit) {
      sum += fit;
    }
    return sum / popFit.size();
  }

  private void updateProcessImprovementState(double gbestBeforeGeneration, double meanFitBeforePostUpdate) {
    boolean bestImproved = state.getGbestFit() > gbestBeforeGeneration;
    double meanFitAfter = computeMeanFitness();
    boolean meanImproved = meanFitAfter > meanFitBeforePostUpdate + AlgorithmConstants.EPSILON;
    if (bestImproved || meanImproved) {
      lastProcessImproveItera = state.getItera();
    }
  }

  private double getCachedNormalizedDiversity() {
    int checkInterval = resolveDiversityCheckIntervalItera();
    if (lastDiversityCheckItera < 0
      || state.getItera() - lastDiversityCheckItera >= checkInterval) {
      lastNormalizedDiversity = computeNormalizedPopulationDiversity();
      lastDiversityCheckItera = state.getItera();
    }
    return lastNormalizedDiversity;
  }

  private double computeNormalizedPopulationDiversity() {
    List<double[]> pop = state.getPop();
    int nPop = state.getNPop();
    if (pop == null || nPop <= 1 || D <= 0) {
      return 0.0;
    }

    double boundRange = 2.0 * AlgorithmConstants.DEFAULT_BOUND;
    if (boundRange <= 0.0) {
      return 0.0;
    }

    double diversitySum = 0.0;
    for (int j = 0; j < D; j++) {
      double mean = 0.0;
      for (int i = 0; i < nPop; i++) {
        mean += pop.get(i)[j];
      }
      mean /= nPop;

      double variance = 0.0;
      for (int i = 0; i < nPop; i++) {
        double delta = pop.get(i)[j] - mean;
        variance += delta * delta;
      }
      double std = Math.sqrt(variance / nPop);
      diversitySum += std / boundRange;
    }
    return diversitySum / D;
  }

  public void updateEnableParentsForTest() {
    updateEnableParents();
  }

  public double computeNormalizedPopulationDiversityForTest() {
    return computeNormalizedPopulationDiversity();
  }

  public void setLastProcessImproveIteraForTest(int lastProcessImproveItera) {
    this.lastProcessImproveItera = Math.max(0, lastProcessImproveItera);
  }

  public void setLastNormalizedDiversityForTest(double normalizedDiversity) {
    this.lastNormalizedDiversity = Math.max(0.0, normalizedDiversity);
    this.lastDiversityCheckItera = state.getItera();
  }

  public void setAdaptiveCreditRatioForTest(double ratio) {
    state.setCreditRatioParent1(Math.max(0.0, Math.min(1.0, ratio)));
    creditManager.seedRatioForTest(state.getCreditRatioParent1());
  }

  private void enableOnlyParent(List<Boolean> enableParents, int parentIndex) {
    enableParents.set(0, parentIndex == 0);
    enableParents.set(1, parentIndex == 1);
  }

  private String getOperationOrderModeLabel(int ooReal) {
    switch (ooReal) {
      case AlgorithmConstants.OPERATION_ORDER_REAL_SERIAL:
        return "Serial";
      case AlgorithmConstants.OPERATION_ORDER_REAL_PARALLEL:
        return "Combined";
      case AlgorithmConstants.OPERATION_ORDER_REAL_ADAPTIVE:
        return "Adaptive";
      default:
        return "Unknown";
    }
  }

  /**
   * Combined execution mode (formerly "parallel mode").
   *
   * Note: Despite the name, this executes sequentially but prepares both parent
   * populations before execution and combines their results afterward.
   * This is NOT true parallel execution using threads.
   *
   * @throws HybCecExecutionException if execution fails
   */
  private void executeCombinedMode() throws HybCecExecutionException {
    try {
      // Prepare OA1 - get count in one pass
      state.setCount(hybridLevelCalculator.determineHybridLevel1());
      populationManager.preparePopSelect1();

      // Prepare OA2 - get count in one pass
      state.setCount(hybridLevelCalculator.determineHybridLevel2ForParallel());
      populationManager.preparePopSelect2();

      // Execute updates
      ParentUpdateResult newPop1 = getNewPopUpdated(
        state.getPbestSolution(), state.getGbestSolution(),
        parent1, state.getIndMask1(),
        state.getSelectIndices1(), state.getX1(), state.getPopFit1());
      populationManager.processParallel1(parent1, newPop1);

      ParentUpdateResult newPop2 = getNewPopUpdated(
        state.getPbestSolution(), state.getGbestSolution(),
        parent2, state.getIndMask2(),
        state.getSelectIndices2(), state.getX2(), state.getPopFit2());
      populationManager.processParallel2(parent2, newPop2);

      // Combined survivor selection
      populationManager.combineSurvivorsParallel();
    } catch (HybCecExecutionException e) {
      throw e;
    } catch (Exception e) {
      throw new HybCecExecutionException("Failed to execute combined mode", e);
    }
  }

  public HybCecState getState() {
    return state;
  }

  // ==========================================================
  // Getter and Setter methods for encapsulated fields
  // ==========================================================

  // Population and fitness getters
  public List<double[]> getPop() {
    return state.getPop();
  }

  public List<Double> getPopFit() {
    return state.getPopFit();
  }

  public List<double[]> getPbestSolution() {
    return state.getPbestSolution();
  }

  public List<Double> getPbestFit() {
    return state.getPbestFit();
  }

  public double[] getGbestSolution() {
    return state.getGbestSolution();
  }

  public double getGbestFit() {
    return state.getGbestFit();
  }

  public double getInitialGbestFit() {
    return state.getInitialGbestFit();
  }

  // Population and fitness setters
  public void setPop(List<double[]> pop) {
    state.setPop(pop);
    state.setNPop(pop.size());
  }

  public void setPopFit(List<Double> popFit) {
    state.setPopFit(popFit);
  }

  public void setPopFitArray(double[] popFitArray) {
    state.setPopFitArray(popFitArray);
  }

  public void setPbestSolution(List<double[]> pbestSolution) {
    state.setPbestSolution(pbestSolution);
  }

  public void setPbestFit(List<Double> pbestFit) {
    state.setPbestFit(pbestFit);
  }

  /**
   * Update the global optimal solution, ensuring consistency between gbestFit and gbestSolution.
   * Optimized to avoid unnecessary array copying when fitness hasn't improved.
   * Also adds the solution to the candidate archive for later re-evaluation.
   *
   * @param solution The candidate solution
   * @param fitness The fitness value of the candidate solution
   */
  public void updateGbestSolution(double[] solution, double fitness) {
    // Only copy if fitness has actually improved or if this is the first update
    if (state.getGbestSolution() == null || fitness > state.getGbestFit()) {
      state.setGbestFit(fitness);
      state.setGbestSolution(Arrays.copyOf(solution, solution.length));
    }
    // Always add to archive - it handles duplicates and maintains top-K
    archive.addCandidate(solution, fitness);
  }

  public void setGbestFit(double gbestFit) {
    state.setGbestFit(gbestFit);
  }

  public void setInitialGbestFit(double initialGbestFit) {
    state.setInitialGbestFit(initialGbestFit);
  }

  // ==========================================================
  // Field accessor methods (Getter and Setter)
  // ==========================================================

  // Random
  public Random getRand() {
    return rand;
  }

  public void setRand(Random rand) {
    this.rand = rand;
  }

  // Input parameters - int
  public int getParent1() {
    return parent1;
  }

  public void setParent1(int parent1) {
    this.parent1 = parent1;
  }

  public int getParent2() {
    return parent2;
  }

  public void setParent2(int parent2) {
    this.parent2 = parent2;
  }

  public int getD() {
    return D;
  }

  public void setD(int D) {
    this.D = D;
  }

  public int getOLRand() {
    return oLRand;
  }

  public void setOLRand(int oLRand) {
    this.oLRand = oLRand;
  }

  public int getOO() {
    return oO;
  }

  public void setOO(int oO) {
    this.oO = oO;
  }

  public int getStrSurvive() {
    return strSurvive;
  }

  public void setStrSurvive(int strSurvive) {
    this.strSurvive = strSurvive;
  }

  public int getMaxItera() {
    return maxItera;
  }

  public void setMaxItera(int maxItera) {
    this.maxItera = maxItera;
  }

  public int getNPop() {
    return state.getNPop();
  }

  // Input parameters - arrays
  public int[] getOL() {
    return oL;
  }

  public void setOL(int[] oL) {
    this.oL = oL;
  }

  // Input parameters - objects
  public Satellite getSatellite() {
    return satellite;
  }

  public void setSatellite(Satellite satellite) {
    this.satellite = satellite;
  }

  public List<Task> getAllTasks() {
    return allTasks;
  }

  public void setAllTasks(List<Task> allTasks) {
    this.allTasks = allTasks;
  }

  public void setEnableGenerationLog(boolean enableGenerationLog) {
    this.enableGenerationLog = enableGenerationLog;
  }

  public void setGenerationLogInterval(int generationLogInterval) {
    if (generationLogInterval <= 0) {
      throw new IllegalArgumentException("generationLogInterval must be positive");
    }
    this.generationLogInterval = generationLogInterval;
  }

  public void setEnableInitialPopLog(boolean enableInitialPopLog) {
    this.enableInitialPopLog = enableInitialPopLog;
  }

  // Main loop parameters
  public int[] getFlagPar() {
    return state.getFlagPar();
  }

  // Performance optimization parameters - int
  public int getMaxPopSize() {
    return maxPopSize;
  }

  public void setMaxPopSize(int maxPopSize) {
    this.maxPopSize = maxPopSize;
  }

  public int getMinPopSize() {
    return minPopSize;
  }

  public void setMinPopSize(int minPopSize) {
    this.minPopSize = minPopSize;
  }

  public int getMaxG() {
    return maxG;
  }

  public void setMaxG(int maxG) {
    this.maxG = maxG;
  }

  public int getNPar() {
    return nPar;
  }

  public void setNPar(int nPar) {
    this.nPar = nPar;
  }

  // Performance optimization parameters - double
  public double getKElite() {
    return kElite;
  }

  public void setKElite(double kElite) {
    this.kElite = kElite;
  }

  // ==========================================================
  // Configuration retrieval method (for serialization)
  // ==========================================================

  /**
   * Retrieve PSO configuration 
   */
  public PSO.PSOConfig getPsoConfig() {
    return configManager.getPsoConfig();
  }

  /**
   * Retrieve GA configuration 
   */
  public GeneticAlgorithmNoSelect.GAConfig getGaConfig() {
    return configManager.getGaConfig();
  }

  /**
   * Retrieve EDA configuration 
   */
  public EDAGaussSam.EDAConfig getEdaConfig() {
    return configManager.getEdaConfig();
  }

  /**
   * Retrieve ACO configuration 
   */
  public ACOGaussGenerate.ACOConfig getAcoConfig() {
    return configManager.getAcoConfig();
  }

  /**
   * Obtain DE configuration 
   */
  public DEConfig getDeConfig() {
    return configManager.getDeConfig();
  }

  /**
   * Retrieve ABC configuration
   */
  public ABC.ABCConfig getAbcConfig() {
    return configManager.getAbcConfig();
  }

  /**
   * Obtain SA Configuration
   */
  public SACont.SAConfig getSaConfig() {
    return configManager.getSaConfig();
  }

  /**
   * Retrieve SHADE configuration
   */
  public SHADELoopBrief.SHADEConfig getShadeConfig() {
    return configManager.getShadeConfig();
  }

  /**
   * Retrieve ALNS configuration
   */
  public method.rkhma.legacy.algorithm.alns.ALNS.ALNSConfig getAlnsConfig() {
    return configManager.getAlnsConfig();
  }
}
