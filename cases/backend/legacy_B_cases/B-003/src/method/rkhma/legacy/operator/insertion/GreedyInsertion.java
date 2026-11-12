package method.rkhma.legacy.operator.insertion;

import method.rkhma.legacy.model.AttitudeRecord;
import method.rkhma.legacy.model.ScheduledTask;
import method.rkhma.legacy.model.Task;
import method.rkhma.legacy.model.TimeWindow;
import method.rkhma.legacy.utils.TimeUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Greedy window insertion strategy.
 *
 * By default, sorts windows by score and selects the first feasible one; in controlled scenarios,
 * allows adding a lightweight independent preference term to window scores.
 *
 * Dependencies: model.AttitudeRecord, model.ScheduledTask, model.Task, model.TimeWindow, utils.TimeUtils, java.util.ArrayList, java.util.Arrays, java.util.List, operator.insertion.AbstractInsertionStrategy
 */
public final class GreedyInsertion extends AbstractInsertionStrategy {

  private static final double WINDOW_TIME_SCALE = 1.0e-3;
  private static final double WINDOW_NOISE_SCALE = 1.0e-2;
  private static final int START_INDEX_ESTIMATE_SCAN_LIMIT = 4;

  public static final class WindowPreferenceStats {
    private long calls;
    private long totalCandidateWindows;
    private long totalTriedWindows;
    private long totalChosenRank;
    private long top1HitCount;
    private long fallbackCount;
    private long maxChosenRank;

    public long getCalls() {
      return calls;
    }

    public long getTotalCandidateWindows() {
      return totalCandidateWindows;
    }

    public long getTotalTriedWindows() {
      return totalTriedWindows;
    }

    public long getTotalChosenRank() {
      return totalChosenRank;
    }

    public long getTop1HitCount() {
      return top1HitCount;
    }

    public long getFallbackCount() {
      return fallbackCount;
    }

    public long getMaxChosenRank() {
      return maxChosenRank;
    }
  }

  public static final class ReusableInsertionResult {
    private ScheduledTask task;
    private int insertIndex = -1;
    private ScheduledTaskProvider taskProvider;

    public ScheduledTask getTask() {
      return task;
    }

    public int getInsertIndex() {
      return insertIndex;
    }

    public boolean isFound() {
      return task != null;
    }

    public void setTaskProvider(ScheduledTaskProvider taskProvider) {
      this.taskProvider = taskProvider;
    }

    private void set(ScheduledTask task, int insertIndex) {
      this.task = task;
      this.insertIndex = insertIndex;
    }

    private void clear() {
      task = null;
      insertIndex = -1;
    }
  }

  @FunctionalInterface
  public interface ScheduledTaskProvider {
    ScheduledTask provide(Task task, TimeWindow window, AttitudeRecord startRecord, AttitudeRecord endRecord);
  }

  @Override
  public InsertionResult findBestPosition(
    Task task, int satelliteId, List<ScheduledTask> currentSchedule) {

    List<TimeWindow> windows = task.getTimeWindowsForSatellite(satelliteId);
    if (windows == null || windows.isEmpty()) {
      return null;
    }
    return findFirstFeasibleInsertion(task, windows, currentSchedule);
  }

  public boolean findBestPositionInto(
    Task task,
    int satelliteId,
    List<ScheduledTask> currentSchedule,
    ReusableInsertionResult result
  ) {
    if (result == null) {
      throw new IllegalArgumentException("Reusable insertion result cannot be null");
    }
    result.clear();
    List<TimeWindow> windows = task.getTimeWindowsForSatellite(satelliteId);
    if (windows == null || windows.isEmpty()) {
      return false;
    }
    findFirstFeasibleInsertion(task, windows, currentSchedule, null, 0, result);
    return result.isFound();
  }

  public InsertionResult findBestPositionWithReverseWindowTieBreak(
    Task task, int satelliteId, List<ScheduledTask> currentSchedule) {

    return findBestPositionWithWindowPreference(task, satelliteId, currentSchedule, 0.0);
  }

