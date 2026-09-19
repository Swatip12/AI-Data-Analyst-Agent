package com.dataanalyst.exception;

/**
 * Thrown when {@code QueryValidator} rejects a SQL statement.
 *
 * <p>Carries the machine-readable reason code (e.g., {@code "FORBIDDEN_KEYWORD"})
 * that {@code GlobalExceptionHandler} can include in the HTTP 400 response body.
 */
public class ValidationFailureException extends RuntimeException {

    private final String reason;

    public ValidationFailureException(String reason) {
        super("SQL validation failed: " + reason);
        this.reason = reason;
    }

    public ValidationFailureException(String reason, Throwable cause) {
        super("SQL validation failed: " + reason, cause);
        this.reason = reason;
    }

    /** Returns the machine-readable validation failure reason code. */
    public String getReason() {
        return reason;
    }
}
