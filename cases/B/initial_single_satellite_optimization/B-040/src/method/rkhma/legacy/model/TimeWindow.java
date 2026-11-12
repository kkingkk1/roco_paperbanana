package method.rkhma.legacy.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Time Window data structure
 * 
 * Represents a time window with start and end time points.
 * Contains attitude records for the time window.
 */
public class TimeWindow {

  // Time conversion constants
  private static final int SECONDS_PER_HOUR = 3600;
  private static final int SECONDS_PER_MINUTE = 60;

  private double startTime;  // Start time in seconds from day start
  private double endTime;     // End time in seconds from day start
  private int satelliteId;    // Satellite ID associated with this window
  private int taskId;         // Task ID associated with this window
  private List<AttitudeRecord> attitudeRecords;
  private volatile double[] attitudeRecordTimes;
  private int validatedDuration = -1;
  private boolean hasValidatedCandidate;

  public void setValidatedCandidate(int duration, boolean available) {
    validatedDuration = duration;
    hasValidatedCandidate = available;
  }

  /**
   * Constructor with raw data (12 columns)
   * 
   * @param satelliteId Satellite identifier
   * @param taskId Task identifier
   * @param startYear Start year (not used in calculation, kept for interface consistency)
   * @param startMonth Start month (not used in calculation, kept for interface consistency)
   * @param startDay Start day (not used in calculation, kept for interface consistency)
   * @param startHour Start hour (0-23)
   * @param startMin Start minute (0-59)
   * @param startSec Start second (0-59.999...)
   * @param endYear End year (not used in calculation, kept for interface consistency)
   * @param endMonth End month (not used in calculation, kept for interface consistency)
   * @param endDay End day (not used in calculation, kept for interface consistency)
   * @param endHour End hour (0-23)
   * @param endMin End minute (0-59)
   * @param endSec End second (0-59.999...)
   */
  public TimeWindow(
    int satelliteId, int taskId, 
    int startYear, int startMonth, int startDay, int startHour, int startMin, double startSec,
    int endYear, int endMonth, int endDay, int endHour, int endMin, double endSec
  ) {
    this.satelliteId = satelliteId;
    this.taskId = taskId;
    this.startTime = startHour * SECONDS_PER_HOUR + startMin * SECONDS_PER_MINUTE + startSec;
    this.endTime = endHour * SECONDS_PER_HOUR + endMin * SECONDS_PER_MINUTE + endSec;
    this.attitudeRecords = new ArrayList<>();
  }

  // Getters
  /**
   * Get start time
   * 
   * @return Start time in seconds from day start
   */
  public double getStartTime() {
    return startTime;
  }

  /**
   * Get end time
   * 
   * @return End time in seconds from day start
   */
  public double getEndTime() {
    return endTime;
  }

  /**
   * Get duration
   * 
   * @return Duration in seconds
   */
  public double getDuration() {
    return endTime - startTime;
  }

  /**
   * Get satellite ID
   * 
   * @return Satellite identifier
   */
  public int getSatelliteId() {
    return satelliteId;
  }

  /**
   * Get task ID
   * 
   * @return Task identifier
   */
  public int getTaskId() {
    return taskId;
  }

  /**
   * Get attitude records
   * Returns an unmodifiable view of the attitude records list
   * 
   * @return Unmodifiable list of attitude records
   */
  public List<AttitudeRecord> getAttitudeRecords() {
    return Collections.unmodifiableList(attitudeRecords);
  }

  /**
   * Get number of attitude records
   * 
   * @return Number of attitude records
   */
  public int getAttitudeRecordCount() {
    return attitudeRecords.size();
  }

  /**
   * 返回记录顺序不变的秒数缓存，仅供内部只读扫描。调用方不得修改数组。
   * 加载后的窗口支持并行只读；与记录列表一样，不支持并发修改。
   * 窗口子类、记录子类或 null 记录返回 null，调用方须沿用原对象读取。
   */
  public double[] getAttitudeRecordTimesUnsafe() {
    if (getClass() != TimeWindow.class) {
      return null;
    }
    double[] times = attitudeRecordTimes;
    return times != null ? times : buildAttitudeRecordTimes();
  }

  private synchronized double[] buildAttitudeRecordTimes() {
    if (attitudeRecordTimes != null) {
      return attitudeRecordTimes;
    }
    int size = attitudeRecords.size();
    for (int i = 0; i < size; i++) {
      AttitudeRecord record = attitudeRecords.get(i);
      if (record == null || record.getClass() != AttitudeRecord.class) {
        return null;
      }
    }
    double[] times = new double[size];
    for (int i = 0; i < size; i++) {
      times[i] = attitudeRecords.get(i).getTotalSeconds();
    }
    attitudeRecordTimes = times;
    return times;
  }

  /**
   * Check if attitude records are empty
   * 
   * @return true if there are no attitude records, false otherwise
   */
  public boolean hasAttitudeRecords() {
    return !attitudeRecords.isEmpty();
  }

