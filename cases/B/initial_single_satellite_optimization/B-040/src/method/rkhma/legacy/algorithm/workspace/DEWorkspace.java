package method.rkhma.legacy.algorithm.workspace;

import java.util.AbstractList;
import java.util.List;

/** Reusable buffers for Differential Evolution variants. */
public class DEWorkspace implements Workspace {
  public double[][] trialBuffer = new double[0][];
  public double[][] mutantBuffer = new double[0][];
  public int[] indexScratchA = new int[0];
  public int[] indexScratchB = new int[0];
  private List<double[]> trialView = emptyView();

  public void ensureCapacity(int n, int d) {
    trialBuffer = ensureRows(trialBuffer, n, d);
    mutantBuffer = ensureRows(mutantBuffer, n, d);
    if (indexScratchA.length < n) {
      indexScratchA = new int[n];
    }
    if (indexScratchB.length < n) {
      indexScratchB = new int[n];
    }
    trialView = fixedView(trialBuffer, n);
  }

  public List<double[]> trialView(int n) {
    if (trialView.size() != n) {
      trialView = fixedView(trialBuffer, n);
    }
    return trialView;
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

  private static List<double[]> emptyView() {
    return fixedView(new double[0][], 0);
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
