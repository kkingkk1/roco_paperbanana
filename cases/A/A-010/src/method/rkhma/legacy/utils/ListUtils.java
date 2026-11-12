package method.rkhma.legacy.utils;

import java.util.*;

public class ListUtils {


  // ==========================================================
  // Initialization and creation methods
  // ==========================================================

  /**
   * Create a zero-filled 2D List.
   *
   * @param rows Number of rows (must be >= 0)
   * @param cols Number of columns (must be >= 0)
   * @return A list of double arrays filled with zeros
   * @throws IllegalArgumentException if rows or cols is negative
   */
  public static List<double[]> zeros2DList(int rows, int cols) {
    if (rows < 0 || cols < 0) {
      throw new IllegalArgumentException("Rows and cols must be non-negative. Got rows=" + rows + ", cols=" + cols);
    }
    List<double[]> result = new ArrayList<>(rows);
    for (int i = 0; i < rows; i++) {
      result.add(new double[cols]);
    }
    return result;
  }

  /**
   * Create a zero-filled 1D List.
   *
   * @param size Number of elements (must be >= 0)
   * @return A list of Double values initialized to 0.0
   * @throws IllegalArgumentException if size is negative
   */
  public static List<Double> zeros1DList(int size) {
    if (size < 0) {
      throw new IllegalArgumentException("Size must be non-negative. Got size=" + size);
    }
    List<Double> result = new ArrayList<>(size);
    for (int i = 0; i < size; i++) {
      result.add(0.0);
    }
    return result;
  }

  /**
   * Create a 2D List filled with a specified value.
   *
   * @param rows Number of rows (must be >= 0)
   * @param cols Number of columns (must be >= 0)
   * @param value The value to fill the list with
   * @return A list of double arrays filled with the specified value
   * @throws IllegalArgumentException if rows or cols is negative
   */
  public static List<double[]> fill2DList(int rows, int cols, double value) {
    if (rows < 0 || cols < 0) {
      throw new IllegalArgumentException("Rows and cols must be non-negative. Got rows=" + rows + ", cols=" + cols);
    }
    List<double[]> result = new ArrayList<>(rows);
    for (int i = 0; i < rows; i++) {
      double[] row = new double[cols];
      Arrays.fill(row, value);
      result.add(row);
    }
    return result;
  }

  /**
   * Create a 1D Double List filled with a specified value.
   *
   * @param size Number of elements (must be >= 0)
   * @param value The value to fill the list with
   * @return A list of Double values filled with the specified value
   * @throws IllegalArgumentException if size is negative
   */
  public static List<Double> fill1DListDouble(int size, double value) {
    if (size < 0) {
      throw new IllegalArgumentException("Size must be non-negative. Got size=" + size);
    }
    List<Double> result = new ArrayList<>(size);
    for (int i = 0; i < size; i++) {
      result.add(value);
    }
    return result;
  }

  /**
   * Create a 1D Boolean List filled with a specified value.
   *
   * @param size Number of elements (must be >= 0)
   * @param value The boolean value to fill the list with
   * @return A list of Boolean values filled with the specified value
   * @throws IllegalArgumentException if size is negative
   */
  public static List<Boolean> fill1DListBoolean(int size, boolean value) {
    if (size < 0) {
      throw new IllegalArgumentException("Size must be non-negative. Got size=" + size);
    }
    List<Boolean> result = new ArrayList<>(size);
    for (int i = 0; i < size; i++) {
      result.add(value);
    }
    return result;
  }

  /**
   * Create a 2D Boolean List filled with false.
   *
   * @param rows Number of rows (must be >= 0)
   * @param cols Number of columns (must be >= 0)
   * @return A list of boolean arrays initialized to false
   * @throws IllegalArgumentException if rows or cols is negative
   */
  public static List<boolean[]> falseMatrix(int rows, int cols) {
    if (rows < 0 || cols < 0) {
      throw new IllegalArgumentException("Rows and cols must be non-negative. Got rows=" + rows + ", cols=" + cols);
    }
    List<boolean[]> result = new ArrayList<>(rows);
    for (int i = 0; i < rows; i++) {
      result.add(new boolean[cols]);
    }
    return result;
  }

