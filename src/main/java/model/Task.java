package model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Task entity
 * Corresponds to one line in tasklist.txt
 * 
 * Represents an imaging task with location, priority, and time windows.
 */
public class Task {

  private int id;
  private double longitude;  // Longitude coordinate (not used in current implementation but preserved for future use)
  private double latitude;   // Latitude coordinate (not used in current implementation but preserved for future use)
  private int priority;
  private int imagingDuration;
  private List<List<TimeWindow>> timeWindows;
  private List<List<TimeWindow>> unmodifiableTimeWindows;
  /** 预缓存的不可变视图，避免 getTimeWindowsForSatellite 每次 new UnmodifiableList */
  private List<List<TimeWindow>> unmodifiableViews;
  private int totalTimeWindowCount;

  /**
   * Constructor
   * 
   * @param id Task identifier
   * @param lon Longitude coordinate (preserved for future use)
   * @param lat Latitude coordinate (preserved for future use)
   * @param priority Task priority (higher is better)
   * @param duration Imaging duration in seconds
   */
  public Task(int id, double lon, double lat, int priority, int duration) {
    this.id = id;
    this.longitude = lon;
    this.latitude = lat;
    this.priority = priority;
    this.imagingDuration = duration;
    this.timeWindows = new ArrayList<>();
    this.unmodifiableTimeWindows = Collections.unmodifiableList(timeWindows);
    this.unmodifiableViews = new ArrayList<>();
  }

  // Getters
  /**
   * Get task ID
   * 
   * @return Task identifier
   */
  public int getId() {
    return id;
  }

  /**
   * Get longitude coordinate
   * 
   * @return Longitude
   */
  public double getLongitude() {
    return longitude;
  }

  /**
   * Get latitude coordinate
   * 
   * @return Latitude
   */
  public double getLatitude() {
    return latitude;
  }

  /**
   * Get task priority
   * 
   * @return Task priority (higher is better)
   */
  public int getPriority() {
    return priority;
  }

  /**
   * Get imaging duration
   * 
   * @return Imaging duration in seconds
   */
  public int getImagingDuration() {
    return imagingDuration;
  }

  /**
   * Get time windows for all satellites
   * Returns an unmodifiable view of the time windows list
   * 
   * @return Unmodifiable list of time window lists (one per satellite)
   */
  public List<List<TimeWindow>> getTimeWindows() {
    return unmodifiableTimeWindows;
  }

  /**
   * Get time windows for a specific satellite
   * 
   * @param satelliteId Satellite identifier (0-based index)
   * @return List of time windows for the satellite, or empty list if not found
   */
  public List<TimeWindow> getTimeWindowsForSatellite(int satelliteId) {
    if (satelliteId < 0 || satelliteId >= unmodifiableViews.size()) {
      return Collections.emptyList();
    }
    return unmodifiableViews.get(satelliteId);
  }

  /**
   * Get the total number of time windows across all satellites.
   *
   * @return Total time window count
   */
  public int getTotalTimeWindowCount() {
    return totalTimeWindowCount;
  }

  // Setters
  /**
   * Set task ID
   * 
   * @param id Task identifier
   */
  public void setId(int id) {
    this.id = id;
  }

  /**
   * Set longitude coordinate
   * 
   * @param longitude Longitude
   */
  public void setLongitude(double longitude) {
    this.longitude = longitude;
  }

  /**
   * Set latitude coordinate
   * 
   * @param latitude Latitude
   */
  public void setLatitude(double latitude) {
    this.latitude = latitude;
  }

  /**
   * Set task priority
   * 
   * @param priority Task priority (higher is better)
   */
  public void setPriority(int priority) {
    this.priority = priority;
  }

  /**
   * Set imaging duration
   * 
   * @param duration Imaging duration in seconds
   * @throws IllegalArgumentException if duration is negative
   */
  public void setImagingDuration(int duration) {
    if (duration < 0) {
      throw new IllegalArgumentException("Imaging duration cannot be negative");
    }
    this.imagingDuration = duration;
  }

  /**
   * Initialize time windows list for a given number of satellites
   * 
   * @param numSatellites Number of satellites
   */
  public void initializeTimeWindows(int numSatellites) {
    if (numSatellites < 0) {
      throw new IllegalArgumentException("Number of satellites cannot be negative");
    }
    this.timeWindows.clear();
    this.unmodifiableViews.clear();
    this.totalTimeWindowCount = 0;
    for (int i = 0; i < numSatellites; i++) {
      ArrayList<TimeWindow> list = new ArrayList<>();
      this.timeWindows.add(list);
      this.unmodifiableViews.add(Collections.unmodifiableList(list));
    }
  }

  /**
   * Clear all time windows
   */
  public void clearTimeWindows() {
    this.timeWindows.clear();
    this.unmodifiableViews.clear();
    this.totalTimeWindowCount = 0;
  }

  /**
   * Add time windows for a specific satellite
   * 
   * @param satelliteId Satellite identifier (0-based index)
   * @param windows List of time windows to add
   */
  public void addTimeWindowsForSatellite(int satelliteId, List<TimeWindow> windows) {
    if (satelliteId < 0) {
      throw new IllegalArgumentException("Satellite ID cannot be negative");
    }
    if (windows == null) {
      return;
    }

    // Ensure the list is large enough
    while (timeWindows.size() <= satelliteId) {
      ArrayList<TimeWindow> list = new ArrayList<>();
      timeWindows.add(list);
      unmodifiableViews.add(Collections.unmodifiableList(list));
    }

    timeWindows.get(satelliteId).addAll(windows);
    totalTimeWindowCount += windows.size();
  }


  /**
   * Check if task has time windows for any satellite
   * 
   * @return true if there are any time windows, false otherwise
   */
  public boolean hasTimeWindows() {
    return !timeWindows.isEmpty() && 
    timeWindows.stream().anyMatch(windows -> windows != null && !windows.isEmpty());
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    Task task = (Task) o;
    return id == task.id;
  }

  @Override
  public int hashCode() {
    return Objects.hash(id);
  }

  @Override
  public String toString() {
    return String.format("Task{id=%d, priority=%d, duration=%d, satellites=%d, hasWindows=%s}",
      id, priority, imagingDuration, timeWindows.size(), hasTimeWindows());
  }
}
