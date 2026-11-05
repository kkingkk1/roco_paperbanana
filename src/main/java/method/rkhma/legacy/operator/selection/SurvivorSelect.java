package method.rkhma.legacy.operator.selection;
import method.rkhma.legacy.common.AlgorithmConstants;
import method.rkhma.legacy.common.RandomProvider;
import java.util.stream.IntStream;
import java.util.Random;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.stream.Collectors;

public class SurvivorSelect {

  /**
   * Survivor selection result wrapper.
   */
  public static class SurvivorSelectResult {
    /** Population after selection */
    public final List<double[]> pop;

    /** Fitness after selection */
    public final List<Double> popFit;

    public SurvivorSelectResult(List<double[]> pop, List<Double> popFit) {
      this.pop = pop;
      this.popFit = popFit;
    }

    // Getter methods
    public List<double[]> getPop() { return pop; }
    public List<Double> getPopFit() { return popFit; }
  }

  public static SurvivorSelectResult survivorSelect(
    List<double[]> offspringPop,
    List<double[]> parentPop,
    List<Double> offspringFit,
    List<Double> parentFit,
    int nPop,
    int strSurvive) {
    return survivorSelect(offspringPop, parentPop, offspringFit, parentFit, nPop, strSurvive,
      RandomProvider.newWithSeed(AlgorithmConstants.FIXED_RANDOM_SEED_LONG));
  }

  public static SurvivorSelectResult survivorSelect(
    List<double[]> offspringPop,
    List<double[]> parentPop,
    List<Double> offspringFit,
    List<Double> parentFit,
    int nPop,
    int strSurvive,
    Random random) {

    switch (strSurvive) {
      case AlgorithmConstants.SURVIVOR_SELECT_STRATEGY_ELITIST_BEST_N:
        return survivorSelectElitistBestN(offspringPop, parentPop, offspringFit, parentFit, nPop);
      case AlgorithmConstants.SURVIVOR_SELECT_STRATEGY_TOURNAMENT:
        return survivorSelectChampion(offspringPop, parentPop, offspringFit, parentFit, nPop,
          AlgorithmConstants.DEFAULT_TOURNAMENT_SIZE, random);
      case AlgorithmConstants.SURVIVOR_SELECT_STRATEGY_PAIRWISE_GREEDY:
        return survivorSelectPairwiseGreedy(offspringPop, parentPop, offspringFit, parentFit);
      case AlgorithmConstants.SURVIVOR_SELECT_STRATEGY_RANK_WEIGHTED:
        return survivorSelectRankBased(offspringPop, parentPop, offspringFit, parentFit, nPop, random);
      default:
        throw new IllegalArgumentException("Unknown survivor selection strategy: " + strSurvive);
    }
  }

  public static SurvivorSelectResult survivorSelectElitistBestN(
    List<double[]> offspringPop,
    List<double[]> parentPop,
    List<Double> offspringFit,
    List<Double> parentFit,
    int nPop) {

    validateInputs(offspringPop, parentPop, offspringFit, parentFit);

    // =====================================================
    // Step 1: Merge populations and fitness
    // =====================================================

    MergedPopulation merged = mergePopsAndFitness(offspringPop, parentPop, offspringFit, parentFit);

    // =====================================================
    // Step 2: Sort by fitness (maximization: descending order)
    // =====================================================

    List<Integer> sortIndex = IntStream.range(0, merged.fitness.size())
    .boxed()
    .sorted(Comparator.comparingDouble(merged.fitness::get).reversed())
    .collect(Collectors.toList());

    // =====================================================
    // Step 3: Select the best N
    // =====================================================

    List<double[]> popNew = new ArrayList<>();
    List<Double> popFitNew = new ArrayList<>();

    int targetN = (parentPop != null) ? parentPop.size() : nPop;
    if (targetN > sortIndex.size()) {
      throw new IllegalStateException(
        "survivorSelectElitistBestN: insufficient merged size: targetN=" + targetN
        + ", merged=" + sortIndex.size()
        + ", offspringPop=" + (offspringPop != null ? offspringPop.size() : -1)
        + ", parentPop=" + (parentPop != null ? parentPop.size() : -1)
        + ", nPop=" + nPop);
    }

    for (int i = 0; i < targetN; i++) {
      int bestIdx = sortIndex.get(i);
      popNew.add(merged.individuals.get(bestIdx));
      popFitNew.add(merged.fitness.get(bestIdx));
    }

    return new SurvivorSelectResult(popNew, popFitNew);
  }

  /** Compatibility wrapper for the old elitist best-N method name. */
  public static SurvivorSelectResult survivorSelectPermutation(
    List<double[]> offspringPop,
    List<double[]> parentPop,
    List<Double> offspringFit,
    List<Double> parentFit,
    int nPop) {
    return survivorSelectElitistBestN(offspringPop, parentPop, offspringFit, parentFit, nPop);
  }

  public static SurvivorSelectResult survivorSelectChampion(
    List<double[]> offspringPop,
    List<double[]> parentPop,
    List<Double> offspringFit,
    List<Double> parentFit,
    int nPop,
    int kTournament,
    Random random) {

    validateInputs(offspringPop, parentPop, offspringFit, parentFit);

    MergedPopulation merged = mergePopsAndFitness(offspringPop, parentPop, offspringFit, parentFit);
    int sizeMerge = merged.fitness.size();

    List<double[]> popNew = new ArrayList<>(nPop);
    List<Double> popFitNew = new ArrayList<>(nPop);

    for (int i = 0; i < nPop; i++) {
      // Tournament: pick k random indices, keep the best
      int bestIdx = random.nextInt(sizeMerge);
      double bestFit = merged.fitness.get(bestIdx);

      for (int j = 1; j < kTournament; j++) {
        int idx = random.nextInt(sizeMerge);
        double fit = merged.fitness.get(idx);
        if (fit > bestFit) {
          bestFit = fit;
          bestIdx = idx;
        }
      }

      popNew.add(merged.individuals.get(bestIdx));
      popFitNew.add(bestFit);
    }

    return new SurvivorSelectResult(popNew, popFitNew);
  }

