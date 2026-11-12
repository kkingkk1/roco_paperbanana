package method.rkhma.legacy.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import method.rkhma.legacy.model.Satellite;
import method.rkhma.legacy.model.ScheduledTask;
import method.rkhma.legacy.model.SchedulingPlan;
import method.rkhma.legacy.model.Task;
import method.rkhma.legacy.operator.insertion.InsertionMode;

/**
 * Alternative Optimal Sequence Library
 * Used to store sequences with high fitness during the search process,
 * and ultimately obtain the final optimal sequence through generateScheduleOptimal optimization.
 *
 * Optimized version with:
 * - Binary insertion for better performance
 * - Duplicate detection mechanism
 * - Parallel evaluation support
 * - Thread safety
 * - Statistics tracking
 */
public class CandidateArchive {

  /**
   * Candidate sequence entry (inner class)
   */
  private static class CandidateEntry {
    final double[] sequence;  // Encoded sequence
    final double fitness;     // Fitness value

    CandidateEntry(double[] sequence, double fitness) {
      this.sequence = sequence != null ? sequence.clone() : null;
      this.fitness = fitness;
    }
  }

  private final List<CandidateEntry> candidates;
  private final int maxSize;  // Maximum capacity of the library
  private final double similarityThreshold;  // Similarity threshold for duplicate detection
  private final boolean enableParallel;  // Enable parallel processing

  // Statistics
  private final AtomicInteger totalAddAttempts = new AtomicInteger(0);
  private final AtomicInteger successfulAdds = new AtomicInteger(0);
  private final AtomicInteger duplicateRejections = new AtomicInteger(0);
  private final AtomicInteger replacements = new AtomicInteger(0);

  /**
   * Constructor with default settings
   */
  public CandidateArchive(int maxSize) {
    this(maxSize, 0.01, true);  // Default similarity threshold and enable parallel
  }

  /**
   * Constructor with custom settings
   * @param maxSize Maximum capacity of the library
   * @param similarityThreshold Threshold for duplicate detection (0.0 to disable)
   * @param enableParallel Enable parallel processing in getOptimalSequence
   */
  public CandidateArchive(int maxSize, double similarityThreshold, boolean enableParallel) {
    if (maxSize <= 0) {
      throw new IllegalArgumentException("Max size must be positive");
    }
    this.maxSize = maxSize;
    this.similarityThreshold = similarityThreshold;
    this.enableParallel = enableParallel;
    this.candidates = new ArrayList<>(maxSize);
  }

  /**
   * Add candidate sequence to the library
   * Optimized with binary insertion and duplicate detection
   */
  public synchronized void addCandidate(double[] sequence, double fitness) {
    if (sequence == null) {
      return;
    }

    totalAddAttempts.incrementAndGet();

    // Check for duplicates if enabled
    if (similarityThreshold > 0 && isDuplicate(sequence)) {
      duplicateRejections.incrementAndGet();
      return;
    }

    // If the library is not full, add using binary insertion
    if (candidates.size() < maxSize) {
      int insertPos = binarySearchInsertPosition(fitness);
      candidates.add(insertPos, new CandidateEntry(sequence, fitness));
      successfulAdds.incrementAndGet();
      return;
    }

    // If the library is full, check if the new candidate can replace the worst one
    CandidateEntry worst = candidates.get(candidates.size() - 1);
    if (fitness > worst.fitness) {
      candidates.remove(candidates.size() - 1);
      int insertPos = binarySearchInsertPosition(fitness);
      candidates.add(insertPos, new CandidateEntry(sequence, fitness));
      replacements.incrementAndGet();
      successfulAdds.incrementAndGet();
    }
  }

  /**
   * Binary search to find the correct insertion position (maintains descending order)
   */
  private int binarySearchInsertPosition(double fitness) {
    int left = 0;
    int right = candidates.size();

    while (left < right) {
      int mid = left + (right - left) / 2;
      if (candidates.get(mid).fitness > fitness) {
        left = mid + 1;
      } else {
        right = mid;
      }
    }

    return left;
  }

