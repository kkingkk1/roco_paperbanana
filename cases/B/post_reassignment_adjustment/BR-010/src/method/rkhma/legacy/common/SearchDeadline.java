package method.rkhma.legacy.common;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/** 一次运行共享的单调墙钟；不重置、不取消线程，与资源保护超时分离。 */
public final class SearchDeadline {
  private static final SearchDeadline UNLIMITED = new SearchDeadline();
  private final LongSupplier clock;
  private final long started;
  private final long limit;
  private final SearchDeadline parent;

  private SearchDeadline() { clock = null; started = 0; limit = Long.MAX_VALUE; parent = null; }

  public SearchDeadline(Duration budget) { this(budget, System::nanoTime); }

  /** 可注入单调时钟，用于精确边界测试，不用于实验选参。 */
  public SearchDeadline(Duration budget, LongSupplier clock) {
    if (budget == null || budget.isNegative()) { throw new IllegalArgumentException("预算须非负"); }
    this.clock = Objects.requireNonNull(clock);
    this.limit = budget.toNanos();
    this.started = clock.getAsLong();
    this.parent = null;
  }

  private SearchDeadline(LongSupplier clock, long started, long limit, SearchDeadline parent) {
    this.clock = Objects.requireNonNull(clock); this.started = started; this.limit = limit; this.parent = parent;
  }

  private static long checkedNanos(Duration duration) {
    if (duration == null || duration.isNegative()) throw new IllegalArgumentException("nonnegative duration required");
    return duration.toNanos();
  }

  /** Anchor a deadline to an earlier reading of the same monotonic clock. */
  public static SearchDeadline startingAt(Duration budget, LongSupplier clock, long started) {
    return new SearchDeadline(clock, started, checkedNanos(budget), null);
  }

  /** Create a relative deadline that cannot outlive the parent's absolute deadline. */
  public SearchDeadline cap(Duration budget) {
    if (!limited()) throw new IllegalStateException("finite parent deadline required");
    long n = checkedNanos(budget);
    return new SearchDeadline(clock, clock.getAsLong(), n, this);
  }

  /** Preserve a tail of the parent's interval without rebuilding a relative deadline. */
  public SearchDeadline reserveFromEnd(Duration reserve) {
    if (!limited()) throw new IllegalStateException("finite parent deadline required");
    long n = checkedNanos(reserve); long childLimit = n >= limit ? 0 : limit - n;
    return new SearchDeadline(clock, started, childLimit, parent == null ? null : parent.reserveFromEnd(reserve));
  }

  public static SearchDeadline unlimited() { return UNLIMITED; }
  public boolean limited() { return clock != null; }
  public long elapsedNanos() { return limited() ? Math.max(0, clock.getAsLong() - started) : 0; }
  public long remainingNanos() {
    if (!limited()) return Long.MAX_VALUE;
    long remaining = Math.max(0, limit - elapsedNanos());
    return parent == null ? remaining : Math.min(remaining, parent.remainingNanos());
  }
  public boolean expired() { return limited() && (elapsedNanos() >= limit || parent != null && parent.expired()); }
  public void check() { if (expired()) { throw new Expired(); } }

  public static boolean causedExpiry(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof Expired) { return true; }
    }
    return false;
  }

  /** 仅在安全候选边界抛出；不得将其当成普通优化失败继续搜索。 */
  public static final class Expired extends RuntimeException {
    private Expired() { super("算法墙钟预算耗尽", null, false, false); }
  }
}
