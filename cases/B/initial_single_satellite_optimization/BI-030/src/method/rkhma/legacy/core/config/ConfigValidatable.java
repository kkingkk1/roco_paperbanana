package method.rkhma.legacy.core.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Configuration Verification Interface
 * All algorithm configuration classes should implement this interface 
 * to provide parameter validation functionality.
 */
public interface ConfigValidatable {

  /**
   * Validate the configuration parameters.
   */
  default ValidationResult validate() {
    return ValidationResult.success();
  }

  /**
   * Create a new ValidationResultBuilder for building complex validation results.
   */
  static ValidationResultBuilder builder() {
    return new ValidationResultBuilder();
  }

  /**
   * Validation result class
   */
  class ValidationResult {
    private final boolean valid;
    private final List<String> errors;
    private final List<String> warnings;

    private ValidationResult(boolean valid, List<String> errors, List<String> warnings) {
      this.valid = valid;
      this.errors = new ArrayList<>(errors);
      this.warnings = new ArrayList<>(warnings);
    }

    /**
     * Create a validation result indicating success.
     */
    public static ValidationResult success() {
      return new ValidationResult(true, Collections.emptyList(), Collections.emptyList());
    }

    /**
     * Create a validation result indicating success with warnings.
     */
    public static ValidationResult successWithWarnings(List<String> warnings) {
      Objects.requireNonNull(warnings, "warnings cannot be null");
      return new ValidationResult(true, Collections.emptyList(), warnings);
    }

    /**
     * Create a validation result indicating failure.
     */
    public static ValidationResult failure(List<String> errors) {
      Objects.requireNonNull(errors, "errors cannot be null");
      return new ValidationResult(false, errors, Collections.emptyList());
    }

    /**
     * Create a validation result containing both errors and warnings.
     */
    public static ValidationResult withErrorsAndWarnings(List<String> errors, List<String> warnings) {
      Objects.requireNonNull(errors, "errors cannot be null");
      Objects.requireNonNull(warnings, "warnings cannot be null");
      return new ValidationResult(errors.isEmpty(), errors, warnings);
    }

    /**
     * Create a validation result with a single error.
     */
    public static ValidationResult failure(String error) {
      Objects.requireNonNull(error, "error cannot be null");
      return new ValidationResult(false, Collections.singletonList(error), Collections.emptyList());
    }

    /**
     * Create a validation result indicating success with a single warning.
     */
    public static ValidationResult successWithWarning(String warning) {
      Objects.requireNonNull(warning, "warning cannot be null");
      return new ValidationResult(true, Collections.emptyList(), Collections.singletonList(warning));
    }

    /**
     * Check if the validation is successful.
     */
    public boolean isValid() {
      return valid;
    }

    /**
     * Check if there are any errors.
     */
    public boolean hasErrors() {
      return !errors.isEmpty();
    }

    /**
     * Check if there are any warnings.
     */
    public boolean hasWarnings() {
      return !warnings.isEmpty();
    }

    /**
     * Get the list of errors.
     */
    public List<String> getErrors() {
      return new ArrayList<>(errors);
    }

    /**
     * Get the list of warnings.
     */
    public List<String> getWarnings() {
      return new ArrayList<>(warnings);
    }

    /**
     * Get the first error message.
     */
    public String getFirstError() {
      return errors.isEmpty() ? null : errors.get(0);
    }

    /**
     * Get all error messages (formatted as a string).
     */
    public String getErrorMessage() {
      if (errors.isEmpty()) {
        return "";
      }
      // Pre-allocate capacity to reduce memory reallocation
      StringBuilder sb = new StringBuilder(128);
      sb.append("Configuration validation failed:\n");
      for (int i = 0; i < errors.size(); i++) {
        sb.append("  ").append(i + 1).append(". ").append(errors.get(i)).append("\n");
      }
      return sb.toString();
    }

    /**
     * Get all warning messages (formatted as a string).
     */
    public String getWarningMessage() {
      if (warnings.isEmpty()) {
        return "";
      }
      // Pre-allocate capacity to reduce memory reallocation
      StringBuilder sb = new StringBuilder(128);
      sb.append("Configuration warnings:\n");
      for (int i = 0; i < warnings.size(); i++) {
        sb.append("  ").append(i + 1).append(". ").append(warnings.get(i)).append("\n");
      }
      return sb.toString();
    }

    /**
     * Get the full validation message (including errors and warnings).
     */
    public String getFullMessage() {
      if (!hasErrors() && !hasWarnings()) {
        return "";
      }
      // Pre-allocate capacity based on expected content
      StringBuilder sb = new StringBuilder(256);
      if (hasErrors()) {
        sb.append(getErrorMessage());
      }
      if (hasWarnings()) {
        if (sb.length() > 0) {
          sb.append("\n");
        }
        sb.append(getWarningMessage());
      }
      return sb.toString();
    }