  /**
   * Check if a sequence is too similar to existing ones
   */
  private boolean isDuplicate(double[] sequence) {
    for (CandidateEntry entry : candidates) {
      if (calculateDistance(sequence, entry.sequence) < similarityThreshold) {
        return true;
      }
    }
    return false;
  }

  /**
   * Calculate Euclidean distance between two sequences
   */
  private double calculateDistance(double[] seq1, double[] seq2) {
    if (seq1.length != seq2.length) {
      return Double.MAX_VALUE;
    }

    double sum = 0.0;
    for (int i = 0; i < seq1.length; i++) {
      double diff = seq1[i] - seq2[i];
      sum += diff * diff;
    }

    return Math.sqrt(sum);
  }

  /**
   * Add a batch of candidate sequences (optimized version)
   */
  public synchronized void addCandidates(List<double[]> sequences, List<Double> fitnesses) {
    if (sequences == null || fitnesses == null) {
      throw new IllegalArgumentException("Sequences and fitnesses cannot be null");
    }
    if (sequences.size() != fitnesses.size()) {
      throw new IllegalArgumentException(
        "Sequences size (" + sequences.size() + ") must match fitnesses size (" + fitnesses.size() + ")");
    }

    if (sequences.isEmpty()) {
      return;
    }

    // Collect all valid new candidates
    List<CandidateEntry> newCandidates = new ArrayList<>();

    for (int i = 0; i < sequences.size(); i++) {
      double[] sequence = sequences.get(i);
      double fitness = fitnesses.get(i);

      if (sequence == null) {
        continue;
      }

      totalAddAttempts.incrementAndGet();

      // Check for duplicates if enabled
      if (similarityThreshold > 0 && isDuplicate(sequence)) {
        duplicateRejections.incrementAndGet();
        continue;
      }

      newCandidates.add(new CandidateEntry(sequence, fitness));
    }

    if (newCandidates.isEmpty()) {
      return;
    }

    // Merge with existing candidates
    candidates.addAll(newCandidates);

    // Sort all candidates by fitness (descending)
    candidates.sort((e1, e2) -> Double.compare(e2.fitness, e1.fitness));

    // Keep only the top maxSize candidates
    int originalSize = candidates.size();
    if (candidates.size() > maxSize) {
      candidates.subList(maxSize, candidates.size()).clear();
      replacements.addAndGet(originalSize - maxSize);
    }

    successfulAdds.addAndGet(newCandidates.size());
  }

  /**
   * Get the optimal sequence from the library
   * Optimized with parallel processing support
   */
  public synchronized SchedulingPlan getOptimalSequence(Satellite satellite, List<Task> allTasks) {
    if (satellite == null || allTasks == null) {
      throw new IllegalArgumentException("Satellite and task list cannot be null");
    }

    if (candidates.isEmpty()) {
      return null;
    }

    if (enableParallel && candidates.size() > 2) {
      return getOptimalSequenceParallel(satellite, allTasks);
    } else {
      return getOptimalSequenceSequential(satellite, allTasks);
    }
  }

  /**
   * Sequential version of getOptimalSequence
   */
  private SchedulingPlan getOptimalSequenceSequential(Satellite satellite, List<Task> allTasks) {
    SchedulingPlan bestPlan = null;
    double bestFitness = Double.NEGATIVE_INFINITY;

    for (CandidateEntry entry : candidates) {
      SchedulingPlan plan = PlanUtils.decode(entry.sequence, allTasks);

      // Method 1: Use generateSchedule (greedy mode)
      SchedulingPlan plan1 = plan.copy();
      List<ScheduledTask> schedule1 = PlanUtils.generateSchedule(
        plan1, satellite, InsertionMode.fromUseOptimal(false)
      );
      double fitness1 = (double) PlanUtils.calculateProfit(schedule1);

      // Method 2: Use generateSchedule (optimal mode)
      SchedulingPlan plan2 = plan.copy();
      List<ScheduledTask> schedule2 = PlanUtils.generateSchedule(
        plan2, satellite, InsertionMode.fromUseOptimal(true)
      );
      double fitness2 = (double) PlanUtils.calculateProfit(schedule2);

      // Select the better result
      double candidateFitness = Math.max(fitness1, fitness2);
      SchedulingPlan candidatePlan = (fitness2 > fitness1) ? plan2 : plan1;

      if (candidateFitness > bestFitness) {
        bestFitness = candidateFitness;
        bestPlan = candidatePlan;
      }
    }

    return bestPlan;
  }

