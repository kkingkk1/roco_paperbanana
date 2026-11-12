package method.rkhma.legacy.strategy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.function.ToDoubleFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import method.rkhma.legacy.model.Satellite;
import method.rkhma.legacy.model.Task;
import method.rkhma.legacy.core.state.HybCecState;
import method.rkhma.legacy.algorithm.alns.ALNS;
import method.rkhma.legacy.core.state.StateUpdater;
import method.rkhma.legacy.algorithm.workspace.ABCWorkspace;
import method.rkhma.legacy.algorithm.workspace.ACOWorkspace;
import method.rkhma.legacy.algorithm.workspace.DEWorkspace;
import method.rkhma.legacy.algorithm.workspace.EDAWorkspace;
import method.rkhma.legacy.algorithm.workspace.GAWorkspace;
import method.rkhma.legacy.algorithm.workspace.PSOWorkspace;
import method.rkhma.legacy.algorithm.workspace.SAWorkspace;
import method.rkhma.legacy.algorithm.workspace.SHADEWorkspace;
import method.rkhma.legacy.algorithm.abc.ABC;
import method.rkhma.legacy.algorithm.sa.SACont;
import method.rkhma.legacy.algorithm.shade.SHADELoopBrief;
import method.rkhma.legacy.algorithm.pso.PSO;

/**
 * Strategy Execution Context Object
 *
 * <p>Encapsulate the shared state and dependencies required by all strategy algorithms,
 * avoiding direct dependency on the entire HybCec object.
 *
 * <p>Through the context object, strategies can only access the states they truly need,
 * improving code decoupling and testability.
 *
 * <p>This class is immutable and thread-safe.
 */
public class ParentContext {

  // ==========================================================
  // General Context (shared by all strategies)
  // ==========================================================

  /** Problem dimension */
  private final int D;

  /** Satellite object (for evaluation) */
  private final Satellite satellite;

  /** All tasks list (for evaluation) - immutable view */
  private final List<Task> allTasks;

  /** Random number generator */
  private final Random rand;

  /** Current generation supplier (delayed retrieval to avoid state inconsistency) */
  private final Supplier<Integer> genSupplier;

  /** Current iteration count (for budget checking) */
  private final int itera;

  /** Maximum iteration count (for budget checking) */
  private final int maxItera;

  /** Number of individuals to update */
  private final int count;

  /** State updater (for updating HybCec state) */
  private final StateUpdater stateUpdater;

  /** HybCec state owner for per-run algorithm workspaces */
  private final HybCecState state;

  /** Optional single-chromosome evaluator for local-search hot paths. */
  private final ToDoubleFunction<double[]> singleEvaluator;

  /** Optional direct task-sequence evaluator for local-search hot paths. */
  private final ToDoubleFunction<List<Task>> sequenceEvaluator;

  /** ABC context (optional, only set when using ABC algorithm) */
  private final ABCContext abcContext;

  /** SA context (optional, only set when using SA algorithm) */
  private final SAContext saContext;

  /** SHADE context (optional, only set when using SHADE algorithm) */
  private final SHADEContext shadeContext;

  /** PSO context (optional, only set when using PSO algorithm) */
  private final PSOContext psoContext;

  // ==========================================================
  // Constructors
  // ==========================================================

