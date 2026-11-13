package method.rkhma.legacy.algorithm.workspace;

import java.util.AbstractList;
import java.util.List;

/** Reusable buffers for PSO candidate positions and velocities. */
public class PSOWorkspace implements Workspace {
  public double[][] positionBuffer = new double[0][];
  public double[][] velocityBuffer = new double[0][];
  private List<double[]> positionView = fixedView(new double[0][], 0);
  private List<double[]> velocityView = fixedView(new double[0][], 0);

  public void ensureCapacity(int n, int d) {
    positionBuffer = ensureRows(positionBuffer, n, d);
    velocityBuffer = ensureRows(velocityBuffer, n, d);
    positionView = fixedView(positionBuffer, n);
    velocityView = fixedView(velocityBuffer, n);
  }

  public List<double[]> positionView(int n) {
    if (positionView.size() != n) {
      positionView = fixedView(positionBuffer, n);
    }
    return positionView;
  }

  public List<double[]> velocityView(int n) {
    if (velocityView.size() != n) {
      velocityView = fixedView(velocityBuffer, n);
    }
    return velocityView;
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
