package model;

/**
 * Attitude Record
 * 
 * Represents a single attitude angle measurement at a specific time point.
 * 
 */
public class AttitudeRecord {

  // Time conversion constants
  private static final int SECONDS_PER_HOUR = 3600;
  private static final int SECONDS_PER_MINUTE = 60;

  private double rollAngle;
  private double pitchAngle;
  private double totalSeconds;

  /**
   * Constructor from raw data (8 columns)
   * 
   * @param year Year (not used in calculation, kept for interface consistency)
   * @param month Month (not used in calculation, kept for interface consistency)
   * @param day Day (not used in calculation, kept for interface consistency)
   * @param hour Hour of day (0-23)
   * @param minute Minute of hour (0-59)
   * @param second Second of minute (0-59.999...)
   * @param roll Roll angle in degrees
   * @param pitch Pitch angle in degrees
   */
  public AttitudeRecord(
    int year, int month, int day,
    int hour, int minute, double second,
    double roll, double pitch
  ) {
    this.rollAngle = roll;
    this.pitchAngle = pitch;
    this.totalSeconds = hour * SECONDS_PER_HOUR + minute * SECONDS_PER_MINUTE + second;
  }

  // Getters
  public double getRollAngle() {
    return rollAngle;
  }

  public double getPitchAngle() {
    return pitchAngle;
  }

  public double getTotalSeconds() {
    return totalSeconds;
  }
}