  /**
   * Initialize a random population within specified bounds.
   *
   * @param populationSize Number of individuals in the population (must be >= 0)
   * @param dimension Problem dimension (number of variables per individual, must be >= 0)
   * @param lowerBound Lower bound for each variable
   * @param upperBound Upper bound for each variable (must be >= lowerBound)
   * @param random Random number generator (must not be null)
   * @return A list of randomly initialized individuals
   * @throws IllegalArgumentException if parameters are invalid
   * @throws NullPointerException if random is null
   */
  public static List<double[]> initializeRandomPopulation(
    int populationSize,
    int dimension,
    double lowerBound,
    double upperBound,
    Random random) {
    if (populationSize < 0) {
      throw new IllegalArgumentException("Population size must be non-negative. Got: " + populationSize);
    }
    if (dimension < 0) {
      throw new IllegalArgumentException("Dimension must be non-negative. Got: " + dimension);
    }
    if (upperBound < lowerBound) {
      throw new IllegalArgumentException("Upper bound must be >= lower bound. Got: [" + lowerBound + ", " + upperBound + "]");
    }
    if (random == null) {
      throw new NullPointerException("Random generator must not be null");
    }

    List<double[]> population = zeros2DList(populationSize, dimension);
    double range = upperBound - lowerBound;

    for (int i = 0; i < populationSize; i++) {
      for (int j = 0; j < dimension; j++) {
        double val = lowerBound + range * random.nextDouble();
        population.get(i)[j] = val;
      }
    }

    return population;
  }

  // ==========================================================
  // Deep copy methods
  // ==========================================================

  /**
   * Deep copy a 2D Double List.
   *
   * @param source The source list to copy (must not be null)
   * @return A deep copy of the source list
   * @throws NullPointerException if source is null
   */
  public static List<double[]> deepCopy2D(List<double[]> source) {
    if (source == null) {
      throw new NullPointerException("Source list must not be null");
    }
    List<double[]> copy = new ArrayList<>(source.size());
    for (double[] row : source) {
      copy.add(Arrays.copyOf(row, row.length));
    }
    return copy;
  }

  /**
   * Deep copy a 2D Boolean List.
   *
   * @param source The source list to copy (must not be null)
   * @return A deep copy of the source list
   * @throws NullPointerException if source is null
   */
  public static List<boolean[]> deepCopy2DBool(List<boolean[]> source) {
    if (source == null) {
      throw new NullPointerException("Source list must not be null");
    }
    List<boolean[]> copy = new ArrayList<>(source.size());
    for (boolean[] row : source) {
      copy.add(Arrays.copyOf(row, row.length));
    }
    return copy;
  }

  // ==========================================================
  // Row/column selection and operations
  // ==========================================================

  /**
   * Select rows by Boolean indices.
   *
   * @param matrix The source matrix (must not be null)
   * @param rowIndices Boolean list indicating which rows to select (must not be null)
   * @return A new list containing deep copies of the selected rows
   * @throws NullPointerException if matrix or rowIndices is null
   * @throws IndexOutOfBoundsException if rowIndices size exceeds matrix size
   */
  public static List<double[]> selectRows(List<double[]> matrix, List<Boolean> rowIndices) {
    if (matrix == null || rowIndices == null) {
      throw new NullPointerException("Matrix and rowIndices must not be null");
    }
    if (rowIndices.size() > matrix.size()) {
      throw new IndexOutOfBoundsException("rowIndices size (" + rowIndices.size() + ") exceeds matrix size (" + matrix.size() + ")");
    }

    List<double[]> result = new ArrayList<>(countTrue(rowIndices));
    int size = rowIndices.size();
    for (int i = 0; i < size; i++) {
      if (rowIndices.get(i)) {
        result.add(Arrays.copyOf(matrix.get(i), matrix.get(i).length));
      }
    }
    return result;
  }

  /**
   * Select rows by Boolean indices (returns views, not copies).
   *
   * @param matrix The source matrix (must not be null)
   * @param rowIndices Boolean list indicating which rows to select (must not be null)
   * @return A new list containing references to the selected rows (not deep copies)
   * @throws NullPointerException if matrix or rowIndices is null
   * @throws IndexOutOfBoundsException if rowIndices size exceeds matrix size
   */
  public static List<double[]> selectRowsView(List<double[]> matrix, List<Boolean> rowIndices) {
    if (matrix == null || rowIndices == null) {
      throw new NullPointerException("Matrix and rowIndices must not be null");
    }
    if (rowIndices.size() > matrix.size()) {
      throw new IndexOutOfBoundsException("rowIndices size (" + rowIndices.size() + ") exceeds matrix size (" + matrix.size() + ")");
    }

    List<double[]> result = new ArrayList<>(countTrue(rowIndices));
    int size = rowIndices.size();
    for (int i = 0; i < size; i++) {
      if (rowIndices.get(i)) {
        result.add(matrix.get(i));
      }
    }
    return result;
  }

