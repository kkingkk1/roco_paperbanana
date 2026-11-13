package method.rkhma.legacy.model;

/**
 * Scheduled Task
 * 
 * Represents a task that has been assigned to a satellite and time window.
 */
public class ScheduledTask {

  private static final double DEFAULT_TIME = 0.0;

  private Task task;
  private int satelliteId;
  private TimeWindow timeWindow;
  private AttitudeRecord startRecord;
  private AttitudeRecord endRecord;

  /**
   * Constructor from Task
   * 
   * @param task Task to be scheduled (must not be null)
   * @throws IllegalArgumentException if task is null
   */
  public ScheduledTask(Task task) {
    if (task == null) {
      throw new IllegalArgumentException("Task cannot be null");
    }
    this.task = task;
  }

  /**
   * Reset this instance for workspace-owned reuse.
   *
   * @param task Task to be scheduled (must not be null)
   * @param timeWindow Time window for the task
   * @param startRecord Start attitude record
   * @param endRecord End attitude record
   */
  public void resetSchedule(Task task, TimeWindow timeWindow,
    AttitudeRecord startRecord, AttitudeRecord endRecord) {
    if (task == null) {
      throw new IllegalArgumentException("Task cannot be null");
    }
    this.task = task;
    setSchedule(timeWindow, startRecord, endRecord);
  }

  /**
   * Set scheduling assignment
   * 
   * @param timeWindow Time window for the task
   * @param startRecord Start attitude record
   * @param endRecord End attitude record
   */
  public void setSchedule(TimeWindow timeWindow, 
    AttitudeRecord startRecord, AttitudeRecord endRecord) {
    this.timeWindow = timeWindow;
    this.startRecord = startRecord;
    this.endRecord = endRecord;
    this.satelliteId = timeWindow.getSatelliteId();
  }

  /**
   * Set scheduling assignment with satellite ID
   * 
   * @param timeWindow Time window for the task
   * @param startRecord Start attitude record
   * @param endRecord End attitude record
   * @param satelliteId Satellite identifier
   */
  public void setSchedule(TimeWindow timeWindow, 
    AttitudeRecord startRecord, AttitudeRecord endRecord,
    int satelliteId) {
    this.timeWindow = timeWindow;
    this.startRecord = startRecord;
    this.endRecord = endRecord;
    this.satelliteId = satelliteId;
  }

  /**
   * Get start time in seconds from day start
   * 
   * @return Start time in seconds, or 0.0 if not scheduled
   */
  public double getStartTime() {
    return startRecord != null ? startRecord.getTotalSeconds() : DEFAULT_TIME;
  }

  /**
   * Get end time in seconds from day start
   * 
   * @return End time in seconds, or 0.0 if not scheduled
   */
  public double getEndTime() {
    return endRecord != null ? endRecord.getTotalSeconds() : DEFAULT_TIME;
  }

  /**
   * Get task duration in seconds
   * 
   * @return Duration in seconds, or 0.0 if not scheduled
   */
  public double getDuration() {
    if (startRecord != null && endRecord != null) {
      return endRecord.getTotalSeconds() - startRecord.getTotalSeconds();
    }
    return DEFAULT_TIME;
  }

  /**
   * Get task priority
   * 
   * @return Task priority
   */
  public int getPriority() {
    return task.getPriority();
  }

  /**
   * Get task ID
   * 
   * @return Task identifier
   */
  public int getTaskId() {
    return task.getId();
  }

  /**
   * Check if scheduled
   * 
   * @return true if both start and end records are set, false otherwise
   */
  public boolean isScheduled() {
    return startRecord != null && endRecord != null;
  }

  // Getters
  /**
   * Get satellite ID
   * 
   * @return Satellite identifier
   */
  public int getSatelliteId() {
    return satelliteId;
  }

  /**
   * Get the task object
   * 
   * @return Task object
   */
  public Task getTask() {
    return task;
  }

  /**
   * Get time window
   * 
   * @return Time window, or null if not set
   */
  public TimeWindow getTimeWindow() {
    return timeWindow;
  }

  /**
   * Get start attitude record
   * 
   * @return Start attitude record, or null if not set
   */
  public AttitudeRecord getStartRecord() {
    return startRecord;
  }

  /**
   * Get end attitude record
   * 
   * @return End attitude record, or null if not set
   */
  public AttitudeRecord getEndRecord() {
    return endRecord;
  }

  // Setters
  /**
   * Set satellite ID
   * 
   * @param satelliteId Satellite identifier
   */
  public void setSatelliteId(int satelliteId) {
    this.satelliteId = satelliteId;
  }

  /**
   * Set time window
   * 
   * @param timeWindow Time window
   */
  public void setTimeWindow(TimeWindow timeWindow) {
    this.timeWindow = timeWindow;
  }

  /**
   * Set start attitude record
   * 
   * @param startRecord Start attitude record
   */
  public void setStartRecord(AttitudeRecord startRecord) {
    this.startRecord = startRecord;
  }

  /**
   * Set end attitude record
   * 
   * @param endRecord End attitude record
   */
  public void setEndRecord(AttitudeRecord endRecord) {
    this.endRecord = endRecord;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    ScheduledTask that = (ScheduledTask) o;
    // Core logic: compare internal Task IDs
    return this.getTaskId() == that.getTaskId();
  }

  /**
   * Override hashCode.
   * Must be consistent with equals, using Task ID to generate hash code.
   */
  @Override
  public int hashCode() {
    // Use Task ID as hash basis
    return Integer.hashCode(getTaskId());
  }

  /**
   * Override toString for convenient debugging output.
   */
  @Override
  public String toString() {
    return String.format("ScheduledTask{id=%d, sat=%d, start=%.3f, end=%.3f, scheduled=%s}",
      getTaskId(), satelliteId, getStartTime(), getEndTime(), isScheduled());
  }
}
