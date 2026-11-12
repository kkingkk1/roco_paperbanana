package method.rkhma.legacy.operator.mutation;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import method.rkhma.legacy.common.BoundaryHandler;

public final class MutationDERandToBest {

  /**
   * Perform DE/rand-to-best/1 mutation using index-based selection (high-performance version).
   *
   * @param pop Full parent population
   * @param selectIndices Indices of individuals to mutate
   * @param bestIndex Index of the best individual in the population
   * @param F1 Scaling factor towards the best vector
   * @param F2 Scaling factor for the differential vector
   * @param upperBound Upper bound (scalar, same for all dimensions and individuals)
   * @param lowerBound Lower bound (scalar, same for all dimensions and individuals)
   * @param customRand Custom random number generator
   * @return Mutated population
   * @throws IllegalArgumentException If population size is less than 4 or parameters are invalid
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
    int D = pop.get(0).length;
    int nSelect = selectIndices.length;

    List<double[]> mutantPopulation = new ArrayList<>(nSelect);

    // Generate a mutant vector for each selected index
    for (int i = 0; i < nSelect; i++) {
      int targetIndex = selectIndices[i];

      double[] mutant = new double[D];

      // Randomly select 3 distinct indices from the full population pop
      // r1, r2, r3 must be distinct and must not equal targetIndex
      int r1 = DEUtils.selectRandomIndex(nPop, targetIndex, -1, -1, -1, -1, -1, customRand);
      int r2 = DEUtils.selectRandomIndex(nPop, targetIndex, r1, -1, -1, -1, -1, customRand);
      int r3 = DEUtils.selectRandomIndex(nPop, targetIndex, r1, r2, -1, -1, -1, customRand);

      // References for readability
      double[] individual_r1 = pop.get(r1);
      double[] individual_r2 = pop.get(r2);
      double[] individual_r3 = pop.get(r3);

      // Apply DE/rand-to-best/1 mutation for each dimension
      for (int d = 0; d < D; d++) {
        double mutatedValue = individual_r1[d]
        + F1 * (pop.get(bestIndex)[d] - individual_r1[d])
        + F2 * (individual_r2[d] - individual_r3[d]);

        mutant[d] = BoundaryHandler.repairDE(
          mutatedValue, pop.get(targetIndex)[d], lowerBound, upperBound, customRand, boundaryMode);
      }

      mutantPopulation.add(mutant);
    }

    return mutantPopulation;
  }
}