  /**
   * Parallel version of getOptimalSequence
   */
  private SchedulingPlan getOptimalSequenceParallel(Satellite satellite, List<Task> allTasks) {
    class EvaluationResult {
      SchedulingPlan plan;
      double fitness;

      EvaluationResult(SchedulingPlan plan, double fitness) {
        this.plan = plan;
        this.fitness = fitness;
      }
    }

    // Parallel evaluation of all candidates
    List<EvaluationResult> results = candidates.parallelStream()
    .map(entry -> {
        SchedulingPlan plan = PlanUtils.decode(entry.sequence, allTasks);

        // Method 1: Use generateSchedule (greedy mode)
        SchedulingPlan plan1 = plan.copy();
        List<ScheduledTask> schedule1 = PlanUtils.generateSchedule(
        plan1, satellite, InsertionMode.fromUseOptimal(false)
      );
        double fitness1 = (double) PlanUtils.calculateProfit(schedule1);

        // Method 2: Use generateSchedule (optimal mode)
        SchedulingPlan plan2 = plan.copy();
        List<ScheduledTask> schedule2 = PlanUtils.generateSchedule(
        plan2, satellite, InsertionMode.fromUseOptimal(true)
      );
        double fitness2 = (double) PlanUtils.calculateProfit(schedule2);

        // Select the better result
        if (fitness2 > fitness1) {
          return new EvaluationResult(plan2, fitness2);
        } else {
          return new EvaluationResult(plan1, fitness1);
        }
      })
    .collect(Collectors.toList());

    // Find the best result
    EvaluationResult best = results.stream()
    .max((r1, r2) -> Double.compare(r1.fitness, r2.fitness))
    .orElse(null);

    return best != null ? best.plan : null;
  }

  /**
   * Get the current number of candidates in the database
   */
  public int size() {
    return candidates.size();
  }

  /**
   * Check if the library is empty
   */
  public boolean isEmpty() {
    return candidates.isEmpty();
  }

  /**
   * Clear the library
   */
  public void clear() {
    candidates.clear();
  }

  /**
   * Get the maximum capacity of the library
   */
  public int getMaxSize() {
    return maxSize;
  }

  /**
   * Get the best fitness value in the library
   */
  public double getBestFitness() {
    if (candidates.isEmpty()) {
      return Double.NEGATIVE_INFINITY;
    }
    return candidates.get(0).fitness;
  }

  /**
   * Get the best re-evaluated fitness by trying both greedy and optimal insertion
   * for all candidates and returning the maximum fitness found.
   *
   * @param satellite The satellite to evaluate against
   * @param allTasks All tasks for decoding
   * @return The best fitness after re-evaluation, or NEGATIVE_INFINITY if empty
   */
  public synchronized double getBestReEvaluatedFitness(Satellite satellite, List<Task> allTasks) {
    if (satellite == null || allTasks == null) {
      throw new IllegalArgumentException("Satellite and task list cannot be null");
    }
    if (candidates.isEmpty()) {
      return Double.NEGATIVE_INFINITY;
    }

    if (enableParallel && candidates.size() > 2) {
      return candidates.parallelStream()
      .mapToDouble(entry -> evaluateCandidateBothModes(entry.sequence, satellite, allTasks))
      .max()
      .orElse(Double.NEGATIVE_INFINITY);
    } else {
      double bestFitness = Double.NEGATIVE_INFINITY;
      for (CandidateEntry entry : candidates) {
        double fitness = evaluateCandidateBothModes(entry.sequence, satellite, allTasks);
        if (fitness > bestFitness) {
          bestFitness = fitness;
        }
      }
      return bestFitness;
    }
  }