  // Setters
  /**
   * Set start time
   * 
   * @param startTime Start time in seconds from day start
   * @throws IllegalArgumentException if startTime is negative or greater than endTime
   */
  public void setStartTime(double startTime) {
    if (startTime < 0) {
      throw new IllegalArgumentException("Start time cannot be negative");
    }
    if (startTime > endTime) {
      throw new IllegalArgumentException("Start time cannot be greater than end time");
    }
    this.startTime = startTime;
  }

  /**
   * Set end time
   * 
   * @param endTime End time in seconds from day start
   * @throws IllegalArgumentException if endTime is negative or less than startTime
   */
  public void setEndTime(double endTime) {
    if (endTime < 0) {
      throw new IllegalArgumentException("End time cannot be negative");
    }
    if (endTime < startTime) {
      throw new IllegalArgumentException("End time cannot be less than start time");
    }
    this.endTime = endTime;
  }

  /**
   * Set satellite ID
   * 
   * @param satelliteId Satellite identifier
   */
  public void setSatelliteId(int satelliteId) {
    this.satelliteId = satelliteId;
  }

  /**
   * Set task ID
   * 
   * @param taskId Task identifier
   */
  public void setTaskId(int taskId) {
    this.taskId = taskId;
  }

  /**
   * Set attitude records
   * 
   * @param attitudeRecords List of attitude records (defensive copy is made)
   */
  public void setAttitudeRecords(List<AttitudeRecord> attitudeRecords) {
    this.attitudeRecords = attitudeRecords != null ? new ArrayList<>(attitudeRecords) : new ArrayList<>();
    this.attitudeRecordTimes = null;
  }

  /**
   * Add an attitude record
   * 
   * @param record Attitude record to add (must not be null)
   * @throws IllegalArgumentException if record is null
   */
  public void addAttitudeRecord(AttitudeRecord record) {
    if (record == null) {
      throw new IllegalArgumentException("Attitude record cannot be null");
    }
    this.attitudeRecords.add(record);
    this.attitudeRecordTimes = null;
  }

  /**
   * Add multiple attitude records
   * 
   * @param records List of attitude records to add
   */
  public void addAttitudeRecords(List<AttitudeRecord> records) {
    if (records != null) {
      this.attitudeRecords.addAll(records);
      this.attitudeRecordTimes = null;
    }
  }

  /**
   * Clear all attitude records
   */
  public void clearAttitudeRecords() {
    this.attitudeRecords.clear();
    this.attitudeRecordTimes = null;
  }

  /**
   * Get attitude record at index
   * 
   * @param index Index of the record
   * @return Attitude record at the specified index
   * @throws IndexOutOfBoundsException if index is out of range
   */
  public AttitudeRecord getAttitudeRecord(int index) {
    return attitudeRecords.get(index);
  }

  /**
   * Get attitude record time at index.
   *
   * @param index Index of the record
   * @return Total seconds of the attitude record
   * @throws IndexOutOfBoundsException if index is out of range
   */
  public double getAttitudeRecordTimeSeconds(int index) {
    return attitudeRecords.get(index).getTotalSeconds();
  }

  /**
   * Check if this window overlaps with another window
   * 
   * @param other Other time window (must not be null)
   * @return true if windows overlap, false otherwise
   * @throws IllegalArgumentException if other is null
   */
  public boolean overlaps(TimeWindow other) {
    if (other == null) {
      throw new IllegalArgumentException("Other time window cannot be null");
    }
    return this.startTime < other.endTime && this.endTime > other.startTime;
  }

  /**
   * Check if a time point is within this window
   * 
   * @param time Time point in seconds from day start
   * @return true if time is within the window, false otherwise
   */
  public boolean contains(double time) {
    return time >= startTime && time <= endTime;
  }

  /**
   * Check if this window can accommodate a duration starting at given time
   * 
   * @param startTime Start time in seconds from day start
   * @param duration Duration in seconds
   * @return true if the window can accommodate the duration, false otherwise
   */
  public boolean canAccommodate(double startTime, double duration) {
    if (duration < 0) {
      return false;
    }
    return startTime >= this.startTime && (startTime + duration) <= this.endTime;
  }

  /**
   * Check if this window can accommodate a task with given duration
   * 
   * @param taskDuration Task duration in seconds
   * @return true if the window can accommodate the task, false otherwise
   */
  public boolean canAccommodateTask(int taskDuration) {
    if (taskDuration < 0) {
      return false;
    }
    if (taskDuration == validatedDuration) return hasValidatedCandidate;
    // Check if there are enough attitude records (taskDuration + 2 for start and end)
    return attitudeRecords.size() > taskDuration + 2;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    TimeWindow that = (TimeWindow) o;
    return Double.compare(that.startTime, startTime) == 0 &&
    Double.compare(that.endTime, endTime) == 0 &&
    satelliteId == that.satelliteId &&
    taskId == that.taskId;
  }

  @Override
  public int hashCode() {
    return Objects.hash(startTime, endTime, satelliteId, taskId);
  }

  @Override
  public String toString() {
    return String.format("TimeWindow{sat=%d, task=%d, start=%.3f, end=%.3f, duration=%.3f, records=%d}",
      satelliteId, taskId, startTime, endTime, getDuration(), attitudeRecords.size());
  }
}
