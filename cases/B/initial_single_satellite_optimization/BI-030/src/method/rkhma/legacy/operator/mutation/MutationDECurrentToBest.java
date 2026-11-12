package method.rkhma.legacy.operator.mutation;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import method.rkhma.legacy.common.BoundaryHandler;

public final class MutationDECurrentToBest {

  /**
   * Perform DE/current-to-best/1 mutation using index-based selection (high-performance version).
   *
   * @param pop Full parent population
   * @param selectIndices Indices of individuals to mutate (as current)
   * @param bestIndex Index of the best individual in the population
   * @param F1 Scaling factor towards the best vector
   * @param F2 Scaling factor for the differential vector
   * @param upperBound Upper bound (scalar, same for all dimensions and individuals)
   * @param lowerBound Lower bound (scalar, same for all dimensions and individuals)
   * @param customRand Custom random number generator
   * @return Mutated population with boundary handling
   * @throws IllegalArgumentException If input parameters are invalid
   */
  public static List<double[]> mutate(List<double[]> pop,
    int[] selectIndices,
    int bestIndex,
    double F1,
    double F2,
    double upperBound,
    double lowerBound,
    Random customRand) {
    return mutate(pop, selectIndices, bestIndex, F1, F2, upperBound, lowerBound, customRand,
      BoundaryHandler.BoundaryMode.RANDOM);
  }

  public static List<double[]> mutate(List<double[]> pop,
    int[] selectIndices,
    int bestIndex,
    double F1,
    double F2,
    double upperBound,
    double lowerBound,
    Random customRand,
    BoundaryHandler.BoundaryMode boundaryMode) {

    int nPop = pop.size();
    int nSelect = selectIndices.length;
    int D = pop.get(0).length;

    List<double[]> mutantPop = new ArrayList<>(nSelect);

    // Generate a mutant vector for each selected index
    for (int i = 0; i < nSelect; i++) {
      int targetIndex = selectIndices[i];
      double[] current = pop.get(targetIndex);

      // Differential vectors are sampled from the full population.
      // Enforce DE invariant: r1/r2 must be distinct and must not equal the target individual's global index.
      int r1 = DEUtils.selectRandomIndex(nPop, targetIndex, -1, -1, -1, -1, -1, customRand);
      int r2 = DEUtils.selectRandomIndex(nPop, targetIndex, r1, -1, -1, -1, -1, customRand);

      double[] mutant = new double[D];

      // Apply DE/current-to-best/1 mutation for each dimension
      for (int d = 0; d < D; d++) {
        double currentX = current[d];

        double mutatedValue = currentX
        + F1 * (pop.get(bestIndex)[d] - currentX)
        + F2 * (pop.get(r1)[d] - pop.get(r2)[d]);

        mutant[d] = BoundaryHandler.repairDE(
          mutatedValue, currentX, lowerBound, upperBound, customRand, boundaryMode);
      }

      mutantPop.add(mutant);
    }

    return mutantPop;
  }
}