  /**
   * Evaluate a candidate sequence using both greedy and optimal insertion modes,
   * returning the better fitness.
   */
  private double evaluateCandidateBothModes(double[] sequence, Satellite satellite, List<Task> allTasks) {
    SchedulingPlan plan = PlanUtils.decode(sequence, allTasks);

    // Greedy mode
    SchedulingPlan plan1 = plan.copy();
    List<ScheduledTask> schedule1 = PlanUtils.generateSchedule(
      plan1, satellite, InsertionMode.fromUseOptimal(false)
    );
    double fitness1 = (double) PlanUtils.calculateProfit(schedule1);

    // Optimal mode
    SchedulingPlan plan2 = plan.copy();
    List<ScheduledTask> schedule2 = PlanUtils.generateSchedule(
      plan2, satellite, InsertionMode.fromUseOptimal(true)
    );
    double fitness2 = (double) PlanUtils.calculateProfit(schedule2);

    return Math.max(fitness1, fitness2);
  }

  /**
   * Return deep copies of the top-k highest-fitness solutions in the archive.
   *
   * @param k Number of solutions requested
   * @return List of solutions in descending fitness order (deep copies), length <= k
   */
  public synchronized List<double[]> getTopSolutions(int k) {
    if (k <= 0 || candidates.isEmpty()) {
      return new ArrayList<>();
    }
    int count = Math.min(k, candidates.size());
    List<double[]> result = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      result.add(candidates.get(i).sequence.clone());
    }
    return result;
  }

  /**
   * Get statistics about the archive
   */
  public ArchiveStatistics getStatistics() {
    return new ArchiveStatistics(
      totalAddAttempts.get(),
      successfulAdds.get(),
      duplicateRejections.get(),
      replacements.get(),
      candidates.size(),
      maxSize,
      candidates.isEmpty() ? Double.NEGATIVE_INFINITY : getBestFitness(),
      candidates.isEmpty() ? Double.NEGATIVE_INFINITY : candidates.get(candidates.size() - 1).fitness
    );
  }

  /**
   * Reset statistics counters
   */
  public void resetStatistics() {
    totalAddAttempts.set(0);
    successfulAdds.set(0);
    duplicateRejections.set(0);
    replacements.set(0);
  }

  /**
   * Archive statistics holder class
   */
  public static class ArchiveStatistics {
    private final int totalAddAttempts;
    private final int successfulAdds;
    private final int duplicateRejections;
    private final int replacements;
    private final int currentSize;
    private final int maxSize;
    private final double bestFitness;
    private final double worstFitness;

    public ArchiveStatistics(int totalAddAttempts, int successfulAdds,
      int duplicateRejections, int replacements,
      int currentSize, int maxSize,
      double bestFitness, double worstFitness) {
      this.totalAddAttempts = totalAddAttempts;
      this.successfulAdds = successfulAdds;
      this.duplicateRejections = duplicateRejections;
      this.replacements = replacements;
      this.currentSize = currentSize;
      this.maxSize = maxSize;
      this.bestFitness = bestFitness;
      this.worstFitness = worstFitness;
    }

    public int getTotalAddAttempts() { return totalAddAttempts; }
    public int getSuccessfulAdds() { return successfulAdds; }
    public int getDuplicateRejections() { return duplicateRejections; }
    public int getReplacements() { return replacements; }
    public int getCurrentSize() { return currentSize; }
    public int getMaxSize() { return maxSize; }
    public double getBestFitness() { return bestFitness; }
    public double getWorstFitness() { return worstFitness; }

    public double getAcceptanceRate() {
      return totalAddAttempts > 0 ? (double) successfulAdds / totalAddAttempts : 0.0;
    }

    public double getDuplicateRate() {
      return totalAddAttempts > 0 ? (double) duplicateRejections / totalAddAttempts : 0.0;
    }

    public double getReplacementRate() {
      return successfulAdds > 0 ? (double) replacements / successfulAdds : 0.0;
    }

    @Override
    public String toString() {
      return String.format(
        "ArchiveStatistics{size=%d/%d, attempts=%d, successful=%d (%.2f%%), " +
        "duplicates=%d (%.2f%%), replacements=%d (%.2f%%), " +
        "fitness=[%.2f, %.2f]}",
        currentSize, maxSize,
        totalAddAttempts, successfulAdds, getAcceptanceRate() * 100,
        duplicateRejections, getDuplicateRate() * 100,
        replacements, getReplacementRate() * 100,
        bestFitness, worstFitness
      );
    }
  }
}