  public InsertionResult findBestPositionWithWindowPreference(
    Task task,
    int satelliteId,
    List<ScheduledTask> currentSchedule,
    double windowPreferenceBias
  ) {

    List<TimeWindow> windows = task.getTimeWindowsForSatellite(satelliteId);
    if (windows == null || windows.isEmpty()) {
      return null;
    }

    List<TimeWindow> rankedWindows = rankWindows(task, satelliteId, windows, windowPreferenceBias);
    return findFirstFeasibleInsertion(task, rankedWindows, currentSchedule);
  }

  public InsertionResult findBestPositionWithWindowPreference(
    Task task,
    int satelliteId,
    List<ScheduledTask> currentSchedule,
    double windowPreferenceBias,
    WindowPreferenceStats stats
  ) {

    List<TimeWindow> windows = task.getTimeWindowsForSatellite(satelliteId);
    if (windows == null || windows.isEmpty()) {
      return null;
    }

    List<TimeWindow> rankedWindows = rankWindows(task, satelliteId, windows, windowPreferenceBias);
    return findFirstFeasibleInsertion(task, rankedWindows, currentSchedule, stats, rankedWindows.size());
  }

  public boolean findBestPositionWithWindowPreferenceInto(
    Task task,
    int satelliteId,
    List<ScheduledTask> currentSchedule,
    double windowPreferenceBias,
    WindowPreferenceStats stats,
    ReusableInsertionResult result
  ) {
    if (result == null) {
      throw new IllegalArgumentException("Reusable insertion result cannot be null");
    }
    result.clear();
    List<TimeWindow> windows = task.getTimeWindowsForSatellite(satelliteId);
    if (windows == null || windows.isEmpty()) {
      return false;
    }

    List<TimeWindow> rankedWindows = rankWindows(task, satelliteId, windows, windowPreferenceBias);
    findFirstFeasibleInsertion(task, rankedWindows, currentSchedule, stats, rankedWindows.size(), result);
    return result.isFound();
  }

  public WindowPreferenceStats evaluateWindowPreferenceStats(
    Task task,
    int satelliteId,
    List<ScheduledTask> currentSchedule,
    double windowPreferenceBias
  ) {
    WindowPreferenceStats stats = new WindowPreferenceStats();
    if (task == null) {
      return stats;
    }
    List<TimeWindow> windows = task.getTimeWindowsForSatellite(satelliteId);
    if (windows == null || windows.isEmpty()) {
      return stats;
    }
    List<TimeWindow> rankedWindows = rankWindows(task, satelliteId, windows, windowPreferenceBias);
    recordFirstFeasibleWindowPreferenceStats(task, rankedWindows, currentSchedule, stats, rankedWindows.size());
    return stats;
  }

  private void recordWindowPreferenceStats(
    WindowPreferenceStats stats,
    int candidateWindowCount,
    int triedWindows,
    int chosenRank
  ) {
    if (stats == null) {
      return;
    }
    stats.calls++;
    stats.totalCandidateWindows += candidateWindowCount;
    stats.totalTriedWindows += triedWindows;
    if (chosenRank > 0) {
      stats.totalChosenRank += chosenRank;
      stats.maxChosenRank = Math.max(stats.maxChosenRank, chosenRank);
      if (chosenRank == 1) {
        stats.top1HitCount++;
      } else {
        stats.fallbackCount++;
      }
    }
  }

  private List<TimeWindow> rankWindows(
    Task task,
    int satelliteId,
    List<TimeWindow> windows,
    double windowPreferenceBias
  ) {
    int size = windows.size();
    // Pre-compute scores once O(W) instead of O(W log W) in comparator
    double[] scores = new double[size];
    for (int i = 0; i < size; i++) {
      scores[i] = computeWindowScore(task, satelliteId, windows.get(i), windowPreferenceBias);
    }
    Integer[] indices = new Integer[size];
    for (int i = 0; i < size; i++) indices[i] = i;
    Arrays.sort(indices, (a, b) -> {
      int cmp = Double.compare(scores[b], scores[a]); // descending by score
      if (cmp != 0) return cmp;
      cmp = Double.compare(windows.get(a).getStartTime(), windows.get(b).getStartTime());
      if (cmp != 0) return cmp;
      return Double.compare(windows.get(a).getEndTime(), windows.get(b).getEndTime());
    });
    List<TimeWindow> result = new ArrayList<>(size);
    for (int idx : indices) result.add(windows.get(idx));
    return result;
  }