  /**
   * Private constructor for internal use.
   *
   * @param D problem dimension (must be positive)
   * @param satellite satellite object (must not be null)
   * @param allTasks all tasks list (must not be null)
   * @param rand random number generator (must not be null)
   * @param genSupplier current generation supplier (must not be null)
   * @param itera current iteration count (must be non-negative)
   * @param maxItera maximum iteration count (must be positive)
   * @param count number of individuals to update (must be positive)
   * @param stateUpdater state updater (must not be null)
   * @param state HybCec state owner (must not be null)
   * @param abcContext ABC context (nullable)
   * @param saContext SA context (nullable)
   * @param shadeContext SHADE context (nullable)
   * @param psoContext PSO context (nullable)
   */
  private ParentContext(
    int D,
    Satellite satellite,
    List<Task> allTasks,
    Random rand,
    Supplier<Integer> genSupplier,
    int itera,
    int maxItera,
    int count,
    StateUpdater stateUpdater,
    HybCecState state,
    ToDoubleFunction<double[]> singleEvaluator,
    ToDoubleFunction<List<Task>> sequenceEvaluator,
    ABCContext abcContext,
    SAContext saContext,
    SHADEContext shadeContext,
    PSOContext psoContext) {
    // Parameter validation
    if (D <= 0) {
      throw new IllegalArgumentException("D must be positive, got: " + D);
    }
    if (maxItera <= 0) {
      throw new IllegalArgumentException("maxItera must be positive, got: " + maxItera);
    }
    if (itera < 0) {
      throw new IllegalArgumentException("itera must be non-negative, got: " + itera);
    }
    if (count <= 0) {
      throw new IllegalArgumentException("count must be positive, got: " + count);
    }

    this.D = D;
    this.satellite = Objects.requireNonNull(satellite, "satellite cannot be null");
    // Defensive copy with immutable wrapper
    this.allTasks = Collections.unmodifiableList(new ArrayList<>(
      Objects.requireNonNull(allTasks, "allTasks cannot be null")));
    this.rand = Objects.requireNonNull(rand, "rand cannot be null");
    this.genSupplier = Objects.requireNonNull(genSupplier, "genSupplier cannot be null");
    this.itera = itera;
    this.maxItera = maxItera;
    this.count = count;
    this.stateUpdater = Objects.requireNonNull(stateUpdater, "stateUpdater cannot be null");
    this.state = Objects.requireNonNull(state, "state cannot be null");
    this.singleEvaluator = singleEvaluator;
    this.sequenceEvaluator = sequenceEvaluator;
    this.abcContext = abcContext;
    this.saContext = saContext;
    this.shadeContext = shadeContext;
    this.psoContext = psoContext;
  }

  // ==========================================================
  // Getters
  // ==========================================================

  /** @return problem dimension */
  public int getD() {
    return D;
  }

  /** @return satellite object */
  public Satellite getSatellite() {
    return satellite;
  }

  /** @return immutable view of all tasks list */
  public List<Task> getAllTasks() {
    return allTasks;
  }

  /** @return random number generator */
  public Random getRand() {
    return rand;
  }

  /** @return current generation supplier */
  public Supplier<Integer> getGenSupplier() {
    return genSupplier;
  }

  /** @return current iteration count */
  public int getItera() {
    return itera;
  }

  /** @return maximum iteration count */
  public int getMaxItera() {
    return maxItera;
  }

  /** @return number of individuals to update */
  public int getCount() {
    return count;
  }

  /** @return state updater */
  public StateUpdater getStateUpdater() {
    return stateUpdater;
  }

  public DEWorkspace getDEWorkspace() {
    return state.getDEWorkspace();
  }

  public GAWorkspace getGAWorkspace() {
    return state.getGAWorkspace();
  }

  public PSOWorkspace getPSOWorkspace() {
    return state.getPSOWorkspace();
  }

  public SAWorkspace getSAWorkspace() {
    return state.getSAWorkspace();
  }

  public ACOWorkspace getACOWorkspace() {
    return state.getACOWorkspace();
  }

  public EDAWorkspace getEDAWorkspace() {
    return state.getEDAWorkspace();
  }

  public ABCWorkspace getABCWorkspace() {
    return state.getABCWorkspace();
  }

  public SHADEWorkspace getSHADEWorkspace() {
    return state.getSHADEWorkspace();
  }

  public List<ALNS.OperatorState> getAlnsDestroyOperators(ALNS.ALNSConfig config) {
    return state.getAlnsDestroyOperators(config);
  }

  public List<ALNS.OperatorState> getAlnsRepairOperators(ALNS.ALNSConfig config) {
    return state.getAlnsRepairOperators(config);
  }

  /** @return current best-fitness no-improvement counter for opt-in stagnation-aware strategies */
  public int getCountNoImprove() {
    return state.getCountNoImprove();
  }

  public Optional<ToDoubleFunction<double[]>> getSingleEvaluator() {
    return Optional.ofNullable(singleEvaluator);
  }

  public Optional<ToDoubleFunction<List<Task>>> getSequenceEvaluator() {
    return Optional.ofNullable(sequenceEvaluator);
  }

  // ==========================================================
  // Safe Context Accessors
  // ==========================================================

