package method.rkhma.legacy.main;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import method.rkhma.legacy.common.TuningConfig;
import method.rkhma.legacy.common.TuningDefaults;

public class TargetSatelliteRanker {
  private static final int ORDINARY_TOP_K = TuningConfig.getInt(
    "ranker.ordinaryTopK",
    TuningDefaults.RANKER_ORDINARY_TOP_K
  );
  private static final int PRIORITIZED_TOP_K = TuningConfig.getInt(
    "ranker.prioritizedTopK",
    TuningDefaults.RANKER_PRIORITIZED_TOP_K
  );
  // Folded pair-score weights. The count/length values include legacy
  // window-compatibility contributions.
  private static final double CHEAP_PAIR_WINDOW_COUNT_WEIGHT = TuningConfig.getDouble("ranker.cheapPairWindowCountWeight", TuningDefaults.RANKER_CHEAP_PAIR_WINDOW_COUNT_WEIGHT);
  private static final double CHEAP_PAIR_WINDOW_LENGTH_WEIGHT = TuningConfig.getDouble("ranker.cheapPairWindowLengthWeight", TuningDefaults.RANKER_CHEAP_PAIR_WINDOW_LENGTH_WEIGHT);
  private static final double CHEAP_PAIR_EARLIEST_START_WEIGHT = TuningConfig.getDouble("ranker.cheapPairEarliestStartWeight", TuningDefaults.RANKER_CHEAP_PAIR_EARLIEST_START_WEIGHT);
  private static final double CHEAP_PAIR_TASK_SAT_KEY_WEIGHT = TuningConfig.getDouble("ranker.cheapPairTaskSatKeyWeight", TuningDefaults.RANKER_CHEAP_PAIR_TASK_SAT_KEY_WEIGHT);

  public List<Integer> rank(RankRequest request) {
    if (request == null || request.candidates == null || request.candidates.isEmpty()) {
      return Collections.emptyList();
    }
    List<RankedCandidate> ranked = cheapRank(request);
    int topK = resolveTopK(request, ranked.size());
    return buildTopSatelliteIds(ranked, topK);
  }

  private List<RankedCandidate> cheapRank(RankRequest request) {
    double maxWindowCount = 1.0;
    double maxWindowLength = 1.0;
    double minEarliestStart = Double.POSITIVE_INFINITY;
    for (SatelliteCandidateContext candidate : request.candidates) {
      maxWindowCount = Math.max(maxWindowCount, candidate.windowCount);
      maxWindowLength = Math.max(maxWindowLength, candidate.totalWindowLength);
      if (Double.isFinite(candidate.earliestStart)) {
        minEarliestStart = Math.min(minEarliestStart, candidate.earliestStart);
      }
    }
    if (!Double.isFinite(minEarliestStart)) {
      minEarliestStart = 0.0;
    }
    List<RankedCandidate> ranked = new ArrayList<>(request.candidates.size());
    for (SatelliteCandidateContext candidate : request.candidates) {
      PairFeatures pairFeatures = extractPairFeatures(candidate, maxWindowCount, maxWindowLength, minEarliestStart);
      double score = computeCheapScore(pairFeatures);
      ranked.add(new RankedCandidate(candidate, pairFeatures, score, 0));
    }
    ranked.sort((left, right) -> compareByScore(right.cheapScore, left.cheapScore, left.satelliteId, right.satelliteId));
    return assignRanks(ranked);
  }

  private int resolveTopK(RankRequest request, int candidateCount) {
    if (candidateCount <= 0) {
      return 0;
    }
    int configuredTopK = request.prioritizedTask ? PRIORITIZED_TOP_K : ORDINARY_TOP_K;
    return Math.min(candidateCount, Math.max(1, configuredTopK));
  }

  private List<Integer> buildTopSatelliteIds(List<RankedCandidate> rankedCandidates, int topK) {
    List<Integer> topSatelliteIds = new ArrayList<>();
    for (int i = 0; i < Math.min(topK, rankedCandidates.size()); i++) {
      topSatelliteIds.add(rankedCandidates.get(i).satelliteId);
    }
    return topSatelliteIds;
  }

  private int compareByScore(double rightScore, double leftScore, int leftSatelliteId, int rightSatelliteId) {
    int scoreCompare = Double.compare(rightScore, leftScore);
    if (scoreCompare != 0) {
      return scoreCompare;
    }
    return Integer.compare(leftSatelliteId, rightSatelliteId);
  }

