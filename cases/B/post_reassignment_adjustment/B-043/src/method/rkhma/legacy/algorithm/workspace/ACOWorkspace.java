package method.rkhma.legacy.algorithm.workspace;

import java.util.List;

/** Reusable buffers for ACOR-style Gaussian sampling. */
public class ACOWorkspace implements Workspace {
  public double[] weights = new double[0];
  public double[] cumulativeProb = new double[0];
  public double[][] sigma = new double[0][];
  public double[][] elitePop = new double[0][];
  public double[] sigmaSortValues = new double[0];
  public int[] sigmaSortRows = new int[0];
  public int[] fitnessOrder = new int[0];
  public int[] cachedEliteIndices = new int[0];

  private int cachedWeightNElite = -1;
  private double cachedWeightIntenseFactor = Double.NaN;
  private int cachedDistributionNElite = -1;
  private int cachedDistributionD = -1;
  private double cachedDistributionDeviateDistanceRatio = Double.NaN;
  private double cachedDistributionSearchRange = Double.NaN;
  private double cachedDistributionMinSigmaRatio = Double.NaN;
  private boolean cachedDistributionLegacySigma;
  private boolean distributionCacheValid;
  private long distributionBuildCount;

  public void ensureCapacity(int nElite, int d) {
    ensureCapacity(nElite, d, nElite);
  }

  public void ensureCapacity(int nElite, int d, int nPop) {
    if (weights.length < nElite) {
      weights = new double[nElite];
      invalidateWeightCache();
    }
    if (cumulativeProb.length < nElite) {
      cumulativeProb = new double[nElite];
      invalidateWeightCache();
    }
    if (sigma.length < nElite) {
      double[][] resized = new double[nElite][];
      System.arraycopy(sigma, 0, resized, 0, sigma.length);
      sigma = resized;
    }
    if (elitePop.length < nElite) {
      elitePop = new double[nElite][];
    }
    if (sigmaSortValues.length < nElite) {
      sigmaSortValues = new double[nElite];
    }
    if (sigmaSortRows.length < nElite) {
      sigmaSortRows = new int[nElite];
    }
    if (fitnessOrder.length < nPop) {
      fitnessOrder = new int[nPop];
    }
    if (cachedEliteIndices.length < nElite) {
      cachedEliteIndices = new int[nElite];
      invalidateDistributionCache();
    }
    for (int i = 0; i < nElite; i++) {
      if (sigma[i] == null || sigma[i].length != d) {
        sigma[i] = new double[d];
        invalidateDistributionCache();
      }
    }
  }

  public boolean hasWeightCache(int nElite, double intenseFactor) {
    return cachedWeightNElite == nElite
      && Double.compare(cachedWeightIntenseFactor, intenseFactor) == 0;
  }

  public void markWeightCache(int nElite, double intenseFactor) {
    cachedWeightNElite = nElite;
    cachedWeightIntenseFactor = intenseFactor;
  }

  private void invalidateWeightCache() {
    cachedWeightNElite = -1;
    cachedWeightIntenseFactor = Double.NaN;
  }

  public boolean hasDistributionCache(
    List<double[]> pop,
    int[] eliteIndices,
    int nElite,
    int d,
    double deviateDistanceRatio,
    double searchRange,
    double minSigmaRatio,
    boolean legacySigma
  ) {
    if (!distributionCacheValid
      || cachedDistributionNElite != nElite
      || cachedDistributionD != d
      || Double.compare(cachedDistributionDeviateDistanceRatio, deviateDistanceRatio) != 0
      || Double.compare(cachedDistributionSearchRange, searchRange) != 0
      || Double.compare(cachedDistributionMinSigmaRatio, minSigmaRatio) != 0
      || cachedDistributionLegacySigma != legacySigma) {
      return false;
    }
    for (int i = 0; i < nElite; i++) {
      if (cachedEliteIndices[i] != eliteIndices[i]) {
        return false;
      }
      if (elitePop[i] != pop.get(eliteIndices[i])) {
        return false;
      }
    }
    return true;
  }

  public void markDistributionCache(
    int[] eliteIndices,
    int nElite,
    int d,
    double deviateDistanceRatio,
    double searchRange,
    double minSigmaRatio,
    boolean legacySigma
  ) {
    System.arraycopy(eliteIndices, 0, cachedEliteIndices, 0, nElite);
    cachedDistributionNElite = nElite;
    cachedDistributionD = d;
    cachedDistributionDeviateDistanceRatio = deviateDistanceRatio;
    cachedDistributionSearchRange = searchRange;
    cachedDistributionMinSigmaRatio = minSigmaRatio;
    cachedDistributionLegacySigma = legacySigma;
    distributionCacheValid = true;
    distributionBuildCount++;
  }

  public void invalidateDistributionCache() {
    distributionCacheValid = false;
    cachedDistributionNElite = -1;
    cachedDistributionD = -1;
    cachedDistributionDeviateDistanceRatio = Double.NaN;
    cachedDistributionSearchRange = Double.NaN;
    cachedDistributionMinSigmaRatio = Double.NaN;
    cachedDistributionLegacySigma = false;
  }

  public long getDistributionBuildCountForTest() {
    return distributionBuildCount;
  }

  public boolean isCachedEliteIndex(int populationIndex) {
    if (!distributionCacheValid || cachedDistributionNElite <= 0) {
      return false;
    }
    for (int i = 0; i < cachedDistributionNElite; i++) {
      if (cachedEliteIndices[i] == populationIndex) {
        return true;
      }
    }
    return false;
  }
}
