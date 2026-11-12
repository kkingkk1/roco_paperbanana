package method.rkhma.legacy.core.manager;

import method.rkhma.legacy.common.AlgorithmConstants;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Sliding-window credit assignment for adaptive OA routing.
 */
public class CreditManager {

  private final int windowSize;
  private final double epsilon;
  private final Deque<CreditEntry> parent1History;
  private final Deque<CreditEntry> parent2History;

  private double parent1RewardSum;
  private double parent1EvalCostSum;
  private double parent2RewardSum;
  private double parent2EvalCostSum;

  public CreditManager(int windowSize) {
    this(windowSize, AlgorithmConstants.EPSILON);
  }

  public CreditManager(int windowSize, double epsilon) {
    if (windowSize <= 0) {
      throw new IllegalArgumentException("windowSize must be positive");
    }
    if (Double.isNaN(epsilon) || epsilon <= 0.0) {
      throw new IllegalArgumentException("epsilon must be positive");
    }
    this.windowSize = windowSize;
    this.epsilon = epsilon;
    this.parent1History = new ArrayDeque<>(windowSize);
    this.parent2History = new ArrayDeque<>(windowSize);
  }

  public void reset() {
    parent1History.clear();
    parent2History.clear();
    parent1RewardSum = 0.0;
    parent1EvalCostSum = 0.0;
    parent2RewardSum = 0.0;
    parent2EvalCostSum = 0.0;
  }

  public void recordGeneration(double parent1Reward, int parent1EvalCost, double parent2Reward, int parent2EvalCost) {
    record(parent1History, clampReward(parent1Reward), clampEvalCost(parent1EvalCost), true);
    record(parent2History, clampReward(parent2Reward), clampEvalCost(parent2EvalCost), false);
  }

  public double getParent1Credit() {
    return computeCredit(parent1RewardSum, parent1EvalCostSum, parent1History.size());
  }

  public double getParent2Credit() {
    return computeCredit(parent2RewardSum, parent2EvalCostSum, parent2History.size());
  }

  public double getParent1Ratio() {
    double credit1 = getParent1Credit();
    double credit2 = getParent2Credit();
    if (credit1 <= epsilon && credit2 <= epsilon) {
      return AlgorithmConstants.CREDIT_ROUTING_DEFAULT_RATIO;
    }
    return credit1 / (credit1 + credit2 + epsilon);
  }

  public void seedRatioForTest(double parent1Ratio) {
    double clampedRatio = Math.max(0.0, Math.min(1.0, parent1Ratio));
    reset();
    recordGeneration(clampedRatio, 1, 1.0 - clampedRatio, 1);
  }

  private void record(Deque<CreditEntry> history, double reward, int evalCost, boolean parent1) {
    CreditEntry entry = new CreditEntry(reward, evalCost);
    history.addLast(entry);
    if (parent1) {
      parent1RewardSum += reward;
      parent1EvalCostSum += evalCost;
    } else {
      parent2RewardSum += reward;
      parent2EvalCostSum += evalCost;
    }

    if (history.size() > windowSize) {
      CreditEntry removed = history.removeFirst();
      if (parent1) {
        parent1RewardSum -= removed.reward;
        parent1EvalCostSum -= removed.evalCost;
      } else {
        parent2RewardSum -= removed.reward;
        parent2EvalCostSum -= removed.evalCost;
      }
    }
  }

  private double computeCredit(double rewardSum, double evalCostSum, int windowCount) {
    if (rewardSum <= 0.0) {
      return 0.0;
    }
    return rewardSum / (evalCostSum + windowCount * epsilon);
  }

  private double clampReward(double reward) {
    if (Double.isNaN(reward) || reward <= 0.0) {
      return 0.0;
    }
    return reward;
  }

  private int clampEvalCost(int evalCost) {
    return Math.max(0, evalCost);
  }

  private static final class CreditEntry {
    private final double reward;
    private final int evalCost;

    private CreditEntry(double reward, int evalCost) {
      this.reward = reward;
      this.evalCost = evalCost;
    }
  }
}
