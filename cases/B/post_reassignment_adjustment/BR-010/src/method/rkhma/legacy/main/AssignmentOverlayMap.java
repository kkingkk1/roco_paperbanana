package method.rkhma.legacy.main;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

final class AssignmentOverlayMap extends AbstractMap<Integer, Integer> {
  private static final Object REMOVED = new Object();

  private final Map<Integer, Integer> base;
  private final LinkedHashMap<Integer, Object> overrides;

  private AssignmentOverlayMap(Map<Integer, Integer> base, LinkedHashMap<Integer, Object> overrides) {
    this.base = base;
    this.overrides = overrides;
  }

  static AssignmentOverlayMap over(Map<Integer, Integer> base) {
    return new AssignmentOverlayMap(base, new LinkedHashMap<>());
  }

  AssignmentOverlayMap withAssigned(int taskId, int satelliteId) {
    LinkedHashMap<Integer, Object> next = new LinkedHashMap<>(overrides);
    next.put(taskId, satelliteId);
    return new AssignmentOverlayMap(base, next);
  }

  AssignmentOverlayMap withRemoved(int taskId) {
    LinkedHashMap<Integer, Object> next = new LinkedHashMap<>(overrides);
    next.put(taskId, REMOVED);
    return new AssignmentOverlayMap(base, next);
  }

  Map<Integer, Integer> materialize() {
    Map<Integer, Integer> materialized = new HashMap<>(mapCapacity(base.size() + overrides.size()));
    materialized.putAll(base);
    for (Map.Entry<Integer, Object> entry : overrides.entrySet()) {
      if (entry.getValue() == REMOVED) {
        materialized.remove(entry.getKey());
      } else {
        materialized.put(entry.getKey(), (Integer) entry.getValue());
      }
    }
    return materialized;
  }

  @Override
  public Integer get(Object key) {
    if (overrides.containsKey(key)) {
      Object value = overrides.get(key);
      return value == REMOVED ? null : (Integer) value;
    }
    return base.get(key);
  }

  @Override
  public boolean containsKey(Object key) {
    if (overrides.containsKey(key)) {
      return overrides.get(key) != REMOVED;
    }
    return base.containsKey(key);
  }

  @Override
  public int size() {
    int size = base.size();
    for (Map.Entry<Integer, Object> entry : overrides.entrySet()) {
      boolean baseHasKey = base.containsKey(entry.getKey());
      boolean removed = entry.getValue() == REMOVED;
      if (removed && baseHasKey) {
        size--;
      } else if (!removed && !baseHasKey) {
        size++;
      }
    }
    return size;
  }

  @Override
  public Set<Map.Entry<Integer, Integer>> entrySet() {
    if (isEmpty()) {
      return Collections.emptySet();
    }
    return new AbstractSet<>() {
      @Override
      public Iterator<Map.Entry<Integer, Integer>> iterator() {
        return new OverlayEntryIterator();
      }

      @Override
      public int size() {
        return AssignmentOverlayMap.this.size();
      }
    };
  }

  @Override
  public Integer put(Integer key, Integer value) {
    throw new UnsupportedOperationException("AssignmentOverlayMap is read-only");
  }

  @Override
  public Integer remove(Object key) {
    throw new UnsupportedOperationException("AssignmentOverlayMap is read-only");
  }

  @Override
  public void clear() {
    throw new UnsupportedOperationException("AssignmentOverlayMap is read-only");
  }

  private static int mapCapacity(int expectedSize) {
    return Math.max(16, (int) (expectedSize / 0.75f) + 1);
  }

  private final class OverlayEntryIterator implements Iterator<Map.Entry<Integer, Integer>> {
    private final Iterator<Map.Entry<Integer, Integer>> baseIterator = base.entrySet().iterator();
    private final Iterator<Map.Entry<Integer, Object>> overrideIterator = overrides.entrySet().iterator();
    private Map.Entry<Integer, Integer> next;
    private boolean overridePhase;

    @Override
    public boolean hasNext() {
      if (next != null) {
        return true;
      }
      while (!overridePhase && baseIterator.hasNext()) {
        Map.Entry<Integer, Integer> baseEntry = baseIterator.next();
        if (overrides.containsKey(baseEntry.getKey())) {
          Object override = overrides.get(baseEntry.getKey());
          if (override == REMOVED) {
            continue;
          }
          next = Map.entry(baseEntry.getKey(), (Integer) override);
          return true;
        }
        next = Map.entry(baseEntry.getKey(), baseEntry.getValue());
        return true;
      }
      overridePhase = true;
      while (overrideIterator.hasNext()) {
        Map.Entry<Integer, Object> overrideEntry = overrideIterator.next();
        if (overrideEntry.getValue() == REMOVED || base.containsKey(overrideEntry.getKey())) {
          continue;
        }
        next = Map.entry(overrideEntry.getKey(), (Integer) overrideEntry.getValue());
        return true;
      }
      return false;
    }

    @Override
    public Map.Entry<Integer, Integer> next() {
      if (!hasNext()) {
        throw new NoSuchElementException();
      }
      Map.Entry<Integer, Integer> current = next;
      next = null;
      return current;
    }
  }
}