  private double computeWindowScore(Task task, int satelliteId, TimeWindow window, double windowPreferenceBias) {
    if (task == null || window == null) {
      return Double.NEGATIVE_INFINITY;
    }
    double earlyWindowScore = -window.getStartTime() * WINDOW_TIME_SCALE;
    double longerWindowScore = Math.max(0.0, window.getEndTime() - window.getStartTime());
    double independentNoise = computeIndependentWindowNoise(task, satelliteId, window) * WINDOW_NOISE_SCALE;
    return earlyWindowScore + longerWindowScore + independentNoise + windowPreferenceBias;
  }

  private double computeIndependentWindowNoise(Task task, int satelliteId, TimeWindow window) {
    long hash = 1469598103934665603L;
    hash = mixHash(hash, task.getId());
    hash = mixHash(hash, satelliteId);
    hash = mixHash(hash, Double.doubleToLongBits(window.getStartTime()));
    hash = mixHash(hash, Double.doubleToLongBits(window.getEndTime()));
    long positive = hash & Long.MAX_VALUE;
    return positive / (double) Long.MAX_VALUE;
  }

  private long mixHash(long seed, long value) {
    return (seed ^ value) * 1099511628211L;
  }

  private void recordFirstFeasibleWindowPreferenceStats(
    Task task,
    List<TimeWindow> windows,
    List<ScheduledTask> currentSchedule,
    WindowPreferenceStats stats,
    int candidateWindowCount
  ) {
    int taskDuration = task.getImagingDuration();
    if (currentSchedule.isEmpty()) {
      recordEmptyScheduleWindowPreferenceStats(windows, taskDuration, stats, candidateWindowCount);
      return;
    }
    int scheduleSize = currentSchedule.size();
    int triedWindows = 0;
    int chosenRank = -1;
    int insertionIndex = -1;
    ScheduledTask prevTask = null;
    ScheduledTask nextTask = null;
    AttitudeRecord prevEndRecord = null;
    AttitudeRecord nextStartRecord = null;
    double prevEndTime = Double.NEGATIVE_INFINITY;
    double nextStartTime = Double.POSITIVE_INFINITY;
    double previousStartTime = Double.NEGATIVE_INFINITY;
    for (int windowIndex = 0; windowIndex < windows.size(); windowIndex++) {
      TimeWindow window = windows.get(windowIndex);
      triedWindows++;
      if (!window.canAccommodateTask(taskDuration)) continue;

      // 起止记录均排除首尾，最晚结束索引为 recordCount - 2。
      int maxStartIndex = window.getAttitudeRecordCount() - 2 - taskDuration;
      double[] recordTimes = window.getAttitudeRecordTimesUnsafe();
      for (int i = 1; i <= maxStartIndex; i++) {
        AttitudeRecord candidateStart = null;
        AttitudeRecord candidateEnd = null;
        double startTime;
        double endTime;
        if (recordTimes != null) {
          startTime = recordTimes[i];
          endTime = recordTimes[i + taskDuration];
        } else {
          candidateStart = window.getAttitudeRecord(i);
          candidateEnd = window.getAttitudeRecord(i + taskDuration);
          startTime = candidateStart.getTotalSeconds();
          endTime = candidateEnd.getTotalSeconds();
        }

        if (insertionIndex < 0 || startTime < previousStartTime) {
          insertionIndex = findInsertionIndex(currentSchedule, startTime);
          if (insertionIndex > 0) {
            prevTask = currentSchedule.get(insertionIndex - 1);
            prevEndRecord = prevTask.getEndRecord();
            prevEndTime = prevEndRecord.getTotalSeconds();
          } else {
            prevTask = null;
            prevEndRecord = null;
            prevEndTime = Double.NEGATIVE_INFINITY;
          }
          if (insertionIndex < scheduleSize) {
            nextTask = currentSchedule.get(insertionIndex);
            nextStartRecord = nextTask.getStartRecord();
            nextStartTime = nextStartRecord.getTotalSeconds();
          } else {
            nextTask = null;
            nextStartRecord = null;
            nextStartTime = Double.POSITIVE_INFINITY;
          }
        } else {
          while (nextTask != null && nextStartTime < startTime) {
            prevTask = nextTask;
            prevEndRecord = nextTask.getEndRecord();
            prevEndTime = prevEndRecord.getTotalSeconds();
            insertionIndex++;
            if (insertionIndex < scheduleSize) {
              nextTask = currentSchedule.get(insertionIndex);
              nextStartRecord = nextTask.getStartRecord();
              nextStartTime = nextStartRecord.getTotalSeconds();
            } else {
              nextTask = null;
              nextStartRecord = null;
              nextStartTime = Double.POSITIVE_INFINITY;
            }
          }
        }
        previousStartTime = startTime;

        if (prevTask != null &&
          startTime - prevEndTime < TimeUtils.MIN_TRANSFORMATION_TIME_SECONDS) {
          double targetStartTime = prevEndTime + TimeUtils.MIN_TRANSFORMATION_TIME_SECONDS;
          boolean strict = false;
          if (nextTask != null && targetStartTime > nextStartTime) {
            targetStartTime = nextStartTime;
            strict = true;
          }
          int nextStartIndex = strict
            ? findStartIndexAfter(window, recordTimes, i + 1, maxStartIndex, targetStartTime)
            : findStartIndexAtOrAfter(window, recordTimes, i + 1, maxStartIndex, targetStartTime);
          if (nextStartIndex < 0) break;
          i = nextStartIndex - 1;
          continue;
        }
        if (nextTask != null &&
          nextStartTime - endTime < TimeUtils.MIN_TRANSFORMATION_TIME_SECONDS) {
          int nextStartIndex = findStartIndexAfter(window, recordTimes, i + 1, maxStartIndex, nextStartTime);
          if (nextStartIndex < 0) break;
          i = nextStartIndex - 1;
          continue;
        }

        if (recordTimes != null) {
          candidateStart = window.getAttitudeRecord(i);
          candidateEnd = window.getAttitudeRecord(i + taskDuration);
        }
        boolean transitionFromPrevOK = true;
        boolean transitionToNextOK = true;

        if (prevTask != null) {
          transitionFromPrevOK = TimeUtils.checkTransformationTime(
            prevEndRecord, candidateStart);
        }
        if (nextTask != null) {
          transitionToNextOK = TimeUtils.checkTransformationTime(
            candidateEnd, nextStartRecord);
        }

        if (transitionFromPrevOK && transitionToNextOK) {
          chosenRank = triedWindows;
          recordWindowPreferenceStats(stats, candidateWindowCount, triedWindows, chosenRank);
          return;
        }
      }
    }

    recordWindowPreferenceStats(stats, candidateWindowCount, triedWindows, chosenRank);
  }

