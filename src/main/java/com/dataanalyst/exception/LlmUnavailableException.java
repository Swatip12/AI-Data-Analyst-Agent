package com.dataanalyst.exception;

/**
 * Thrown when the LLM API cannot be reached or returns an unrecoverable error.
 *
 * <p>Maps to HTTP 503 via {@code GlobalExceptionHandler}. The underlying cause
 * must not be surfaced in the HTTP response body.
 */
public class LlmUnavailableException extends RuntimeException {

    public LlmUnavailableException(String message) {
        super(message);
    }

    public LlmUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
