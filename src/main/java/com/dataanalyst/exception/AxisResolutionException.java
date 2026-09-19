package com.dataanalyst.exception;

/**
 * Thrown when the agent cannot determine which columns should map to the
 * X and Y axes of the requested chart type.
 *
 * <p>Maps to HTTP 422 via {@code GlobalExceptionHandler}.
 */
public class AxisResolutionException extends RuntimeException {

    public AxisResolutionException(String message) {
        super(message);
    }

    public AxisResolutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
