package method.rkhma.legacy.algorithm.workspace;

import java.util.AbstractList;
import java.util.List;

/** Reusable buffers for simulated annealing. */
public class SAWorkspace implements Workspace {
  public double[][] candidateBuffer = new double[0][];
  public double[][] currentBuffer = new double[0][];
  public double[][] bestBuffer = new double[0][];
  public double[][] sigmaBuffer = new double[0][];
  public double[] currentFitness = new double[0];
  public double[] bestFitness = new double[0];
  public double[] candidateFitness = new double[0];
  public boolean[] acceptedFlags = new boolean[0];
  public int[] acceptedCounts = new int[0];
  public int[] selectedIndices = new int[0];
  private List<double[]> candidateView = fixedView(new double[0][], 0);
  private List<double[]> bestView = fixedView(new double[0][], 0);
  private List<Double> candidateFitnessView = doubleView(new double[0], 0);
  private List<Double> bestFitnessView = doubleView(new double[0], 0);
  private List<double[]> sigmaView = fixedView(new double[0][], 0);

  public void ensureCapacity(int n, int d) {
    candidateBuffer = ensureRows(candidateBuffer, n, d);
    currentBuffer = ensureRows(currentBuffer, n, d);
    bestBuffer = ensureRows(bestBuffer, n, d);
    sigmaBuffer = ensureRows(sigmaBuffer, n, d);
    if (currentFitness.length < n) {
      currentFitness = new double[n];
    }
    if (bestFitness.length < n) {
      bestFitness = new double[n];
    }
    if (candidateFitness.length < n) {
      candidateFitness = new double[n];
    }
    if (acceptedFlags.length < n) {
      acceptedFlags = new boolean[n];
    }
    if (acceptedCounts.length < n) {
      acceptedCounts = new int[n];
    }
    if (selectedIndices.length < n) {
      selectedIndices = new int[n];
    }
    candidateView = fixedView(candidateBuffer, n);
    bestView = fixedView(bestBuffer, n);
    candidateFitnessView = doubleView(candidateFitness, n);
    bestFitnessView = doubleView(bestFitness, n);
    sigmaView = fixedView(sigmaBuffer, n);
  }

  public List<double[]> candidateView(int n) {
    if (candidateView.size() != n) {
      candidateView = fixedView(candidateBuffer, n);
    }
    return candidateView;
  }

  public List<double[]> bestView(int n) {
    if (bestView.size() != n) {
      bestView = fixedView(bestBuffer, n);
    }
    return bestView;
  }

  public List<Double> candidateFitnessView(int n) {
    if (candidateFitnessView.size() != n) {
      candidateFitnessView = doubleView(candidateFitness, n);
    }
    return candidateFitnessView;
  }

  public List<Double> bestFitnessView(int n) {
    if (bestFitnessView.size() != n) {
      bestFitnessView = doubleView(bestFitness, n);
    }
    return bestFitnessView;
  }

  public List<double[]> sigmaView(int n) {
    if (sigmaView.size() != n) {
      sigmaView = fixedView(sigmaBuffer, n);
    }
    return sigmaView;
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

  private static List<Double> doubleView(double[] values, int size) {
    return new AbstractList<Double>() {
      @Override
      public Double get(int index) {
        if (index < 0 || index >= size) {
          throw new IndexOutOfBoundsException("index=" + index + ", size=" + size);
        }
        return values[index];
      }

      @Override
      public Double set(int index, Double element) {
        if (index < 0 || index >= size) {
          throw new IndexOutOfBoundsException("index=" + index + ", size=" + size);
        }
        double previous = values[index];
        values[index] = element;
        return previous;
      }

      @Override
      public int size() {
        return size;
      }
    };
  }
}
