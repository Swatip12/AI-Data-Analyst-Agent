package com.dataanalyst.exception;

/**
 * Thrown when the LLM fails to produce a usable SQL statement from the
 * user's natural-language query.
 *
 * <p>Maps to HTTP 422 via {@code GlobalExceptionHandler}.
 */
public class SqlGenerationException extends RuntimeException {

    public SqlGenerationException(String message) {
        super(message);
    }

    public SqlGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
