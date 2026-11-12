package method.rkhma.legacy.algorithm.shade;
import method.rkhma.legacy.utils.FitnessUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class UpdateArchive {
  // ==========================================================
  // List-based methods
  // ==========================================================

  public static class ListArchive {
    /** Archive capacity */
    public int NP;

    /** Individuals in the archive (each individual is double[]) */
    public List<double[]> pop;

    /** Corresponding fitness values */
    public List<Double> fitness;

    public ListArchive() {
      this.pop = new ArrayList<>();
      this.fitness = new ArrayList<>();
    }

    public ListArchive(int np) {
      this.NP = np;
      this.pop = new ArrayList<>();
      this.fitness = new ArrayList<>();
    }
  }

  public static void updateArchive(ListArchive archive,
    List<double[]> newPop,
    List<Double> newFunValues) {
    updateArchiveInternal(archive, newPop, newFunValues);
  }

  private static void updateArchiveInternal(ListArchive archive,
    List<double[]> newPop,
    List<Double> newFunValues) {
    // Input validation
    if (archive == null) {
      throw new IllegalArgumentException("Archive cannot be null");
    }
    if (newPop == null || newFunValues == null) {
      throw new IllegalArgumentException("Input population and fitness values cannot be null");
    }
    if (archive.NP == 0) {
      return;
    }
    if (newPop.size() != newFunValues.size()) {
      throw new IllegalArgumentException(
        "Number of incoming individuals (" + newPop.size() +
        ") must match the number of fitness values (" + newFunValues.size() + ")."
      );
    }
    if (newPop.isEmpty()) {
      return;
    }

    // Combine archive and new population
    List<double[]> popAll = new ArrayList<>(archive.pop.size() + newPop.size());
    popAll.addAll(archive.pop);
    popAll.addAll(newPop);

    List<Double> funvaluesAll = new ArrayList<>(archive.fitness.size() + newFunValues.size());
    funvaluesAll.addAll(archive.fitness);
    funvaluesAll.addAll(newFunValues);

    // Early exit if total size is within capacity (skip deduplication if possible)
    if (popAll.size() <= archive.NP) {
      archive.pop = popAll;
      archive.fitness = funvaluesAll;
      return;
    }

    // Deduplicate using HashSet for O(n) complexity
    Set<String> seen = new HashSet<>();
    List<double[]> uniquePop = new ArrayList<>();
    List<Double> uniqueFunvalues = new ArrayList<>();

    for (int i = 0; i < popAll.size(); i++) {
      double[] individual = popAll.get(i);
      String key = Arrays.toString(individual);
      if (!seen.contains(key)) {
        seen.add(key);
        uniquePop.add(individual);
        uniqueFunvalues.add(funvaluesAll.get(i));
      }
    }

    // If after deduplication we're still within capacity, we're done
    if (uniquePop.size() <= archive.NP) {
      archive.pop = uniquePop;
      archive.fitness = uniqueFunvalues;
      return;
    }

    // Sort by fitness in descending order and keep top archive.NP individuals
    List<FitnessUtils.IndexFitnessPair> sortedPairs = FitnessUtils.sortIndexFitnessDesc(uniqueFunvalues);

    // Select top archive.NP individuals
    List<double[]> finalPop = new ArrayList<>(archive.NP);
    List<Double> finalFunvalues = new ArrayList<>(archive.NP);

    for (int i = 0; i < archive.NP; i++) {
      int bestIndex = sortedPairs.get(i).getIndex();
      finalPop.add(uniquePop.get(bestIndex));
      finalFunvalues.add(uniqueFunvalues.get(bestIndex));
    }

    archive.pop = finalPop;
    archive.fitness = finalFunvalues;
  }
}