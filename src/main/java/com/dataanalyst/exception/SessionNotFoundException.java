package com.dataanalyst.exception;

/**
 * Thrown when a requested session ID cannot be found in the session store.
 *
 * <p>Maps to HTTP 404 via {@code GlobalExceptionHandler}.
 */
public class SessionNotFoundException extends RuntimeException {

    public SessionNotFoundException(String message) {
        super(message);
    }

    public SessionNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
