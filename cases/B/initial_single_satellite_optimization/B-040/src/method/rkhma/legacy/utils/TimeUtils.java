package method.rkhma.legacy.utils;

import method.rkhma.legacy.model.AttitudeRecord;

/**
 * Time calculation utilities for satellite attitude transformations
 *
 * Core functions:
 * 1. Time comparison between attitude records
 * 2. Attitude transformation time calculation based on angle differences
 * 3. Time interval calculation
 * 4. Transformation feasibility checking
 *
 * Time units: seconds
 * Angle units: degrees
 */
public class TimeUtils {

  // Angle thresholds for piecewise transformation time calculation (degrees)
  private static final double ANGLE_THRESHOLD_10_DEG = 10.0;
  private static final double ANGLE_THRESHOLD_30_DEG = 30.0;
  private static final double ANGLE_THRESHOLD_60_DEG = 60.0;
  private static final double ANGLE_THRESHOLD_90_DEG = 90.0;

  // Transformation time parameters for angle <= 10 degrees
  /** Minimum transformation time for small angles (≤10°) in seconds */
  public static final double MIN_TRANSFORMATION_TIME_SECONDS = 11.66;

  // Transformation time parameters for 10° < angle <= 30°
  /** Divisor for angle range (10°, 30°]: time = angle / divisor + offset */
  private static final double ANGLE_10_30_DIVISOR = 1.5;
  private static final double ANGLE_10_30_OFFSET_SECONDS = 5.0;

  // Transformation time parameters for 30° < angle <= 60°
  /** Divisor for angle range (30°, 60°]: time = angle / divisor + offset */
  private static final double ANGLE_30_60_DIVISOR = 2.0;
  private static final double ANGLE_30_60_OFFSET_SECONDS = 10.0;

  // Transformation time parameters for 60° < angle <= 90°
  /** Divisor for angle range (60°, 90°]: time = angle / divisor + offset */
  private static final double ANGLE_60_90_DIVISOR = 2.5;
  private static final double ANGLE_60_90_OFFSET_SECONDS = 16.0;

  // Transformation time parameters for angle > 90°
  /** Divisor for angle range (90°, +∞): time = angle / divisor + offset */
  private static final double ANGLE_ABOVE_90_DIVISOR = 3.0;
  private static final double ANGLE_ABOVE_90_OFFSET_SECONDS = 22.0;

  /**
   * Check if t1 is earlier than t2
   *
   * @param t1 first attitude record (must not be null)
   * @param t2 second attitude record (must not be null)
   * @return true if t1 occurs before t2
   * @throws NullPointerException if t1 or t2 is null
   */
  public static boolean isEarlier(AttitudeRecord t1, AttitudeRecord t2) {
    if (t1 == null || t2 == null) {
      throw new NullPointerException("AttitudeRecord parameters cannot be null");
    }
    return t1.getTotalSeconds() < t2.getTotalSeconds();
  }

  /**
   * Calculate attitude transformation time based on angle difference
   *
   * Uses a piecewise linear function based on total angle change:
   * - angle ≤ 10°:  11.66 seconds (constant minimum time)
   * - 10° < angle ≤ 30°:  time = angle / 1.5 + 5
   * - 30° < angle ≤ 60°:  time = angle / 2.0 + 10
   * - 60° < angle ≤ 90°:  time = angle / 2.5 + 16
   * - angle > 90°:  time = angle / 3.0 + 22
   *
   * @param t1 starting attitude record (must not be null)
   * @param t2 target attitude record (must not be null)
   * @return transformation time in seconds
   * @throws NullPointerException if t1 or t2 is null
   */
  public static double calculateTransformationTime(AttitudeRecord t1, AttitudeRecord t2) {
    if (t1 == null || t2 == null) {
      throw new NullPointerException("AttitudeRecord parameters cannot be null");
    }

    double transformationAngle = getTransformationAngle(t1, t2);
    double transformationTime;

    if (transformationAngle <= ANGLE_THRESHOLD_10_DEG) {
      transformationTime = MIN_TRANSFORMATION_TIME_SECONDS;
    } else if (transformationAngle <= ANGLE_THRESHOLD_30_DEG) {
      transformationTime = transformationAngle / ANGLE_10_30_DIVISOR + ANGLE_10_30_OFFSET_SECONDS;
    } else if (transformationAngle <= ANGLE_THRESHOLD_60_DEG) {
      transformationTime = transformationAngle / ANGLE_30_60_DIVISOR + ANGLE_30_60_OFFSET_SECONDS;
    } else if (transformationAngle <= ANGLE_THRESHOLD_90_DEG) {
      transformationTime = transformationAngle / ANGLE_60_90_DIVISOR + ANGLE_60_90_OFFSET_SECONDS;
    } else {
      transformationTime = transformationAngle / ANGLE_ABOVE_90_DIVISOR + ANGLE_ABOVE_90_OFFSET_SECONDS;
    }

    return transformationTime;
  }

  /**
   * Calculate time interval between two attitude records
   *
   * @param t1 earlier attitude record (must not be null)
   * @param t2 later attitude record (must not be null)
   * @return time interval in seconds (t2 - t1)
   * @throws NullPointerException if t1 or t2 is null
   */
  public static double calculateTimeInterval(AttitudeRecord t1, AttitudeRecord t2) {
    if (t1 == null || t2 == null) {
      throw new NullPointerException("AttitudeRecord parameters cannot be null");
    }
    return t2.getTotalSeconds() - t1.getTotalSeconds();
  }

  /**
   * Check if attitude transformation is feasible between two records
   *
   * A transformation is feasible if:
   * 1. endRecord occurs before nextStartRecord (temporal ordering)
   * 2. Time interval between records is sufficient for the transformation
   *
   * @param endRecord ending attitude of current observation (must not be null)
   * @param nextStartRecord starting attitude of next observation (must not be null)
   * @return true if transformation is feasible, false otherwise
   * @throws NullPointerException if endRecord or nextStartRecord is null
   */
  public static boolean checkTransformationTime(
    AttitudeRecord endRecord,
    AttitudeRecord nextStartRecord
  ) {
    if (endRecord == null || nextStartRecord == null) {
      throw new NullPointerException("AttitudeRecord parameters cannot be null");
    }

    if (!isEarlier(endRecord, nextStartRecord)) {
      return false;
    }

    double transformTime = calculateTransformationTime(endRecord, nextStartRecord);
    double timeInterval = calculateTimeInterval(endRecord, nextStartRecord);

    return transformTime <= timeInterval;
  }

  /**
   * Calculate total transformation angle between two attitude records
   *
   * Uses Manhattan distance (sum of absolute differences) for roll and pitch angles.
   * This is the appropriate metric for satellite attitude transformations where
   * roll and pitch adjustments are independent.
   *
   * @param t1 starting attitude record (must not be null)
   * @param t2 target attitude record (must not be null)
   * @return total transformation angle in degrees
   * @throws NullPointerException if t1 or t2 is null
   */
  public static double getTransformationAngle(AttitudeRecord t1, AttitudeRecord t2) {
    if (t1 == null || t2 == null) {
      throw new NullPointerException("AttitudeRecord parameters cannot be null");
    }
    double rollDiff = Math.abs(t1.getRollAngle() - t2.getRollAngle());
    double pitchDiff = Math.abs(t1.getPitchAngle() - t2.getPitchAngle());
    return rollDiff + pitchDiff;
  }
}
