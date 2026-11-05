package method.runtime;

import java.util.Objects;
import java.util.function.LongSupplier;

/** 单实例共享的单调时钟；局部截止不能延长实例截止。 */
public final class Budget {
  private final LongSupplier clock;
  private final long start;
  private final long hardNanos;
  private final long cutoffNanos;

  Budget(LongSupplier clock, long start, long hardNanos, long cutoffNanos) {
    this.clock = Objects.requireNonNull(clock);
    this.start = start;
    this.hardNanos = hardNanos;
    this.cutoffNanos = cutoffNanos;
  }

  public long elapsedNanos() {
    return clock.getAsLong() - start;
  }

  public void check() {
    long elapsed = elapsedNanos();
    if (elapsed >= hardNanos) {
      throw new HardTimeout();
    }
    if (elapsed >= cutoffNanos) {
      throw new SearchExpired();
    }
  }

  void checkHard() {
    if (elapsedNanos() >= hardNanos) {
      throw new HardTimeout();
    }
  }

  Budget within(long durationNanos) {
    long elapsed = elapsedNanos();
    long requested = durationNanos > Long.MAX_VALUE - elapsed ? Long.MAX_VALUE : elapsed + durationNanos;
    return new Budget(clock, start, hardNanos, Math.min(cutoffNanos, requested));
  }

  static final class SearchExpired extends RuntimeException { }
  static final class HardTimeout extends RuntimeException { }
}
