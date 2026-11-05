package method.runtime;

import method.ProblemAdapter;
import model.ProblemInstance;
import model.ScheduledTask;
import model.TimeWindow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

/** 方法层只读候选访问；构建和统计均计入实例预算。 */
public final class ProblemAccess {
  private record Key(int taskId, int satelliteId) { }

  private final ProblemAdapter adapter;
  private final Map<Key, List<TimeWindow>> windows = new HashMap<>();
  private final Map<Key, Long> counts = new ConcurrentHashMap<>();
  private final Map<Integer, Double> capacities = new ConcurrentHashMap<>();
  private final Map<TimeWindow, Integer> windowIndices = new IdentityHashMap<>();
  private final List<Integer> satelliteIds;

  ProblemAccess(ProblemInstance instance, Budget budget) {
    budget.check();
    adapter = new ProblemAdapter(instance);
    budget.check();
    List<Integer> ids = new ArrayList<>();
    int index = 0;
    for (var satellite : instance.satellites()) {
      budget.check();
      ids.add(satellite.getId());
      for (TimeWindow window : satellite.getTimeWindows()) {
        budget.check();
        windowIndices.putIfAbsent(window, index++);
        windows.computeIfAbsent(new Key(window.getTaskId(), satellite.getId()), ignored -> new ArrayList<>())
            .add(window);
      }
    }
    ids.sort(Integer::compareTo);
    satelliteIds = List.copyOf(ids);
    windows.replaceAll((key, value) -> List.copyOf(value));
  }

  public List<Integer> satelliteIds() {
    return satelliteIds;
  }

  public List<TimeWindow> windows(int taskId, int satelliteId) {
    return windows.getOrDefault(new Key(taskId, satelliteId), List.of());
  }

  public Iterable<ScheduledTask> candidates(int taskId, int satelliteId, Budget budget) {
    List<TimeWindow> selected = windows(taskId, satelliteId);
    return () -> new Iterator<>() {
      private int windowIndex;
      private Iterator<ScheduledTask> current = Collections.emptyIterator();

      @Override public boolean hasNext() {
        budget.check();
        while (!current.hasNext() && windowIndex < selected.size()) {
          budget.check();
          current = adapter.candidates(selected.get(windowIndex++)).iterator();
        }
        budget.check();
        return current.hasNext();
      }

      @Override public ScheduledTask next() {
        if (!hasNext()) throw new NoSuchElementException();
        return current.next();
      }
    };
  }

  public long candidateCount(int taskId, int satelliteId, Budget budget) {
    Key key = new Key(taskId, satelliteId);
    Long known = counts.get(key);
    if (known != null) return known;
    long count = 0;
    for (ScheduledTask ignored : candidates(taskId, satelliteId, budget)) {
      budget.check();
      count++;
    }
    counts.put(key, count);
    return count;
  }

  public boolean hasCandidate(int taskId, int satelliteId, Budget budget) {
    Long known = counts.get(new Key(taskId, satelliteId));
    return known != null ? known > 0 : candidates(taskId, satelliteId, budget).iterator().hasNext();
  }

  /** 可用窗口长度总和只用于分配时归一化负载，不作为问题约束。 */
  public double capacity(int satelliteId, Budget budget) {
    Double known = capacities.get(satelliteId);
    if (known != null) return known;
    double total = 0;
    for (var entry : windows.entrySet()) {
      budget.check();
      if (entry.getKey().satelliteId() != satelliteId) continue;
      for (TimeWindow window : entry.getValue()) {
        budget.check();
        if (adapter.candidates(window).iterator().hasNext()) {
          total += Math.max(0, window.getEndTime() - window.getStartTime());
        }
      }
    }
    capacities.put(satelliteId, total);
    return total;
  }

  int windowIndex(TimeWindow window) {
    Integer index = windowIndices.get(window);
    if (index == null) throw new IllegalArgumentException("window not in instance");
    return index;
  }
}
