package method.rkhma.legacy.algorithm.workspace;

/** Reusable buffers for Artificial Bee Colony phases. */
public class ABCWorkspace implements Workspace {
  public int[] scoutTargets = new int[0];
  public double[] selectedFitness = new double[0];
  public double[] probabilities = new double[0];
  public double[] employedFitness = new double[0];
  public double[] onlookerFitness = new double[0];
  public double[] scoutFitness = new double[0];
  public double[][] employedBuffer = new double[0][];
  public double[][] onlookerBuffer = new double[0][];
  public double[][] scoutBuffer = new double[0][];

  public void ensureCapacity(int n, int d) {
    if (scoutTargets.length < n) {
      scoutTargets = new int[n];
    }
    if (selectedFitness.length < n) {
      selectedFitness = new double[n];
    }
    if (probabilities.length < n) {
      probabilities = new double[n];
    }
    if (employedFitness.length < n) {
      employedFitness = new double[n];
    }
    if (onlookerFitness.length < n) {
      onlookerFitness = new double[n];
    }
    if (scoutFitness.length < n) {
      scoutFitness = new double[n];
    }
    employedBuffer = ensureRows(employedBuffer, n, d);
    onlookerBuffer = ensureRows(onlookerBuffer, n, d);
    scoutBuffer = ensureRows(scoutBuffer, n, d);
  }

  private static double[][] ensureRows(double[][] buffer, int n, int d) {
    if (buffer.length < n) {
      double[][] resized = new double[n][];
      System.arraycopy(buffer, 0, resized, 0, buffer.length);
      buffer = resized;
    }
    for (int i = 0; i < n; i++) {
      if (buffer[i] == null || buffer[i].length != d) {
        buffer[i] = new double[d];
      }
    }
    return buffer;
  }
}