  /**
   * Select elements from a List by Boolean indices.
   *
   * @param list The source list (must not be null)
   * @param indices Boolean list indicating which elements to select (must not be null)
   * @return A new list containing the selected elements
   * @throws NullPointerException if list or indices is null
   * @throws IndexOutOfBoundsException if indices size exceeds list size
   */
  public static List<Double> selectElements(List<Double> list, List<Boolean> indices) {
    if (list == null || indices == null) {
      throw new NullPointerException("List and indices must not be null");
    }
    if (indices.size() > list.size()) {
      throw new IndexOutOfBoundsException("indices size (" + indices.size() + ") exceeds list size (" + list.size() + ")");
    }

    List<Double> result = new ArrayList<>(countTrue(indices));
    int size = indices.size();
    for (int i = 0; i < size; i++) {
      if (indices.get(i)) {
        result.add(list.get(i));
      }
    }
    return result;
  }

  /**
   * Assign source rows to target matrix at specified positions.
   *
   * @param target The target matrix to modify (must not be null)
   * @param source The source matrix to copy from (must not be null)
   * @param rowIndices Boolean list indicating where to assign rows (must not be null)
   * @throws NullPointerException if any parameter is null
   * @throws IndexOutOfBoundsException if indices size exceeds target size or source has insufficient rows
   */
  public static void assignRows(List<double[]> target, List<double[]> source, List<Boolean> rowIndices) {
    if (target == null || source == null || rowIndices == null) {
      throw new NullPointerException("Target, source, and rowIndices must not be null");
    }
    if (rowIndices.size() > target.size()) {
      throw new IndexOutOfBoundsException("rowIndices size (" + rowIndices.size() + ") exceeds target size (" + target.size() + ")");
    }

    int trueCount = countTrue(rowIndices);
    if (trueCount > source.size()) {
      throw new IndexOutOfBoundsException("Number of true values in rowIndices (" + trueCount + ") exceeds source size (" + source.size() + ")");
    }

    int sourceIdx = 0;
    int size = rowIndices.size();
    for (int i = 0; i < size; i++) {
      if (rowIndices.get(i)) {
        System.arraycopy(source.get(sourceIdx), 0, target.get(i), 0, target.get(i).length);
        sourceIdx++;
      }
    }
  }

  /**
   * Assign elements in a List by Boolean indices.
   *
   * @param target The target list to modify (must not be null)
   * @param source The source list to copy from (must not be null)
   * @param indices Boolean list indicating where to assign elements (must not be null)
   * @throws NullPointerException if any parameter is null
   */
  public static void assignElements(List<Double> target, List<Double> source, List<Boolean> indices) {
    if (target == null || source == null || indices == null) {
      throw new NullPointerException("Target, source, and indices must not be null");
    }

    int sourceIdx = 0;
    int size = Math.min(indices.size(), target.size());
    for (int i = 0; i < size; i++) {
      if (indices.get(i)) {
        if (sourceIdx < source.size()) {
          target.set(i, source.get(sourceIdx++));
        }
      }
    }
  }

  // ==========================================================
  // Matrix combination and resizing
  // ==========================================================

  /**
   * Combine two matrices element-wise according to two boolean masks.
   * For each element, if mask1 is true, take from matrix1; otherwise take from matrix2.
   *
   * @param matrix1 First source matrix (must not be null)
   * @param matrix2 Second source matrix (must not be null)
   * @param mask1 Boolean mask for matrix1 (must not be null)
   * @param mask2 Boolean mask for matrix2 (must not be null, currently unused but kept for API compatibility)
   * @return A new matrix combining elements from matrix1 and matrix2 based on mask1
   * @throws NullPointerException if any parameter is null
   */
  public static List<double[]> combineByMask(List<double[]> matrix1,
    List<double[]> matrix2,
    List<boolean[]> mask1,
    List<boolean[]> mask2) {
    if (matrix1 == null || matrix2 == null || mask1 == null || mask2 == null) {
      throw new NullPointerException("All parameters must not be null");
    }

    int rows = Math.min(Math.min(matrix1.size(), matrix2.size()), Math.min(mask1.size(), mask2.size()));
    List<double[]> result = new ArrayList<>(rows);

    for (int i = 0; i < rows; i++) {
      int cols = matrix1.get(i).length;
      double[] row = new double[cols];

      for (int j = 0; j < cols; j++) {
        if (mask1.get(i)[j]) {
          row[j] = matrix1.get(i)[j];
        } else {
          row[j] = matrix2.get(i)[j];
        }
      }
      result.add(row);
    }

    return result;
  }

