package com.dataanalyst.exception;

/**
 * Thrown when the analytics database cannot be reached during query execution.
 *
 * <p>Maps to HTTP 503 via {@code GlobalExceptionHandler}. The underlying cause
 * must not be surfaced in the HTTP response body.
 */
public class DatabaseUnavailableException extends RuntimeException {

    public DatabaseUnavailableException(String message) {
        super(message);
    }

    public DatabaseUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
