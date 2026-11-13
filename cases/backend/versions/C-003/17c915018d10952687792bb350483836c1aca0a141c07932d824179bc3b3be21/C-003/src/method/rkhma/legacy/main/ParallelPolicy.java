package method.rkhma.legacy.main;

import java.util.Locale;

/**
 * Central runtime policy for choosing one parallelism layer.
 */
public final class ParallelPolicy {
  public static final String PROPERTY = "autohyb.parallel.level";

  public enum Level {
    RUN,
    SATELLITE,
    VECTOR,
    AUTO
  }

  public static final class Scope implements AutoCloseable {
    private final boolean previousOuterEvaluationActive;

    private Scope(boolean previousOuterEvaluationActive) {
      this.previousOuterEvaluationActive = previousOuterEvaluationActive;
    }

    @Override
    public void close() {
      OUTER_EVALUATION_ACTIVE.set(previousOuterEvaluationActive);
    }
  }

  private static final ThreadLocal<Boolean> OUTER_EVALUATION_ACTIVE =
    ThreadLocal.withInitial(() -> false);

  private ParallelPolicy() {
    throw new AssertionError("Utility class should not be instantiated");
  }

  public static Level configuredLevel() {
    String raw = System.getProperty(PROPERTY, Level.AUTO.name()).trim();
    if (raw.isEmpty()) {
      return Level.AUTO;
    }
    try {
      return Level.valueOf(raw.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
        "Invalid " + PROPERTY + ": " + raw + ". Valid values: run, satellite, vector, auto", e);
    }
  }

  public static Scope enterOuterEvaluation() {
    boolean previous = OUTER_EVALUATION_ACTIVE.get();
    OUTER_EVALUATION_ACTIVE.set(true);
    return new Scope(previous);
  }

  public static boolean isOuterEvaluationActive() {
    return OUTER_EVALUATION_ACTIVE.get();
  }

  public static boolean allowVectorParallelism(int populationSize, int dimension, int threshold) {
    Level level = configuredLevel();
    if (level == Level.VECTOR) {
      return populationSize > effectiveThreshold(threshold, dimension);
    }
    if (level == Level.RUN || level == Level.SATELLITE) {
      return false;
    }
    if (isOuterEvaluationActive()) {
      return false;
    }
    return populationSize > effectiveThreshold(threshold, dimension);
  }

  public static int effectiveThreshold(int baseThreshold, int dimension) {
    if (dimension <= 0) {
      return baseThreshold;
    }
    int dimensionAware = dimension <= 64
      ? baseThreshold * 2
      : baseThreshold;
    return Math.max(1, dimensionAware);
  }

  public static int hybThreadsForSingleRun(int totalCores, int minimumThreads) {
    Level level = configuredLevel();
    if (level == Level.VECTOR) {
      return Math.max(minimumThreads, Math.max(1, totalCores / 2));
    }
    return Math.max(minimumThreads, totalCores);
  }

  public static int evalThreadsForOuter(int totalCores, int minimumThreads, int maximumThreads) {
    Level level = configuredLevel();
    if (level == Level.SATELLITE || level == Level.VECTOR) {
      return minimumThreads;
    }
    return Math.max(minimumThreads, Math.min(totalCores / 2, maximumThreads));
  }

  public static int hybThreadsForOuter(int totalCores, int evalThreads, int minimumThreads) {
    Level level = configuredLevel();
    if (level == Level.RUN) {
      return Math.max(minimumThreads, Math.max(totalCores - evalThreads, totalCores / 2));
    }
    if (level == Level.VECTOR) {
      return Math.max(minimumThreads, Math.max(1, totalCores / 2));
    }
    return Math.max(minimumThreads, Math.max(totalCores - evalThreads, totalCores / 2));
  }
}
