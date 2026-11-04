package method;

import model.AttitudeRecord;
import model.ProblemInstance;
import model.ScheduledTask;
import model.Task;
import model.TimeWindow;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.OptionalDouble;

/** 将问题实例映射为只读内部网格，不选择任务、窗口或起点。 */
public final class ProblemAdapter {
  private static final double DURATION_EPSILON = 1e-6;

  private final ProblemInstance instance;
  private final Map<TimeWindow, Grid> grids = new IdentityHashMap<>();

  public ProblemAdapter(ProblemInstance instance) {
    this.instance = Objects.requireNonNull(instance, "instance");
    instance.satellites().forEach(satellite -> satellite.getTimeWindows().forEach(window ->
        grids.put(window, new Grid(window))));
  }

  /** 获取实例窗口的内部记录快照；内部对象仍是原始姿态对象。 */
  public Grid grid(TimeWindow window) {
    Grid grid = grids.get(window);
    if (grid == null) {
      throw new IllegalArgumentException("Window is not in this instance");
    }
    return grid;
  }

  /** 枚举单窗内满足窗口和精确时长的观测；跨任务约束由完整方案验证。 */
  public Iterable<ScheduledTask> candidates(TimeWindow window) {
    Grid grid = grid(window);
    Task task = instance.task(window.getTaskId());
    return () -> new CandidateIterator(task, window, grid);
  }

  public static final class Grid {
    private final List<AttitudeRecord> records;

    private Grid(TimeWindow window) {
      List<AttitudeRecord> raw = window.getAttitudeRecords();
      records = raw.size() < 3 ? List.of() : List.copyOf(raw.subList(1, raw.size() - 1));
    }

    public int size() {
      return records.size();
    }

    public List<AttitudeRecord> records() {
      return records;
    }

    /** 内部记录不足两条时不存在可确定的步长。 */
    public OptionalDouble stepSeconds() {
      return records.size() < 2 ? OptionalDouble.empty()
          : OptionalDouble.of(records.get(1).getTotalSeconds() - records.get(0).getTotalSeconds());
    }
  }

  private static final class CandidateIterator implements Iterator<ScheduledTask> {
    private final Task task;
    private final TimeWindow window;
    private final Grid grid;
    private int start;
    private int lowerEnd = 1;
    private int end = -1;
    private ScheduledTask next;

    private CandidateIterator(Task task, TimeWindow window, Grid grid) {
      this.task = task;
      this.window = window;
      this.grid = grid;
      findNext();
    }

    @Override
    public boolean hasNext() {
      return next != null;
    }

    @Override
    public ScheduledTask next() {
      if (next == null) {
        throw new NoSuchElementException();
      }
      ScheduledTask result = next;
      findNext();
      return result;
    }

    private void findNext() {
      next = null;
      while (start + 1 < grid.size()) {
        AttitudeRecord first = grid.records().get(start);
        double firstTime = first.getTotalSeconds();
        if (end < 0) {
          lowerEnd = Math.max(lowerEnd, start + 1);
          while (lowerEnd < grid.size()
              && grid.records().get(lowerEnd).getTotalSeconds() - firstTime - task.getImagingDuration()
                  < -DURATION_EPSILON) {
            lowerEnd++;
          }
          end = lowerEnd;
        }
        while (end < grid.size()) {
          AttitudeRecord last = grid.records().get(end++);
          double lastTime = last.getTotalSeconds();
          double difference = lastTime - firstTime - task.getImagingDuration();
          if (difference > DURATION_EPSILON) {
            break;
          }
          if (Math.abs(difference) <= DURATION_EPSILON && firstTime >= window.getStartTime()
              && lastTime <= window.getEndTime() && lastTime > firstTime) {
            next = new ScheduledTask(task, window, first, last);
            return;
          }
        }
        start++;
        end = -1;
      }
    }
  }
}
