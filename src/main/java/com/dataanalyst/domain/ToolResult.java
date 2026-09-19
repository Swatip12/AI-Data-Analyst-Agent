package com.dataanalyst.domain;

/**
 * Immutable value object representing the outcome of an {@code AgentTool} execution.
 *
 * <p>Use the static factory methods {@link #ok(Object)} and {@link #error(String)}
 * rather than constructing instances directly.
 *
 * @param success     {@code true} when the tool completed without error
 * @param payload     the result object on success; {@code null} on error
 * @param errorReason human-readable error description; {@code null} on success
 */
public record ToolResult(boolean success, Object payload, String errorReason) {

    /**
     * Creates a successful result carrying the given payload.
     *
     * @param payload the tool's output value
     * @return a successful {@code ToolResult}
     */
    public static ToolResult ok(Object payload) {
        return new ToolResult(true, payload, null);
    }

    /**
     * Creates a failed result with the given error description.
     *
     * @param reason human-readable description of what went wrong
     * @return a failed {@code ToolResult}
     */
    public static ToolResult error(String reason) {
        return new ToolResult(false, null, reason);
    }
}
