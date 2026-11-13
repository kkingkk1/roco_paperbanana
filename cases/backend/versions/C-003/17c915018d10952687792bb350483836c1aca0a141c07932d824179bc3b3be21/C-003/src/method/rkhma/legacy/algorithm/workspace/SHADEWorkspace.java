package method.rkhma.legacy.algorithm.workspace;

import java.util.AbstractList;
import java.util.List;

/** Reusable buffers for SHADE full-population binomial crossover path. */
public class SHADEWorkspace implements Workspace {
  public double[][] mutantBuffer = new double[0][];
  public double[][] trialBuffer = new double[0][];
  private List<double[]> mutantView = fixedView(new double[0][], 0);
  private List<double[]> trialView = fixedView(new double[0][], 0);

  public void ensureCapacity(int n, int d) {
    mutantBuffer = ensureRows(mutantBuffer, n, d);
    trialBuffer = ensureRows(trialBuffer, n, d);
    mutantView = fixedView(mutantBuffer, n);
    trialView = fixedView(trialBuffer, n);
  }

  public List<double[]> mutantView(int n) {
    if (mutantView.size() != n) {
      mutantView = fixedView(mutantBuffer, n);
    }
    return mutantView;
  }

  public List<double[]> trialView(int n) {
    if (trialView.size() != n) {
      trialView = fixedView(trialBuffer, n);
    }
    return trialView;
  }

  private static double[][] ensureRows(double[][] buffer, int rows, int cols) {
    if (buffer.length < rows) {
      double[][] resized = new double[rows][];
      System.arraycopy(buffer, 0, resized, 0, buffer.length);
      buffer = resized;
    }
    for (int i = 0; i < rows; i++) {
      if (buffer[i] == null || buffer[i].length != cols) {
        buffer[i] = new double[cols];
      }
    }
    return buffer;
  }

  private static List<double[]> fixedView(double[][] rows, int size) {
    return new AbstractList<double[]>() {
      @Override
      public double[] get(int index) {
        if (index < 0 || index >= size) {
          throw new IndexOutOfBoundsException("index=" + index + ", size=" + size);
        }
        return rows[index];
      }

      @Override
      public int size() {
        return size;
      }
    };
  }
}
