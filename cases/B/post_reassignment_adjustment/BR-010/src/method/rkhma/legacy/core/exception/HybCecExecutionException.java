package method.rkhma.legacy.core.exception;

/**
 * Custom exception for HybCec execution errors.
 *
 * This exception is thrown when critical errors occur during HybCec algorithm execution,
 * such as:
 * - No individuals being updated in an iteration
 * - Invalid configuration or state
 * - Strategy execution failures
 * - Population management errors
 */
public class HybCecExecutionException extends Exception {

  private static final long serialVersionUID = 1L;

  /**
   * Constructs a new HybCecExecutionException with the specified detail message.
   *
   * @param message the detail message
   */
  public HybCecExecutionException(String message) {
    super(message);
  }

  /**
   * Constructs a new HybCecExecutionException with the specified detail message and cause.
   *
   * @param message the detail message
   * @param cause the cause of the exception
   */
  public HybCecExecutionException(String message, Throwable cause) {
    super(message, cause);
  }

  /**
   * Constructs a new HybCecExecutionException with the specified cause.
   *
   * @param cause the cause of the exception
   */
  public HybCecExecutionException(Throwable cause) {
    super(cause);
  }
}