  /**
   * Get ABC context safely.
   *
   * @return Optional containing ABC context if present
   */
  public Optional<ABCContext> getABCContext() {
    return Optional.ofNullable(abcContext);
  }

  /**
   * Get ABC context, throwing exception if not present.
   *
   * @return ABC context
   * @throws IllegalStateException if ABC context is not initialized
   */
  public ABCContext requireABCContext() {
    if (abcContext == null) {
      throw new IllegalStateException("ABC context not initialized");
    }
    return abcContext;
  }

  /**
   * Get SA context safely.
   *
   * @return Optional containing SA context if present
   */
  public Optional<SAContext> getSAContext() {
    return Optional.ofNullable(saContext);
  }

  /**
   * Get SA context, throwing exception if not present.
   *
   * @return SA context
   * @throws IllegalStateException if SA context is not initialized
   */
  public SAContext requireSAContext() {
    if (saContext == null) {
      throw new IllegalStateException("SA context not initialized");
    }
    return saContext;
  }

  /**
   * Get SHADE context safely.
   *
   * @return Optional containing SHADE context if present
   */
  public Optional<SHADEContext> getSHADEContext() {
    return Optional.ofNullable(shadeContext);
  }

  /**
   * Get SHADE context, throwing exception if not present.
   *
   * @return SHADE context
   * @throws IllegalStateException if SHADE context is not initialized
   */
  public SHADEContext requireSHADEContext() {
    if (shadeContext == null) {
      throw new IllegalStateException("SHADE context not initialized");
    }
    return shadeContext;
  }

  /**
   * Get PSO context safely.
   *
   * @return Optional containing PSO context if present
   */
  public Optional<PSOContext> getPSOContext() {
    return Optional.ofNullable(psoContext);
  }

  /**
   * Get PSO context, throwing exception if not present.
   *
   * @return PSO context
   * @throws IllegalStateException if PSO context is not initialized
   */
  public PSOContext requirePSOContext() {
    if (psoContext == null) {
      throw new IllegalStateException("PSO context not initialized");
    }
    return psoContext;
  }

  // ==========================================================
  // Immutable Copy Methods
  // ==========================================================

  /**
   * Create a new context with ABC context set.
   *
   * @param abcContext ABC context to set
   * @return new ParentContext with ABC context
   */
  public ParentContext withABCContext(ABCContext abcContext) {
    return new ParentContext(
      this.D, this.satellite, this.allTasks, this.rand, this.genSupplier,
      this.itera, this.maxItera, this.count, this.stateUpdater,
      this.state, this.singleEvaluator, this.sequenceEvaluator,
      abcContext, this.saContext, this.shadeContext, this.psoContext);
  }

  /**
   * Create a new context with SA context set.
   *
   * @param saContext SA context to set
   * @return new ParentContext with SA context
   */
  public ParentContext withSAContext(SAContext saContext) {
    return new ParentContext(
      this.D, this.satellite, this.allTasks, this.rand, this.genSupplier,
      this.itera, this.maxItera, this.count, this.stateUpdater,
      this.state, this.singleEvaluator, this.sequenceEvaluator,
      this.abcContext, saContext, this.shadeContext, this.psoContext);
  }

  /**
   * Create a new context with SHADE context set.
   *
   * @param shadeContext SHADE context to set
   * @return new ParentContext with SHADE context
   */
  public ParentContext withSHADEContext(SHADEContext shadeContext) {
    return new ParentContext(
      this.D, this.satellite, this.allTasks, this.rand, this.genSupplier,
      this.itera, this.maxItera, this.count, this.stateUpdater,
      this.state, this.singleEvaluator, this.sequenceEvaluator,
      this.abcContext, this.saContext, shadeContext, this.psoContext);
  }

  /**
   * Create a new context with PSO context set.
   *
   * @param psoContext PSO context to set
   * @return new ParentContext with PSO context
   */
  public ParentContext withPSOContext(PSOContext psoContext) {
    return new ParentContext(
      this.D, this.satellite, this.allTasks, this.rand, this.genSupplier,
      this.itera, this.maxItera, this.count, this.stateUpdater,
      this.state, this.singleEvaluator, this.sequenceEvaluator,
      this.abcContext, this.saContext, this.shadeContext, psoContext);
  }

