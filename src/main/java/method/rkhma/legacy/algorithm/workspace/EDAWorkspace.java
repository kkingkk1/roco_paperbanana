package method.rkhma.legacy.algorithm.workspace;

/** Reusable buffers for EDA model estimation. */
public class EDAWorkspace implements Workspace {
  public double[] mu = new double[0];
  public double[] sumSq = new double[0];
  public double[] sigma = new double[0];
  public int[] eliteIndices = new int[0];

  public void ensureCapacity(int nElite, int d) {
    if (mu.length < d) {
      mu = new double[d];
    }
    if (sumSq.length < d) {
      sumSq = new double[d];
    }
    if (sigma.length < d) {
      sigma = new double[d];
    }
    if (eliteIndices.length < nElite) {
      eliteIndices = new int[nElite];
    }
  }
}