  private InsertionResult findFirstFeasibleInsertion(
    Task task,
    List<TimeWindow> windows,
    List<ScheduledTask> currentSchedule
  ) {
    return findFirstFeasibleInsertion(task, windows, currentSchedule, null, 0);
  }

  private InsertionResult findFirstFeasibleInsertion(
    Task task,
    List<TimeWindow> windows,
    List<ScheduledTask> currentSchedule,
    WindowPreferenceStats stats,
    int candidateWindowCount
  ) {
    return findFirstFeasibleInsertion(task, windows, currentSchedule, stats, candidateWindowCount, null);
  }

  private InsertionResult findFirstFeasibleInsertion(
    Task task,
    List<TimeWindow> windows,
    List<ScheduledTask> currentSchedule,
    WindowPreferenceStats stats,
    int candidateWindowCount,
    ReusableInsertionResult reusableResult
  ) {
    int taskDuration = task.getImagingDuration();
    if (currentSchedule.isEmpty()) {
      return findEmptyScheduleInsertion(task, windows, taskDuration, stats, candidateWindowCount, reusableResult);
    }
    int scheduleSize = currentSchedule.size();
    int triedWindows = 0;
    int chosenRank = -1;
    int insertionIndex = -1;
    ScheduledTask prevTask = null;
    ScheduledTask nextTask = null;
    AttitudeRecord prevEndRecord = null;
    AttitudeRecord nextStartRecord = null;
    double prevEndTime = Double.NEGATIVE_INFINITY;
    double nextStartTime = Double.POSITIVE_INFINITY;
    double previousStartTime = Double.NEGATIVE_INFINITY;
    for (int windowIndex = 0; windowIndex < windows.size(); windowIndex++) {
      TimeWindow window = windows.get(windowIndex);
      triedWindows++;
      if (!window.canAccommodateTask(taskDuration)) continue;

      // 与候选合同一致：最后一条边界记录不能作为成像终点。
      int maxStartIndex = window.getAttitudeRecordCount() - 2 - taskDuration;
      double[] recordTimes = window.getAttitudeRecordTimesUnsafe();
      for (int i = 1; i <= maxStartIndex; i++) {
        AttitudeRecord candidateStart = null;
        AttitudeRecord candidateEnd = null;
        double startTime;
        double endTime;
        if (recordTimes != null) {
          startTime = recordTimes[i];
          endTime = recordTimes[i + taskDuration];
        } else {
          candidateStart = window.getAttitudeRecord(i);
          candidateEnd = window.getAttitudeRecord(i + taskDuration);
          startTime = candidateStart.getTotalSeconds();
          endTime = candidateEnd.getTotalSeconds();
        }

        if (insertionIndex < 0 || startTime < previousStartTime) {
          insertionIndex = findInsertionIndex(currentSchedule, startTime);
          if (insertionIndex > 0) {
            prevTask = currentSchedule.get(insertionIndex - 1);
            prevEndRecord = prevTask.getEndRecord();
            prevEndTime = prevEndRecord.getTotalSeconds();
          } else {
            prevTask = null;
            prevEndRecord = null;
            prevEndTime = Double.NEGATIVE_INFINITY;
          }
          if (insertionIndex < scheduleSize) {
            nextTask = currentSchedule.get(insertionIndex);
            nextStartRecord = nextTask.getStartRecord();
            nextStartTime = nextStartRecord.getTotalSeconds();
          } else {
            nextTask = null;
            nextStartRecord = null;
            nextStartTime = Double.POSITIVE_INFINITY;
          }
        } else {
          while (nextTask != null && nextStartTime < startTime) {
            prevTask = nextTask;
            prevEndRecord = nextTask.getEndRecord();
            prevEndTime = prevEndRecord.getTotalSeconds();
            insertionIndex++;
            if (insertionIndex < scheduleSize) {
              nextTask = currentSchedule.get(insertionIndex);
              nextStartRecord = nextTask.getStartRecord();
              nextStartTime = nextStartRecord.getTotalSeconds();
            } else {
              nextTask = null;
              nextStartRecord = null;
              nextStartTime = Double.POSITIVE_INFINITY;
            }
          }
        }
        previousStartTime = startTime;

        if (prevTask != null &&
          startTime - prevEndTime < TimeUtils.MIN_TRANSFORMATION_TIME_SECONDS) {
          double targetStartTime = prevEndTime + TimeUtils.MIN_TRANSFORMATION_TIME_SECONDS;
          boolean strict = false;
          if (nextTask != null && targetStartTime > nextStartTime) {
            targetStartTime = nextStartTime;
            strict = true;
          }
          int nextStartIndex = strict
            ? findStartIndexAfter(window, recordTimes, i + 1, maxStartIndex, targetStartTime)
            : findStartIndexAtOrAfter(window, recordTimes, i + 1, maxStartIndex, targetStartTime);
          if (nextStartIndex < 0) break;
          i = nextStartIndex - 1;
          continue;
        }
        if (nextTask != null &&
          nextStartTime - endTime < TimeUtils.MIN_TRANSFORMATION_TIME_SECONDS) {
          int nextStartIndex = findStartIndexAfter(window, recordTimes, i + 1, maxStartIndex, nextStartTime);
          if (nextStartIndex < 0) break;
          i = nextStartIndex - 1;
          continue;
        }

        if (recordTimes != null) {
          candidateStart = window.getAttitudeRecord(i);
          candidateEnd = window.getAttitudeRecord(i + taskDuration);
        }
        boolean transitionFromPrevOK = true;
        boolean transitionToNextOK = true;

        if (prevTask != null) {
          transitionFromPrevOK = TimeUtils.checkTransformationTime(
            prevEndRecord, candidateStart);
        }
        if (nextTask != null) {
          transitionToNextOK = TimeUtils.checkTransformationTime(
            candidateEnd, nextStartRecord);
        }

        if (transitionFromPrevOK && transitionToNextOK) {
          ScheduledTask newTask = createScheduledTask(
            task,
            window,
            candidateStart,
            candidateEnd,
            reusableResult
          );
          chosenRank = triedWindows;
          recordWindowPreferenceStats(stats, candidateWindowCount, triedWindows, chosenRank);
          if (reusableResult != null) {
            reusableResult.set(newTask, insertionIndex);
            return null;
          }
          return new InsertionResult(newTask, insertionIndex);
        }
      }
    }

    recordWindowPreferenceStats(stats, candidateWindowCount, triedWindows, chosenRank);
    return null;
  }

