package method.rkhma.legacy.algorithm.workspace;

import java.util.AbstractList;
import java.util.List;

/** Reusable buffers for GeneticAlgorithmNoSelect. */
public class GAWorkspace implements Workspace {
  public double[][] offspringBuffer = new double[0][];
  public double[] fitnessBuffer = new double[0];
  public int[] tournamentScratch = new int[0];
  public int[] dimensionScratch = new int[0];
  public int[] eliteScratch = new int[0];
  private List<double[]> offspringView = fixedView(new double[0][], 0);

  public void ensureCapacity(int nSelect, int dimension, int nPop) {
    offspringBuffer = ensureRows(offspringBuffer, nSelect, dimension);
    if (fitnessBuffer.length != nPop) {
      fitnessBuffer = new double[nPop];
    }
    if (tournamentScratch.length < nPop) {
      tournamentScratch = new int[nPop];
    }
    if (dimensionScratch.length < dimension) {
      dimensionScratch = new int[dimension];
    }
    int eliteCapacity = Math.max(nPop, nSelect);
    if (eliteScratch.length < eliteCapacity) {
      eliteScratch = new int[eliteCapacity];
    }
    offspringView = fixedView(offspringBuffer, nSelect);
  }

  public List<double[]> offspringView(int nSelect) {
    if (offspringView.size() != nSelect) {
      offspringView = fixedView(offspringBuffer, nSelect);
    }
    return offspringView;
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