  public static SurvivorSelectResult survivorSelectPairwiseGreedy(
    List<double[]> offspringPop,
    List<double[]> parentPop,
    List<Double> offspringFit,
    List<Double> parentFit) {

    validateInputs(offspringPop, parentPop, offspringFit, parentFit);

    int N = parentPop.size();
    List<double[]> popNew = new ArrayList<>();
    List<Double> popFitNew = new ArrayList<>();

    for (int i = 0; i < N; i++) {
      if (offspringFit.get(i) > parentFit.get(i)) {
        popNew.add(offspringPop.get(i));
        popFitNew.add(offspringFit.get(i));
      } else {
        popNew.add(parentPop.get(i));
        popFitNew.add(parentFit.get(i));
      }
    }

    return new SurvivorSelectResult(popNew, popFitNew);
  }

  /** Compatibility wrapper for the old pairwise-greedy method name. */
  public static SurvivorSelectResult survivorSelectBasedSerialNumber(
    List<double[]> offspringPop,
    List<double[]> parentPop,
    List<Double> offspringFit,
    List<Double> parentFit) {
    return survivorSelectPairwiseGreedy(offspringPop, parentPop, offspringFit, parentFit);
  }

  public static SurvivorSelectResult survivorSelectRankBased(
    List<double[]> offspringPop,
    List<double[]> parentPop,
    List<Double> offspringFit,
    List<Double> parentFit,
    int nPop,
    Random random) {

    validateInputs(offspringPop, parentPop, offspringFit, parentFit);

    // =====================================================
    // Step 1: Merge populations and fitness
    // =====================================================

    MergedPopulation merged = mergePopsAndFitness(offspringPop, parentPop, offspringFit, parentFit);

    // =====================================================
    // Step 2: Convert absolute fitness to rank order
    // =====================================================

    List<Integer> rankedIndices = IntStream.range(0, merged.fitness.size())
    .boxed()
    .sorted(Comparator.comparingDouble(merged.fitness::get).reversed())
    .collect(Collectors.toList());

    // =====================================================
    // Step 3: Build cumulative rank weights
    // =====================================================

    double totalWeight = 0.0;
    int mergedSize = rankedIndices.size();
    List<Double> cumulativeWeights = new ArrayList<>(mergedSize);
    for (int rank = 0; rank < mergedSize; rank++) {
      totalWeight += (mergedSize - rank);
      cumulativeWeights.add(totalWeight);
    }

    List<double[]> popNew = new ArrayList<>();
    List<Double> popFitNew = new ArrayList<>();

    for (int i = 0; i < nPop; i++) {
      double draw = random.nextDouble() * totalWeight;
      int selectedRank = 0;
      while (selectedRank < cumulativeWeights.size() - 1
        && draw >= cumulativeWeights.get(selectedRank)) {
        selectedRank++;
      }
      int selectedIdx = rankedIndices.get(selectedRank);

      popNew.add(merged.individuals.get(selectedIdx));
      popFitNew.add(merged.fitness.get(selectedIdx));
    }

    return new SurvivorSelectResult(popNew, popFitNew);
  }

  // =====================================================
  // Private Helper Methods
  // =====================================================

  /**
   * Internal class to hold merged populations and fitness
   */
  private static class MergedPopulation {
    final List<double[]> individuals;
    final List<Double> fitness;

    MergedPopulation(List<double[]> individuals, List<Double> fitness) {
      this.individuals = individuals;
      this.fitness = fitness;
    }
  }

  /**
   * Merge offspring and parent populations
   */
  private static MergedPopulation mergePopsAndFitness(
    List<double[]> offspringPop,
    List<double[]> parentPop,
    List<Double> offspringFit,
    List<Double> parentFit) {

    List<double[]> merged = new ArrayList<>(offspringPop.size() + parentPop.size());
    merged.addAll(offspringPop);
    merged.addAll(parentPop);

    List<Double> mergedFit = new ArrayList<>(offspringFit.size() + parentFit.size());
    mergedFit.addAll(offspringFit);
    mergedFit.addAll(parentFit);

    return new MergedPopulation(merged, mergedFit);
  }

  /**
   * Validate input parameters
   */
  private static void validateInputs(
    List<double[]> offspringPop,
    List<double[]> parentPop,
    List<Double> offspringFit,
    List<Double> parentFit) {

    if (offspringPop == null || offspringFit == null) {
      throw new IllegalArgumentException("Offspring population and fitness cannot be null");
    }
    if (parentPop == null || parentFit == null) {
      throw new IllegalArgumentException("Parent population and fitness cannot be null");
    }
    if (offspringPop.size() != offspringFit.size()) {
      throw new IllegalArgumentException(
        "Offspring population size (" + offspringPop.size() +
        ") does not match fitness size (" + offspringFit.size() + ")");
    }
    if (parentPop.size() != parentFit.size()) {
      throw new IllegalArgumentException(
        "Parent population size (" + parentPop.size() +
        ") does not match fitness size (" + parentFit.size() + ")");
    }
  }
}
