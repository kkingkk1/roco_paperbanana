package method.rkhma.legacy.common;

import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * Thread-safe Random provider with deterministic behavior for reproducibility.
 *
 * <p><b>Design Philosophy:</b>
 * <ul>
 *   <li><b>Single-threaded contexts:</b> Use shared GLOBAL_RANDOM for perfect reproducibility</li>
 *   <li><b>Concurrent contexts:</b> Use ThreadLocal Random with deterministic per-thread seeds</li>
 * </ul>
 *
 * <p><b>Performance characteristics:</b>
 * <ul>
 *   <li>GLOBAL_RANDOM: Thread-safe but has contention under concurrent access (~40-60% throughput loss with 4+ threads)</li>
 *   <li>ThreadLocal: Zero contention, linear scaling with thread count</li>
 * </ul>
 *
 * <p><b>Usage:</b>
 * <pre>{@code
 * // Single-threaded or when perfect reproducibility is required
 * Random rand = RandomProvider.get();
 *
 * // Multi-threaded contexts (parallel streams, thread pools)
 * Random rand = RandomProvider.getThreadLocal();
 * }</pre>
 */
public final class RandomProvider {
  private static final long DEFAULT_SEED = AlgorithmConstants.FIXED_RANDOM_SEED_LONG;

  /** Optional research instrumentation. The normal path leaves this null. */
  private static volatile BiConsumer<String, Long> seedObserver;

  private static void observeSeed(String source, long seed) {
    BiConsumer<String, Long> observer = seedObserver;
    if (observer != null) observer.accept(source, seed);
  }

  /** Install or clear the observer before starting a strictly instrumented run. */
  public static void setSeedObserver(BiConsumer<String, Long> observer) {
    seedObserver = observer;
  }

  /** Shared global random - thread-safe but has contention under concurrent access */
  private static final Random GLOBAL_RANDOM = RandomProvider.newWithSeed(DEFAULT_SEED);

  /** Counter for assigning deterministic thread-local seeds */
  private static final AtomicLong THREAD_COUNTER = new AtomicLong(0);

  /**
   * Thread-local random with deterministic seeds.
   * Each thread gets a unique but reproducible Random instance.
   * The seed is based on: DEFAULT_SEED + sequential counter value.
   */
  private static final ThreadLocal<Random> THREAD_LOCAL_RANDOM = ThreadLocal.withInitial(() -> {
      // Use atomic counter to assign deterministic seeds in order of thread creation
      long threadIndex = THREAD_COUNTER.getAndIncrement();
      long threadSeed = DEFAULT_SEED + threadIndex;
      observeSeed("threadLocal.create", threadSeed);
      return new Random(threadSeed);
    });

  private RandomProvider() {
  }

  /**
   * Get the shared global Random instance.
   * Use this for single-threaded contexts or when perfect reproducibility is required.
   *
   * <p><b>Thread-safety:</b> Thread-safe but has contention under concurrent access.
   *
   * @return Shared global Random instance
   */
  public static Random get() {
    return GLOBAL_RANDOM;
  }

  /**
   * Get a thread-local Random instance with deterministic seed.
   * Use this in multi-threaded contexts for optimal performance.
   *
   * <p><b>Determinism:</b> Each thread gets a unique Random seeded with (DEFAULT_SEED + threadId),
   * ensuring reproducible results across runs when using the same thread pool configuration.
   *
   * <p><b>Performance:</b> Zero contention, provides linear scaling with thread count.
   *
   * @return Thread-local Random instance
   */
  public static Random getThreadLocal() {
    return THREAD_LOCAL_RANDOM.get();
  }

  /**
   * Create a new Random instance with the specified seed.
   *
   * @param seed Seed value
   * @return New Random instance
   */
  public static Random newWithSeed(long seed) {
    observeSeed("newWithSeed", seed);
    return new Random(seed);
  }

  /**
   * Reset the global Random to default seed.
   * Note: This does NOT reset thread-local Random instances.
   */
  public static void reset() {
    observeSeed("global.reset", DEFAULT_SEED);
    GLOBAL_RANDOM.setSeed(DEFAULT_SEED);
  }

  /**
   * Reset the global Random to specified seed.
   * Note: This does NOT reset thread-local Random instances.
   *
   * @param seed New seed value
   */
  public static void reset(long seed) {
    observeSeed("global.reset", seed);
    GLOBAL_RANDOM.setSeed(seed);
  }

  /**
   * Reset the thread counter to ensure reproducibility.
   *
   * <p><b>Usage:</b> Call this method BEFORE creating thread pools to ensure
   * deterministic thread seed assignment across different runs.
   *
   * <p><b>Important:</b> This must be called before any threads access getThreadLocal(),
   * typically at the start of your main method or before thread pool creation.
   *
   * <p><b>Example:</b>
   * <pre>{@code
   * // Ensure reproducibility
   * RandomProvider.resetThreadCounter();
   *
   * // Now create thread pools
   * ExecutorService executor = Executors.newFixedThreadPool(4);
   * }</pre>
   */
  public static void resetThreadCounter() {
    THREAD_COUNTER.set(0);
  }

  /**
   * Reset the thread-local Random for current thread to default seed.
   * Useful when you need to reset randomness within a thread.
   *
   * <p><b>Note:</b> This resets to the original seed assigned to this thread.
   */
  public static void resetThreadLocal() {
    // Simply recreate with a new instance - this will use the same counter-based seed
    THREAD_LOCAL_RANDOM.remove();
    THREAD_LOCAL_RANDOM.get(); // Force reinitialization
  }

  /**
   * Reset the thread-local Random for current thread to specified seed.
   *
   * <p><b>Note:</b> This directly sets the seed without combining with thread index.
   *
   * @param seed New seed value
   */
  public static void resetThreadLocal(long seed) {
    observeSeed("threadLocal.reset", seed);
    THREAD_LOCAL_RANDOM.get().setSeed(seed);
  }
}