  /**
   * Resize a 2D List (row count).
   *
   * @param list The source list to resize (must not be null)
   * @param newRows The new number of rows (must be >= 0)
   * @param cols The number of columns (must be >= 0)
   * @return A resized copy of the list
   * @throws NullPointerException if list is null
   * @throws IllegalArgumentException if newRows or cols is negative
   */
  public static List<double[]> resize2DList(List<double[]> list, int newRows, int cols) {
    if (list == null) {
      throw new NullPointerException("List must not be null");
    }
    if (newRows < 0 || cols < 0) {
      throw new IllegalArgumentException("newRows and cols must be non-negative. Got newRows=" + newRows + ", cols=" + cols);
    }

    List<double[]> result = new ArrayList<>(newRows);
    int oldRows = list.size();

    if (oldRows > newRows) {
      // Keep existing rows (up to newRows)
      for (int i = 0; i < newRows; i++) {
        result.add(Arrays.copyOf(list.get(i), cols));
      }
    } else {
      // Keep existing rows
      for (int i = 0; i < oldRows; i++) {
        result.add(Arrays.copyOf(list.get(i), cols));
      }
      // Add zero rows if more rows are needed
      for (int i = oldRows; i < newRows; i++) {
        result.add(new double[cols]);
      }
    }

    return result;
  }

  /**
   * Resize a 1D List.
   *
   * @param list The source list to resize (must not be null)
   * @param newSize The new size (must be >= 0)
   * @param defaultValue The default value for new elements
   * @return A resized copy of the list
   * @throws NullPointerException if list is null
   * @throws IllegalArgumentException if newSize is negative
   */
  public static List<Double> resize1DList(List<Double> list, int newSize, double defaultValue) {
    if (list == null) {
      throw new NullPointerException("List must not be null");
    }
    if (newSize < 0) {
      throw new IllegalArgumentException("newSize must be non-negative. Got: " + newSize);
    }

    List<Double> result = new ArrayList<>(newSize);
    int oldSize = list.size();

    if (oldSize > newSize) {
      // Keep existing elements
      for (int i = 0; i < newSize; i++) {
        result.add(list.get(i));
      }
    } else {
      // Keep existing elements
      for (int i = 0; i < oldSize; i++) {
        result.add(list.get(i));
      }
      // Add new elements
      for (int i = oldSize; i < newSize; i++) {
        result.add(defaultValue);
      }
    }

    return result;
  }

  // ==========================================================
  // Logic and comparison operations
  // ==========================================================

  /**
   * Check whether each row of a 2D Boolean matrix has any true value.
   * Equivalent to MATLAB's any(matrix, 2)
   *
   * @param matrix The boolean matrix to check (must not be null)
   * @return A list of Boolean values, one per row, indicating if that row contains any true value
   * @throws NullPointerException if matrix is null
   */
  public static List<Boolean> anyTruePerRow(List<boolean[]> matrix) {
    if (matrix == null) {
      throw new NullPointerException("Matrix must not be null");
    }

    List<Boolean> result = new ArrayList<>(matrix.size());
    for (boolean[] row : matrix) {
      boolean hasTrue = false;
      for (boolean b : row) {
        if (b) {
          hasTrue = true;
          break;
        }
      }
      result.add(hasTrue);
    }
    return result;
  }

  /**
   * Count the number of true values in a Boolean list.
   *
   * @param list The Boolean list to count (must not be null)
   * @return The number of true values
   * @throws NullPointerException if list is null
   */
  public static int countTrue(List<Boolean> list) {
    if (list == null) {
      throw new NullPointerException("List must not be null");
    }

    int count = 0;
    int size = list.size();
    for (int i = 0; i < size; i++) {
      if (list.get(i)) {
        count++;
      }
    }
    return count;
  }

  /**
   * Check if a Boolean list contains any true values.
   * Performance optimization: avoid stream overhead, exit early when the first true is found.
   *
   * @param list The Boolean list to check (must not be null)
   * @return true if any element is true, false otherwise
   * @throws NullPointerException if list is null
   */
  public static boolean hasAnyTrue(List<Boolean> list) {
    if (list == null) {
      throw new NullPointerException("List must not be null");
    }

    int size = list.size();
    for (int i = 0; i < size; i++) {
      if (list.get(i)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Element-wise logical negation (NOT) operation on a boolean matrix.
   * Returns a new matrix where each element is the logical negation of the input.
   *
   * @param matrix The boolean matrix to negate (must not be null)
   * @return A new matrix with all boolean values negated
   * @throws NullPointerException if matrix is null
   */
  public static List<boolean[]> negate(List<boolean[]> matrix) {
    if (matrix == null) {
      throw new NullPointerException("Matrix must not be null");
    }

    List<boolean[]> result = new ArrayList<>(matrix.size());
    for (boolean[] row : matrix) {
      boolean[] newRow = new boolean[row.length];
      for (int j = 0; j < row.length; j++) {
        newRow[j] = !row[j];
      }
      result.add(newRow);
    }
    return result;
  }

}
