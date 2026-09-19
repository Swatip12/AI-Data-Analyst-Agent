package com.dataanalyst.exception;

/**
 * Thrown by {@code SchemaInspector} when the schema cache is empty and the
 * database cannot be reached to populate it.
 *
 * <p>Maps to HTTP 503 via {@code GlobalExceptionHandler}. The cause (if any)
 * is a database connectivity exception and must not be surfaced in the HTTP
 * response body (requirement 14.4).
 */
public class SchemaUnavailableException extends RuntimeException {

    public SchemaUnavailableException(String message) {
        super(message);
    }

    public SchemaUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
