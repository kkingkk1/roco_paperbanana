package method.rkhma.legacy.core.manager;

import method.rkhma.legacy.common.ResizePop;
import method.rkhma.legacy.common.AlgorithmConstants;
import method.rkhma.legacy.core.config.HybCecConfigManager;
import method.rkhma.legacy.core.state.HybCecState;
import method.rkhma.legacy.algorithm.shade.UpdateArchive;
import method.rkhma.legacy.algorithm.shade.SHADELoopBrief;
import java.util.Random;
/**
 * Population Size Adjuster
 * 
 * Responsible for managing the dynamic adjustment of population size, including:
 * - L-SHADE algorithm population size adjustment
 * - Synchronization of population, fitness, pbest, and archive
 * 
 * By centrally managing population adjustment logic, it is possible to:
 * 1. Make population adjustment logic independent, making it easier to test
 * 2. Support different adjustment strategies
 * 3. Reduce the complexity of the HybCec class
 */
public class PopulationResizer {

  /** State Manager Reference */
  private final HybCecState state;

  /** Configuration Manager Reference */
  private final HybCecConfigManager configManager;

  /** Maximum number of iterations */
  private final int maxItera;

  /** Maximum population size */
  private final int maxPopSize;

  /** Minimum population size */
  private final int minPopSize;

  /** Random source owned by the current HybCec run. */
  private final Random rand;

  /**
   * Population Adjustment Results
   *
   * Immutable result object containing population resize information.
   * This encapsulates the outcome of a population size adjustment operation.
   */
  public static class ResizeResult {
    /** Whether parameter reset is needed */
    private final boolean needsParameterReset;

    /** New population size */
    private final int newNPop;

    /** Adjusted population */
    private final ResizePop.ResizeResult resizeResult;

    public ResizeResult(boolean needsParameterReset, int newNPop,
      ResizePop.ResizeResult resizeResult) {
      if (resizeResult == null) {
        throw new IllegalArgumentException("resizeResult cannot be null");
      }
      if (newNPop <= 0) {
        throw new IllegalArgumentException("newNPop must be positive, got: " + newNPop);
      }
      this.needsParameterReset = needsParameterReset;
      this.newNPop = newNPop;
      this.resizeResult = resizeResult;
    }

    /**
     * @return true if parameters need to be reset due to population size change
     */
    public boolean needsParameterReset() {
      return needsParameterReset;
    }

    /**
     * @return the new population size after adjustment
     */
    public int getNewNPop() {
      return newNPop;
    }

    /**
     * @return the detailed resize result containing adjusted population data
     */
    public ResizePop.ResizeResult getResizeResult() {
      return resizeResult;
    }
  }

  /**
   * Constructing a population size adjuster
   *
   * @param state State manager (must not be null)
   * @param configManager Configuration manager (must not be null)
   * @param maxItera Maximum number of iterations (must be positive)
   * @param maxPopSize Maximum population size (must be positive)
   * @param minPopSize Minimum population size (must be positive and <= maxPopSize)
   * @throws IllegalArgumentException if any validation fails
   */
  public PopulationResizer(HybCecState state, HybCecConfigManager configManager,
    int maxItera, int maxPopSize, int minPopSize, Random rand) {
    if (state == null) {
      throw new IllegalArgumentException("state cannot be null");
    }
    if (configManager == null) {
      throw new IllegalArgumentException("configManager cannot be null");
    }
    if (maxItera <= 0) {
      throw new IllegalArgumentException("maxItera must be positive, got: " + maxItera);
    }
    if (maxPopSize <= 0) {
      throw new IllegalArgumentException("maxPopSize must be positive, got: " + maxPopSize);
    }
    if (minPopSize <= 0) {
      throw new IllegalArgumentException("minPopSize must be positive, got: " + minPopSize);
    }
    if (minPopSize > maxPopSize) {
      throw new IllegalArgumentException(
        "minPopSize (" + minPopSize + ") cannot be greater than maxPopSize (" + maxPopSize + ")");
    }
    if (rand == null) {
      throw new IllegalArgumentException("rand cannot be null");
    }

    this.state = state;
    this.configManager = configManager;
    this.maxItera = maxItera;
    this.maxPopSize = maxPopSize;
    this.minPopSize = minPopSize;
    this.rand = rand;
  }

  /**
   * Adjust population size
   *
   * Execute L-SHADE algorithm population size adjustment logic.
   * The population size is dynamically reduced as the algorithm progresses,
   * following the L-SHADE population reduction strategy.
   *
   * @param previousPopSize The population size from the previous iteration.
   *                        This is compared with the newly calculated size to determine
   *                        if parameter reset is needed. Must be positive.
   * @return Adjustment result, containing:
   *         - needsParameterReset: true if population size changed
   *         - newNPop: the calculated new population size
   *         - resizeResult: detailed resize information including updated population data
   * @throws IllegalArgumentException if previousPopSize is not positive
   * @throws IllegalStateException if SHADE config is not initialized
   */
  public ResizeResult resizePopulation(int previousPopSize) {
    // Validate input
    if (previousPopSize <= 0) {
      throw new IllegalArgumentException(
        "previousPopSize must be positive, got: " + previousPopSize);
    }

    // Get SHADE configuration - must exist for population resizing
    SHADELoopBrief.SHADEConfig shadeConfig = configManager.getShadeConfig();
    if (shadeConfig == null) {
      throw new IllegalStateException(
        "SHADE config must be initialized before resizing population. " +
        "Ensure configManager.getShadeConfig() returns a valid config.");
    }

    // Initialize archive if not present
    UpdateArchive.ListArchive currentArchive = shadeConfig.archive;
    if (currentArchive == null) {
      currentArchive = new UpdateArchive.ListArchive(
        SHADELoopBrief.SHADE_DEFAULT_ARCHIVE_INITIAL_CAPACITY);
      shadeConfig.archive = currentArchive;
    }

    // Perform population resize
    ResizePop.ResizeResult resultResize = ResizePop.resizePop(
      state.getPop(), state.getPopFit(), state.getPbestSolution(), state.getPbestFit(),
      currentArchive,
      state.getItera(), maxItera, maxPopSize, minPopSize, rand);

    int newNPop = resultResize.nPop;
    boolean needsReset = (newNPop != previousPopSize);

    // Update the archive in the configuration object
    shadeConfig.archive = resultResize.archive;

    return new ResizeResult(needsReset, newNPop, resultResize);
  }
}