  // ==========================================================
  // Helper Methods
  // ==========================================================

  /**
   * Get the current generation.
   *
   * @return current generation number
   */
  public int getGen() {
    return genSupplier.get();
  }

  // ==========================================================
  // Builder Pattern
  // ==========================================================

  /**
   * Builder for creating ParentContext instances.
   *
   * <p>Example usage:
   * <pre>{@code
   * ParentContext context = ParentContext.builder()
   *     .setD(10)
   *     .setSatellite(satellite)
   *     .setAllTasks(tasks)
   *     .setRand(random)
   *     .setGenSupplier(() -> gen)
   *     .setItera(0)
   *     .setMaxItera(1000)
   *     .setCount(50)
   *     .setStateUpdater(updater)
   *     .withABCContext(abcCtx)
   *     .build();
   * }</pre>
   */
  public static class Builder {
    private int D;
    private Satellite satellite;
    private List<Task> allTasks;
    private Random rand;
    private Supplier<Integer> genSupplier;
    private int itera;
    private int maxItera;
    private int count;
    private StateUpdater stateUpdater;
    private HybCecState state;
    private ToDoubleFunction<double[]> singleEvaluator;
    private ToDoubleFunction<List<Task>> sequenceEvaluator;
    private ABCContext abcContext;
    private SAContext saContext;
    private SHADEContext shadeContext;
    private PSOContext psoContext;

    /**
     * Set problem dimension.
     *
     * @param D problem dimension (must be positive)
     * @return this builder
     */
    public Builder setD(int D) {
      this.D = D;
      return this;
    }

    /**
     * Set satellite object.
     *
     * @param satellite satellite object
     * @return this builder
     */
    public Builder setSatellite(Satellite satellite) {
      this.satellite = satellite;
      return this;
    }

    /**
     * Set all tasks list.
     *
     * @param allTasks all tasks list
     * @return this builder
     */
    public Builder setAllTasks(List<Task> allTasks) {
      this.allTasks = allTasks;
      return this;
    }

    /**
     * Set random number generator.
     *
     * @param rand random number generator
     * @return this builder
     */
    public Builder setRand(Random rand) {
      this.rand = rand;
      return this;
    }

    /**
     * Set generation supplier.
     *
     * @param genSupplier generation supplier
     * @return this builder
     */
    public Builder setGenSupplier(Supplier<Integer> genSupplier) {
      this.genSupplier = genSupplier;
      return this;
    }

    /**
     * Set current iteration.
     *
     * @param itera current iteration (must be non-negative)
     * @return this builder
     */
    public Builder setItera(int itera) {
      this.itera = itera;
      return this;
    }

    /**
     * Set maximum iteration.
     *
     * @param maxItera maximum iteration (must be positive)
     * @return this builder
     */
    public Builder setMaxItera(int maxItera) {
      this.maxItera = maxItera;
      return this;
    }

    /**
     * Set number of individuals to update.
     *
     * @param count number of individuals (must be positive)
     * @return this builder
     */
    public Builder setCount(int count) {
      this.count = count;
      return this;
    }

    /**
     * Set state updater.
     *
     * @param stateUpdater state updater
     * @return this builder
     */
    public Builder setStateUpdater(StateUpdater stateUpdater) {
      this.stateUpdater = stateUpdater;
      return this;
    }

    public Builder setHybCecState(HybCecState state) {
      this.state = state;
      return this;
    }

    /** @deprecated use {@link #setHybCecState(HybCecState)} to make the state owner explicit. */
    @Deprecated
    public Builder setState(HybCecState state) {
      return setHybCecState(state);
    }

    public Builder setSingleEvaluator(ToDoubleFunction<double[]> singleEvaluator) {
      this.singleEvaluator = singleEvaluator;
      return this;
    }

    public Builder setSequenceEvaluator(ToDoubleFunction<List<Task>> sequenceEvaluator) {
      this.sequenceEvaluator = sequenceEvaluator;
      return this;
    }

    /**
     * Set ABC context.
     *
     * @param abcContext ABC context (nullable)
     * @return this builder
     */
    public Builder withABCContext(ABCContext abcContext) {
      this.abcContext = abcContext;
      return this;
    }

