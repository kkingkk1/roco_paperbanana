package method.rkhma.legacy.core.manager;

import method.rkhma.legacy.common.AlgorithmConstants;
import method.rkhma.legacy.core.state.HybCecState;
import java.util.Arrays;
import java.util.List;

/**
 * Fitness Manager
 *
 * Responsible for handling fitness evaluation and optimal solution updates, including:
 * - Updating individual optimal solutions (pbest) and global optimal solutions (gbest)
 * - Fitness records and statistics after iteration
 * - Improvement flag and dispersion flag calculation
 *
 * Benefits of centralized management:
 * 1. Centralize fitness-related logic to facilitate maintenance
 * 2. Add fitness analysis functions
 * 3. Performance optimization
 * 4. Reduce the complexity of the HybCec class
 */
public class FitnessManager {

  /** State Manager Reference */
  private final HybCecState state;

  /** Problem Dimension */
  private final int D;

  /**
   * Construct Fitness Manager
   *
   * @param state The HybCec state manager (must not be null)
   * @param D The problem dimension (must be positive)
   * @throws IllegalArgumentException if state is null or D is non-positive
   */
  public FitnessManager(HybCecState state, int D) {
    if (state == null) {
      throw new IllegalArgumentException("State cannot be null");
    }
    if (D <= 0) {
      throw new IllegalArgumentException("Dimension must be positive, got: " + D);
    }
    this.state = state;
    this.D = D;
  }

  /**
   * Update the optimal solution
   *
   * Update individual optimal solutions (pbest) and global optimal solutions (gbest).
   * Performance optimization: single traversal to update pbest and track global minimum (gbest).
   */
  public void updateBestSolutions() {
    // HOTSPOT: this method is executed every iteration and touches many member fields.
    // Cache frequently accessed fields for the hot loop to reduce member access overhead.
    List<Double> popFitLocal = state.getPopFit();
    List<Double> pbestFitLocal = state.getPbestFit();
    List<double[]> popLocal = state.getPop();
    List<double[]> pbestSolutionLocal = state.getPbestSolution();
    int dim = D;
    int size = popFitLocal.size();

    // Early return if population is empty
    if (size == 0) {
      return;
    }

    int posBest = 0;
    double currentBest = popFitLocal.get(0);

    // Single pass: update pbest and track global minimum (gbest)
    for (int i = 0; i < size; i++) {
      double fit = popFitLocal.get(i);

      if (fit > pbestFitLocal.get(i)) {
        pbestSolutionLocal.set(i, Arrays.copyOf(popLocal.get(i), dim));
        pbestFitLocal.set(i, fit);
      }

      if (fit > currentBest) {
        currentBest = fit;
        posBest = i;
      }
    }

    // Update gbest if improved
    if (currentBest > state.getGbestFit()) {
      state.setGbestSolution(Arrays.copyOf(popLocal.get(posBest), dim));
      state.setGbestFit(currentBest);
    }
  }

  /**
   * Update after iteration
   *
   * Execute post-iteration fitness record and statistics update, including:
   * - Record the best fitness
   * - Update improvement flag and no improvement count
   * - Update dispersion flag (only when needed)
   * - Update fitness records and generation count
   *
   * @param needDispersity true if the dispersity flag is actually used (e.g., oO == SWITCH_ON_DISPERSITY)
   */
  public void afterIterationUpdate(boolean needDispersity) {
    // Record the best fitness
    double gbestFit = state.getGbestFit();
    int gen = state.getGen();
    List<Double> bestFitRecord = state.getBestFitRecord();

    // Check improvement with proper bounds validation
    double prevBest = Double.NEGATIVE_INFINITY;
    if (gen > 0 && bestFitRecord.size() > gen - 1) {
      prevBest = bestFitRecord.get(gen - 1);
    }

    if (gbestFit > prevBest) {
      state.setFlagImprove(1);
      state.setCountNoImprove(0);
    } else {
      state.setFlagImprove(0);
      state.incrementCountNoImprove();
    }

    updateLandscapeStats(gbestFit, needDispersity);

    // Record current generation's best fitness
    bestFitRecord.set(gen, gbestFit);

    // Increment generation counter only if there's space in the record array
    if (gen + 1 < bestFitRecord.size()) {
      state.incrementGen();
    }
  }

  /** Backward-compatible overload. */
  public void afterIterationUpdate() {
    afterIterationUpdate(true);
  }

  private void updateLandscapeStats(double gbestFit, boolean needDispersity) {
    List<Double> popFit = state.getPopFit();
    int size = popFit.size();
    if (size == 0) {
      state.setNeutralRatio(0.0);
      if (needDispersity) {
        state.setFlagDispersity(0);
      }
      return;
    }

    double neutralGap = Math.max(1.0, Math.abs(gbestFit))
      * AlgorithmConstants.LANDSCAPE_NEUTRAL_RELATIVE_EPSILON;
    int neutralCount = 0;
    double maxFit = Double.NEGATIVE_INFINITY;
    double minFit = Double.POSITIVE_INFINITY;

    for (int i = 0; i < size; i++) {
      double fit = popFit.get(i);
      if (Math.abs(gbestFit - fit) <= neutralGap) {
        neutralCount++;
      }
      if (needDispersity) {
        if (fit > maxFit) {
          maxFit = fit;
        }
        if (fit < minFit) {
          minFit = fit;
        }
      }
    }

    state.setNeutralRatio(neutralCount / (double) size);

    if (!needDispersity) {
      return;
    }
    if (minFit <= 0 || maxFit == minFit) {
      state.setFlagDispersity(0);
      return;
    }
    double dispersity = (maxFit - minFit) / minFit;
    state.setFlagDispersity(dispersity < AlgorithmConstants.HYBCEC_DISPERSITY_THRESHOLD ? 1 : 0);
  }
}
