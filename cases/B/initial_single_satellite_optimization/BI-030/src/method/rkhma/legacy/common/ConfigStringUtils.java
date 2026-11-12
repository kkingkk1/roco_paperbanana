package method.rkhma.legacy.common;

public final class ConfigStringUtils {

  private ConfigStringUtils() {
    throw new UnsupportedOperationException("A utility class cannot be instantiated.");
  }

  public static String arrayToString(int[] array) {
    if (array == null) return "";
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < array.length; i++) {
      if (i > 0) sb.append(",");
      sb.append(array[i]);
    }
    return sb.toString();
  }

  public static String arrayToString(double[] array) {
    if (array == null) return "";
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < array.length; i++) {
      if (i > 0) sb.append(",");
      sb.append(array[i]);
    }
    return sb.toString();
  }

  public static int[] stringToIntArray(String str) {
    if (str == null || str.trim().isEmpty()) return null;
    String[] parts = str.split(",");
    int[] array = new int[parts.length];
    for (int i = 0; i < parts.length; i++) {
      array[i] = Integer.parseInt(parts[i].trim());
    }
    return array;
  }

  public static double[] stringToDoubleArray(String str) {
    if (str == null || str.trim().isEmpty()) return null;
    String[] parts = str.split(",");
    double[] array = new double[parts.length];
    for (int i = 0; i < parts.length; i++) {
      array[i] = Double.parseDouble(parts[i].trim());
    }
    return array;
  }
}