    /**
     * Set SA context.
     *
     * @param saContext SA context (nullable)
     * @return this builder
     */
    public Builder withSAContext(SAContext saContext) {
      this.saContext = saContext;
      return this;
    }

    /**
     * Set SHADE context.
     *
     * @param shadeContext SHADE context (nullable)
     * @return this builder
     */
    public Builder withSHADEContext(SHADEContext shadeContext) {
      this.shadeContext = shadeContext;
      return this;
    }

    /**
     * Set PSO context.
     *
     * @param psoContext PSO context (nullable)
     * @return this builder
     */
    public Builder withPSOContext(PSOContext psoContext) {
      this.psoContext = psoContext;
      return this;
    }

    /**
     * Build the ParentContext instance.
     *
     * @return new ParentContext instance
     * @throws IllegalArgumentException if any required parameter is invalid
     * @throws NullPointerException if any required parameter is null
     */
    public ParentContext build() {
      return new ParentContext(
        D, satellite, allTasks, rand, genSupplier,
        itera, maxItera, count, stateUpdater,
        state, singleEvaluator, sequenceEvaluator,
        abcContext, saContext, shadeContext, psoContext
      );
    }
  }

  /**
   * Create a new builder instance.
   *
   * @return new Builder
   */
  public static Builder builder() {
    return new Builder();
  }

  // ==========================================================
  // ABC Context
  // ==========================================================

  /**
   * ABC algorithm specific context.
   */
  public static class ABCContext {
    /** ABC limit list */
    private final List<Double> limitABC;

    /** ABC configuration */
    private final ABC.ABCConfig abcConfig;

    /** Optional evaluator seam: if not null, ABC should use it instead of PlanUtils.evaluatePop */
    private final Function<List<double[]>, List<Double>> evaluator;

    /**
     * Create ABC context without custom evaluator.
     *
     * @param limitABC ABC limit list
     * @param abcConfig ABC configuration
     */
    public ABCContext(List<Double> limitABC, ABC.ABCConfig abcConfig) {
      this(limitABC, abcConfig, null);
    }

    /**
     * Create ABC context with custom evaluator.
     *
     * @param limitABC ABC limit list
     * @param abcConfig ABC configuration
     * @param evaluator optional custom evaluator (nullable)
     */
    public ABCContext(List<Double> limitABC, ABC.ABCConfig abcConfig,
      Function<List<double[]>, List<Double>> evaluator) {
      this.limitABC = Objects.requireNonNull(limitABC, "limitABC cannot be null");
      this.abcConfig = Objects.requireNonNull(abcConfig, "abcConfig cannot be null");
      this.evaluator = evaluator;
    }

    /** @return immutable view of ABC limit list */
    public List<Double> getLimitABC() {
      return Collections.unmodifiableList(limitABC);
    }

    /** @return ABC configuration */
    public ABC.ABCConfig getAbcConfig() {
      return abcConfig;
    }

    /** @return optional custom evaluator */
    public Optional<Function<List<double[]>, List<Double>>> getEvaluator() {
      return Optional.ofNullable(evaluator);
    }
  }

  // ==========================================================
  // SA Context
  // ==========================================================

  /**
   * SA algorithm specific context.
   */
  public static class SAContext {
    /** SA sigma list (complete list, strategy will extract the required part based on the mask) */
    private final List<double[]> sigma;

    /** SA configuration */
    private final SACont.SAConfig saConfig;

    /** Optional evaluator seam: if not null, SA should use it instead of PlanUtils.evaluatePop */
    private final Function<List<double[]>, List<Double>> evaluator;

    /**
     * Create SA context without custom evaluator.
     *
     * @param sigma SA sigma list
     * @param saConfig SA configuration
     */
    public SAContext(List<double[]> sigma, SACont.SAConfig saConfig) {
      this(sigma, saConfig, null);
    }

    /**
     * Create SA context with custom evaluator.
     *
     * @param sigma SA sigma list
     * @param saConfig SA configuration
     * @param evaluator optional custom evaluator (nullable)
     */
    public SAContext(
      List<double[]> sigma,
      SACont.SAConfig saConfig,
      Function<List<double[]>, List<Double>> evaluator) {
      this.sigma = Objects.requireNonNull(sigma, "sigma cannot be null");
      this.saConfig = Objects.requireNonNull(saConfig, "saConfig cannot be null");
      this.evaluator = evaluator;
    }