  private void recordEmptyScheduleWindowPreferenceStats(
    List<TimeWindow> windows,
    int taskDuration,
    WindowPreferenceStats stats,
    int candidateWindowCount
  ) {
    int triedWindows = 0;
    int chosenRank = -1;
    for (int windowIndex = 0; windowIndex < windows.size(); windowIndex++) {
      TimeWindow window = windows.get(windowIndex);
      triedWindows++;
      if (window.canAccommodateTask(taskDuration)) {
        chosenRank = triedWindows;
        break;
      }
    }
    recordWindowPreferenceStats(stats, candidateWindowCount, triedWindows, chosenRank);
  }

  private InsertionResult findEmptyScheduleInsertion(
    Task task,
    List<TimeWindow> windows,
    int taskDuration,
    WindowPreferenceStats stats,
    int candidateWindowCount,
    ReusableInsertionResult reusableResult
  ) {
    int triedWindows = 0;
    int chosenRank = -1;
    for (int windowIndex = 0; windowIndex < windows.size(); windowIndex++) {
      TimeWindow window = windows.get(windowIndex);
      triedWindows++;
      if (!window.canAccommodateTask(taskDuration)) {
        continue;
      }
      AttitudeRecord candidateStart = window.getAttitudeRecord(1);
      AttitudeRecord candidateEnd = window.getAttitudeRecord(1 + taskDuration);
      ScheduledTask newTask = createScheduledTask(
        task,
        window,
        candidateStart,
        candidateEnd,
        reusableResult
      );
      chosenRank = triedWindows;
      recordWindowPreferenceStats(stats, candidateWindowCount, triedWindows, chosenRank);
      if (reusableResult != null) {
        reusableResult.set(newTask, 0);
        return null;
      }
      return new InsertionResult(newTask, 0);
    }

    recordWindowPreferenceStats(stats, candidateWindowCount, triedWindows, chosenRank);
    return null;
  }