  private List<RankedCandidate> assignRanks(List<RankedCandidate> ranked) {
    List<RankedCandidate> withRanks = new ArrayList<>(ranked.size());
    for (int index = 0; index < ranked.size(); index++) {
      RankedCandidate candidate = ranked.get(index);
      withRanks.add(new RankedCandidate(
        candidate.context,
        candidate.pairFeatures,
        candidate.cheapScore,
        index + 1
      ));
    }
    return withRanks;
  }

  private double computeCheapScore(PairFeatures pairFeatures) {
    return CHEAP_PAIR_WINDOW_COUNT_WEIGHT * pairFeatures.windowCountScore
      + CHEAP_PAIR_WINDOW_LENGTH_WEIGHT * pairFeatures.windowLengthScore
      + CHEAP_PAIR_EARLIEST_START_WEIGHT * pairFeatures.earliestStartScore
      + CHEAP_PAIR_TASK_SAT_KEY_WEIGHT * pairFeatures.taskSatelliteKeyScore;
  }

  private PairFeatures extractPairFeatures(
    SatelliteCandidateContext candidate,
    double maxWindowCount,
    double maxWindowLength,
    double minEarliestStart
  ) {
    double windowCountScore = clamp01(safeDivide(candidate.windowCount, maxWindowCount));
    double windowLengthScore = clamp01(safeDivide(candidate.totalWindowLength, maxWindowLength));
    double earliestStartScore = Double.isFinite(candidate.earliestStart)
      ? clamp01(1.0 / (1.0 + Math.max(0.0, candidate.earliestStart - minEarliestStart)))
      : 0.0;
    return new PairFeatures(
      windowCountScore,
      windowLengthScore,
      earliestStartScore,
      clamp01(candidate.taskSatelliteKey)
    );
  }

  private double safeDivide(double numerator, double denominator) {
    if (Math.abs(denominator) <= 1.0e-12) {
      return 0.0;
    }
    return numerator / denominator;
  }

  private double clamp01(double value) {
    if (Double.isNaN(value)) {
      return 0.0;
    }
    return Math.max(0.0, Math.min(1.0, value));
  }

  public static class RankRequest {
    final List<SatelliteCandidateContext> candidates;
    final boolean prioritizedTask;

    public RankRequest(
      List<SatelliteCandidateContext> candidates,
      boolean prioritizedTask
    ) {
      this.candidates = candidates;
      this.prioritizedTask = prioritizedTask;
    }

    /** Compatibility constructor for callers from the legacy selector path. */
    public RankRequest(
      List<SatelliteCandidateContext> candidates,
      boolean prioritizedTask,
      double ignoredPriorityScore,
      double ignoredTightnessScore,
      double ignoredUnscheduledPressure
    ) {
      this(candidates, prioritizedTask);
    }
  }

  public static class SatelliteCandidateContext {
    final int satelliteId;
    final int windowCount;
    final double totalWindowLength;
    final double earliestStart;
    final double taskSatelliteKey;

    public SatelliteCandidateContext(
      int satelliteId,
      int windowCount,
      double totalWindowLength,
      double earliestStart,
      double taskSatelliteKey
    ) {
      this.satelliteId = satelliteId;
      this.windowCount = windowCount;
      this.totalWindowLength = totalWindowLength;
      this.earliestStart = earliestStart;
      this.taskSatelliteKey = taskSatelliteKey;
    }
  }

  public static class RankedCandidate {
    final int satelliteId;
    final SatelliteCandidateContext context;
    final PairFeatures pairFeatures;
    final double cheapScore;
    final int cheapRank;

    RankedCandidate(
      SatelliteCandidateContext context,
      PairFeatures pairFeatures,
      double cheapScore,
      int cheapRank
    ) {
      this.context = context;
      this.satelliteId = context.satelliteId;
      this.pairFeatures = pairFeatures;
      this.cheapScore = cheapScore;
      this.cheapRank = cheapRank;
    }
  }

  static class PairFeatures {
    final double windowCountScore;
    final double windowLengthScore;
    final double earliestStartScore;
    final double taskSatelliteKeyScore;

    PairFeatures(
      double windowCountScore,
      double windowLengthScore,
      double earliestStartScore,
      double taskSatelliteKeyScore
    ) {
      this.windowCountScore = windowCountScore;
      this.windowLengthScore = windowLengthScore;
      this.earliestStartScore = earliestStartScore;
      this.taskSatelliteKeyScore = taskSatelliteKeyScore;
    }
  }
}