    /** @return sigma list */
    public List<double[]> getSigma() {
      return sigma;
    }

    /** @return SA configuration */
    public SACont.SAConfig getSaConfig() {
      return saConfig;
    }

    /** @return optional custom evaluator */
    public Optional<Function<List<double[]>, List<Double>>> getEvaluator() {
      return Optional.ofNullable(evaluator);
    }
  }

  // ==========================================================
  // SHADE Context
  // ==========================================================

  /**
   * SHADE algorithm specific context.
   */
  public static class SHADEContext {
    /** SHADE configuration */
    private final SHADELoopBrief.SHADEConfig shadeConfig;

    /** SHADE current generation counter */
    private final int shadeGG;

    /** Initial population size */
    private final int nPop;

    /** Good fitness values list 1 */
    private final List<Double> goodFit1All;

    /** Good fitness values list 2 */
    private final List<Double> goodFit2All;

    /** Bad fitness values list 1 */
    private final List<Double> badFit1All;

    /** Bad fitness values list 2 */
    private final List<Double> badFit2All;

    /**
     * Create SHADE context.
     *
     * @param shadeConfig SHADE configuration
     * @param shadeGG SHADE current generation counter
     * @param nPop initial population size (must be positive)
     * @param goodFit1All good fitness values list 1
     * @param goodFit2All good fitness values list 2
     * @param badFit1All bad fitness values list 1
     * @param badFit2All bad fitness values list 2
     */
    public SHADEContext(
      SHADELoopBrief.SHADEConfig shadeConfig,
      int shadeGG,
      int nPop,
      List<Double> goodFit1All,
      List<Double> goodFit2All,
      List<Double> badFit1All,
      List<Double> badFit2All) {
      if (nPop <= 0) {
        throw new IllegalArgumentException("nPop must be positive, got: " + nPop);
      }
      this.shadeConfig = Objects.requireNonNull(shadeConfig, "shadeConfig cannot be null");
      this.shadeGG = shadeGG;
      this.nPop = nPop;
      this.goodFit1All = Objects.requireNonNull(goodFit1All, "goodFit1All cannot be null");
      this.goodFit2All = Objects.requireNonNull(goodFit2All, "goodFit2All cannot be null");
      this.badFit1All = Objects.requireNonNull(badFit1All, "badFit1All cannot be null");
      this.badFit2All = Objects.requireNonNull(badFit2All, "badFit2All cannot be null");
    }

    /** @return SHADE configuration */
    public SHADELoopBrief.SHADEConfig getShadeConfig() {
      return shadeConfig;
    }

    /** @return SHADE current generation counter */
    public int getShadeGG() {
      return shadeGG;
    }

    /** @return initial population size */
    public int getNPop() {
      return nPop;
    }

    /** @return good fitness values list 1 */
    public List<Double> getGoodFit1All() {
      return goodFit1All;
    }

    /** @return good fitness values list 2 */
    public List<Double> getGoodFit2All() {
      return goodFit2All;
    }

    /** @return bad fitness values list 1 */
    public List<Double> getBadFit1All() {
      return badFit1All;
    }

    /** @return bad fitness values list 2 */
    public List<Double> getBadFit2All() {
      return badFit2All;
    }
  }

  // ==========================================================
  // PSO Context
  // ==========================================================

  /**
   * PSO algorithm specific context.
   */
  public static class PSOContext {
    /** PSO velocity matrix */
    private final List<double[]> velocity;

    /** PSO configuration */
    private final PSO.PSOConfig psoConfig;

    /**
     * Create PSO context.
     *
     * @param velocity PSO velocity matrix
     * @param psoConfig PSO configuration
     */
    public PSOContext(List<double[]> velocity, PSO.PSOConfig psoConfig) {
      this.velocity = Objects.requireNonNull(velocity, "velocity cannot be null");
      this.psoConfig = Objects.requireNonNull(psoConfig, "psoConfig cannot be null");
    }

    /** @return PSO velocity matrix */
    public List<double[]> getVelocity() {
      return velocity;
    }

    /** @return PSO configuration */
    public PSO.PSOConfig getPsoConfig() {
      return psoConfig;
    }
  }
}