  private ScheduledTask createScheduledTask(
    Task task,
    TimeWindow window,
    AttitudeRecord candidateStart,
    AttitudeRecord candidateEnd,
    ReusableInsertionResult reusableResult
  ) {
    if (reusableResult != null && reusableResult.taskProvider != null) {
      return reusableResult.taskProvider.provide(task, window, candidateStart, candidateEnd);
    }
    ScheduledTask newTask = new ScheduledTask(task);
    newTask.setSchedule(window, candidateStart, candidateEnd);
    return newTask;
  }

  private double recordTime(TimeWindow window, double[] recordTimes, int index) {
    return recordTimes != null ? recordTimes[index] : window.getAttitudeRecordTimeSeconds(index);
  }

  private int findStartIndexAtOrAfter(
    TimeWindow window,
    double[] recordTimes,
    int low,
    int high,
    double targetTime
  ) {
    int estimated = estimateStartIndex(window, recordTimes, low, high, targetTime);
    if (estimated >= 0) {
      int corrected = findStartIndexAtOrAfterNear(window, recordTimes, low, high, targetTime, estimated);
      if (corrected >= 0) {
        return corrected;
      }
    }
    return findStartIndexAtOrAfterBinary(window, recordTimes, low, high, targetTime);
  }

  private int findStartIndexAfter(
    TimeWindow window,
    double[] recordTimes,
    int low,
    int high,
    double targetTime
  ) {
    int estimated = estimateStartIndex(window, recordTimes, low, high, targetTime);
    if (estimated >= 0) {
      int corrected = findStartIndexAfterNear(window, recordTimes, low, high, targetTime, estimated);
      if (corrected >= 0) {
        return corrected;
      }
    }
    return findStartIndexAfterBinary(window, recordTimes, low, high, targetTime);
  }

