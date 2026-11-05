package method.runtime;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** 单次B调用的受控试评范围；并发请求串行检查，结束后不可复用。 */
final class TrialGuard implements AutoCloseable {
  private final Budget budget;
  private final Set<Object> issued = Collections.newSetFromMap(new IdentityHashMap<>());
  private boolean open = true;
  private RuntimeException failure;

  TrialGuard(Budget budget) { this.budget = Objects.requireNonNull(budget); }

  synchronized <T> T evaluate(Supplier<T> action) {
    checkOpen();
    if (failure != null) throw failure;
    try {
      budget.check();
      T trial = Objects.requireNonNull(action.get(), "null candidate trial");
      budget.check();
      issued.add(trial);
      return trial;
    } catch (RuntimeException error) {
      failure = error;
      throw error;
    }
  }

  synchronized <T> T select(T trial, boolean required) {
    checkOpen();
    budget.check();
    if (failure != null) throw failure;
    if ((trial == null && required) || (trial != null && !issued.contains(trial))) {
      throw new IllegalArgumentException("B returned a candidate not issued by the current evaluator");
    }
    return trial;
  }

  private void checkOpen() {
    if (!open) {
      throw new IllegalStateException("candidate evaluator is closed");
    }
  }

  @Override public synchronized void close() {
    open = false;
    issued.clear();
  }
}