    /**
     * If validation fails, throw an exception
     *
     * @throws ConfigValidationException when validation fails
     */
    public void throwIfInvalid() throws ConfigValidationException {
      if (!valid) {
        throw new ConfigValidationException(getErrorMessage());
      }
    }

    /**
     * If validation fails, throw a runtime exception
     *
     * @throws ConfigValidationRuntimeException when validation fails
     */
    public void throwIfInvalidAsRuntimeException() throws ConfigValidationRuntimeException {
      if (!valid) {
        throw new ConfigValidationRuntimeException(getErrorMessage());
      }
    }

  }

  /**
   * Validation result builder
   * Convenient for building complex validation results
   */
  class ValidationResultBuilder {
    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    /**
     * Add an error
     */
    public ValidationResultBuilder addError(String error) {
      errors.add(error);
      return this;
    }

    /**
     * Add Warning
     */
    public ValidationResultBuilder addWarning(String warning) {
      warnings.add(warning);
      return this;
    }

    /**
     * Add an error conditionally
     */
    public ValidationResultBuilder addErrorIf(boolean condition, String error) {
      if (condition) {
        errors.add(error);
      }
      return this;
    }

    /**
     * Add a warning conditionally
     */
    public ValidationResultBuilder addWarningIf(boolean condition, String warning) {
      if (condition) {
        warnings.add(warning);
      }
      return this;
    }

    /**
     * Check the range of a value
     */
    public ValidationResultBuilder checkRange(String name, double value, double min, double max) {
      if (value < min || value > max) {
        addError(String.format("%s = %.4f Out of valid range [%.4f, %.4f]", name, value, min, max));
      }
      return this;
    }

    /**
     * Check if a value is positive
     */
    public ValidationResultBuilder checkPositive(String name, double value) {
      if (value <= 0) {
        addError(String.format("%s = %.4f Must be a positive number.", name, value));
      }
      return this;
    }

    /**
     * Check if a value is non-negative
     */
    public ValidationResultBuilder checkNonNegative(String name, double value) {
      if (value < 0) {
        addError(String.format("%s = %.4f Must be non-negative", name, value));
      }
      return this;
    }

    /**
     * Check if a value is positive integer
     */
    public ValidationResultBuilder checkPositiveInt(String name, int value) {
      if (value <= 0) {
        addError(String.format("%s = %d Must be a positive integer", name, value));
      }
      return this;
    }

    /**
     * Check if a value is not null
     */
    public ValidationResultBuilder checkNotNull(String name, Object value) {
      if (value == null) {
        addError(String.format("%s cannot be null", name));
      }
      return this;
    }

    /**
     * Check the size of an array/list
     */
    public ValidationResultBuilder checkSize(String name, int actualSize, int expectedSize) {
      if (actualSize != expectedSize) {
        addError(String.format("%s The size is %d, expected to be %d", name, actualSize, expectedSize));
      }
      return this;
    }

    /**
     * Check if a string is not empty
     */
    public ValidationResultBuilder checkNotEmpty(String name, String value) {
      if (value == null || value.trim().isEmpty()) {
        addError(String.format("%s cannot be null or empty", name));
      }
      return this;
    }

    /**
     * Check if a value is within a minimum range (inclusive)
     */
    public ValidationResultBuilder checkMinimum(String name, double value, double min) {
      if (value < min) {
        addError(String.format("%s = %.4f must be at least %.4f", name, value, min));
      }
      return this;
    }

    /**
     * Check if a value is within a maximum range (inclusive)
     */
    public ValidationResultBuilder checkMaximum(String name, double value, double max) {
      if (value > max) {
        addError(String.format("%s = %.4f must be at most %.4f", name, value, max));
      }
      return this;
    }

    /**
     * Check a custom condition with a custom error message
     */
    public ValidationResultBuilder checkCondition(boolean condition, String errorMessage) {
      if (!condition) {
        addError(errorMessage);
      }
      return this;
    }

    /**
     * Check a custom condition with a custom warning message
     */
    public ValidationResultBuilder warnCondition(boolean condition, String warningMessage) {
      if (!condition) {
        addWarning(warningMessage);
      }
      return this;
    }

    /**
     * Build the validation result
     */
    public ValidationResult build() {
      if (errors.isEmpty() && warnings.isEmpty()) {
        return ValidationResult.success();
      } else if (errors.isEmpty()) {
        return ValidationResult.successWithWarnings(warnings);
      } else {
        return ValidationResult.withErrorsAndWarnings(errors, warnings);
      }
    }
  }

  /**
   * Configuration validation exception (checked exception)
   */
  class ConfigValidationException extends Exception {
    private static final long serialVersionUID = 1L;

    public ConfigValidationException(String message) {
      super(message);
    }

    public ConfigValidationException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /**
   * Configuration validation runtime exception (unchecked exception)
   * Use this when you prefer runtime exceptions over checked exceptions.
   */
  class ConfigValidationRuntimeException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ConfigValidationRuntimeException(String message) {
      super(message);
    }

    public ConfigValidationRuntimeException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}

