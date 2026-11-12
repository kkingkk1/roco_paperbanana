package method.rkhma.legacy.common;
import method.rkhma.legacy.algorithm.shade.UpdateArchive;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import method.rkhma.legacy.utils.FitnessUtils;

public class ResizePop {

  /**
   * Archive rate constant (empirical factor for archive size calculation).
   */
  private static final double ARCHIVE_RATE = 1.4;

  /**
   * Population resizing result wrapper.
   */
  public static class ResizeResult {
    /** Resized population */
    public List<double[]> pop;

    /** Resized fitness values */
    public List<Double> popFit;

    /** Resized individual personal best positions */
    public List<double[]> pbestS;

    /** Resized individual personal best fitness */
    public List<Double> pbestF;

    /** Resized external archive */
    public UpdateArchive.ListArchive archive;

    /** New population size estimate */
    public int nPop;

    /**
     * Construct a resize result object.
     */
    public ResizeResult(List<double[]> pop, List<Double> popFit,
      List<double[]> pbestS, List<Double> pbestF,
      UpdateArchive.ListArchive archive, int nPop) {
      this.pop = pop;
      this.popFit = popFit;
      this.pbestS = pbestS;
      this.pbestF = pbestF;
      this.archive = archive;
      this.nPop = nPop;
    }
  }

  public static ResizeResult resizePop(
    List<double[]> pop,
    List<Double> fitness,
    List<double[]> pbestS,
    List<Double> pbestF,
    UpdateArchive.ListArchive archive,
    int nfes,
    int maxNfes,
    int maxPopSize,
    int minPopSize,
    Random rand) {

    // ======================================================
    // Step 1: Input validation
    // ======================================================

    if (pop == null || fitness == null || pbestS == null || pbestF == null || archive == null) {
      throw new IllegalArgumentException("Input lists and archive cannot be null");
    }

    if (pop.size() != fitness.size() || pop.size() != pbestS.size() || pop.size() != pbestF.size()) {
      throw new IllegalArgumentException("Population, fitness, pbestS, and pbestF must have the same size");
    }

    if (minPopSize < 1 || maxPopSize < minPopSize) {
      throw new IllegalArgumentException("Invalid population size bounds");
    }

    // ======================================================
    // Step 2: Compute planned population size
    // ======================================================

    // Current population size
    int currentPopSize = pop.size();

    // Linear population decay formula
    long planPopSize = Math.round(
      ((double) minPopSize - maxPopSize) / maxNfes * nfes + maxPopSize
    );

    // ======================================================
    // Step 3: Check if population reduction is needed
    // ======================================================

    if (currentPopSize > planPopSize) {

      // --------------------------------------------------
      // Step 4: Compute number of individuals to remove
      // --------------------------------------------------

      int reductionIndNum = currentPopSize - (int) planPopSize;

      // Boundary check: ensure not going below minimum population size
      if (currentPopSize - reductionIndNum < minPopSize) {
        reductionIndNum = currentPopSize - minPopSize;
      }

      // Proceed only if removal is needed
      if (reductionIndNum > 0) {

        // --------------------------------------------------
        // Step 5: Create index-fitness pairs and sort
        // --------------------------------------------------

        // Create list of index-fitness pairs
        List<FitnessUtils.IndexFitnessPair> indexedFitness = new ArrayList<>();
        for (int i = 0; i < currentPopSize; i++) {
          indexedFitness.add(new FitnessUtils.IndexFitnessPair(i, fitness.get(i)));
        }

        // Sort by fitness in ASCENDING order (worst first for maximization)
        indexedFitness.sort((a, b) -> Double.compare(a.getFitness(), b.getFitness()));

        // --------------------------------------------------
        // Step 6: Mark individuals to keep (O(n) optimization)
        // --------------------------------------------------

        // Create a set of indices to keep
        boolean[] keepFlags = new boolean[currentPopSize];
        for (int i = reductionIndNum; i < currentPopSize; i++) {
          keepFlags[indexedFitness.get(i).getIndex()] = true;
        }

        // --------------------------------------------------
        // Step 7: Build new lists with kept individuals
        // --------------------------------------------------

        List<double[]> newPop = new ArrayList<>(currentPopSize - reductionIndNum);
        List<Double> newFitness = new ArrayList<>(currentPopSize - reductionIndNum);
        List<double[]> newPbestS = new ArrayList<>(currentPopSize - reductionIndNum);
        List<Double> newPbestF = new ArrayList<>(currentPopSize - reductionIndNum);

        for (int i = 0; i < currentPopSize; i++) {
          if (keepFlags[i]) {
            newPop.add(pop.get(i));
            newFitness.add(fitness.get(i));
            newPbestS.add(pbestS.get(i));
            newPbestF.add(pbestF.get(i));
          }
        }

        // Replace original lists
        pop.clear();
        pop.addAll(newPop);
        fitness.clear();
        fitness.addAll(newFitness);
        pbestS.clear();
        pbestS.addAll(newPbestS);
        pbestF.clear();
        pbestF.addAll(newPbestF);
      }
    }

    // ======================================================
    // Step 8: Adjust archive size
    // ======================================================

    // Compute new archive capacity
    archive.NP = (int) Math.round(ARCHIVE_RATE * pop.size());

    // If current archive exceeds capacity, prune it
    if (archive.pop.size() > archive.NP) {

      // --------------------------------------------------
      // Randomly sample to keep archive individuals
      // --------------------------------------------------

      // Create index-fitness pairs (to shuffle synchronously)
      List<FitnessUtils.IndexFitnessPair> archiveIndexedFitness = new ArrayList<>();
      for (int i = 0; i < archive.pop.size(); i++) {
        archiveIndexedFitness.add(
          new FitnessUtils.IndexFitnessPair(i, archive.fitness.get(i))
        );
      }

      if (rand == null) {
        throw new IllegalArgumentException("rand cannot be null");
      }
      Collections.shuffle(archiveIndexedFitness, rand);

      // Keep the first NP
      List<double[]> newArchivePop = new ArrayList<>();
      List<Double> newArchiveFit = new ArrayList<>();
      for (int i = 0; i < archive.NP; i++) {
        int idx = archiveIndexedFitness.get(i).getIndex();
        newArchivePop.add(archive.pop.get(idx));
        newArchiveFit.add(archive.fitness.get(idx));
      }

      // Update archive
      archive.pop = newArchivePop;
      archive.fitness = newArchiveFit;
    }

    // ======================================================
    // Step 9: Return result
    // ======================================================

    return new ResizeResult(
      pop,
      fitness,
      pbestS,
      pbestF,
      archive,
      pop.size()  // New population size estimate
    );
  }
}