  private int estimateStartIndex(
    TimeWindow window,
    double[] recordTimes,
    int low,
    int high,
    double targetTime
  ) {
    double lowTime = recordTime(window, recordTimes, low);
    if (targetTime <= lowTime) {
      return low;
    }
    double highTime = recordTime(window, recordTimes, high);
    if (targetTime > highTime) {
      return -1;
    }
    int estimated = low + (int) Math.floor(targetTime - lowTime);
    if (estimated < low) {
      estimated = low;
    } else if (estimated > high) {
      estimated = high;
    }
    return estimated;
  }

  private int findStartIndexAtOrAfterNear(
    TimeWindow window,
    double[] recordTimes,
    int low,
    int high,
    double targetTime,
    int index
  ) {
    int steps = 0;
    while (index > low && recordTime(window, recordTimes, index - 1) >= targetTime) {
      index--;
      if (++steps > START_INDEX_ESTIMATE_SCAN_LIMIT) {
        return -1;
      }
    }
    steps = 0;
    while (index <= high && recordTime(window, recordTimes, index) < targetTime) {
      index++;
      if (++steps > START_INDEX_ESTIMATE_SCAN_LIMIT) {
        return -1;
      }
    }
    return index <= high ? index : -1;
  }

  private int findStartIndexAfterNear(
    TimeWindow window,
    double[] recordTimes,
    int low,
    int high,
    double targetTime,
    int index
  ) {
    int steps = 0;
    while (index > low && recordTime(window, recordTimes, index - 1) > targetTime) {
      index--;
      if (++steps > START_INDEX_ESTIMATE_SCAN_LIMIT) {
        return -1;
      }
    }
    steps = 0;
    while (index <= high && recordTime(window, recordTimes, index) <= targetTime) {
      index++;
      if (++steps > START_INDEX_ESTIMATE_SCAN_LIMIT) {
        return -1;
      }
    }
    return index <= high ? index : -1;
  }

  private int findStartIndexAtOrAfterBinary(
    TimeWindow window,
    double[] recordTimes,
    int low,
    int high,
    double targetTime
  ) {
    int result = -1;
    while (low <= high) {
      int mid = (low + high) >>> 1;
      double midTime = recordTime(window, recordTimes, mid);
      if (midTime >= targetTime) {
        result = mid;
        high = mid - 1;
      } else {
        low = mid + 1;
      }
    }
    return result;
  }

  private int findStartIndexAfterBinary(
    TimeWindow window,
    double[] recordTimes,
    int low,
    int high,
    double targetTime
  ) {
    int result = -1;
    while (low <= high) {
      int mid = (low + high) >>> 1;
      double midTime = recordTime(window, recordTimes, mid);
      if (midTime > targetTime) {
        result = mid;
        high = mid - 1;
      } else {
        low = mid + 1;
      }
    }
    return result;
  }

}
