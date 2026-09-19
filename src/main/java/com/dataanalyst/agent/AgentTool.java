package com.dataanalyst.agent;

import com.dataanalyst.domain.ToolInput;
import com.dataanalyst.domain.ToolResult;

/**
 * Contract for all tools that the AI agent can invoke during query processing.
 *
 * <p>Each tool has a unique {@link #name()} that the agent uses to dispatch
 * calls, and an {@link #execute(ToolInput)} method that performs the tool's
 * work and returns a {@link ToolResult} indicating success or failure.
 *
 * <p>Implementations are registered as Spring beans and discovered via
 * dependency injection by the agent orchestrator.
 */
public interface AgentTool {

    /**
     * Returns the unique identifier for this tool.
     *
     * <p>The name is used by the agent to match tool-call responses from the
     * LLM to the correct implementation.
     *
     * @return non-null, non-blank tool name (e.g., {@code "validate_sql"})
     */
    String name();

    /**
     * Executes the tool with the provided input parameters.
     *
     * @param input tool-specific parameters; must not be {@code null}
     * @return a {@link ToolResult} indicating success ({@code ok}) or failure ({@code error})
     */
    ToolResult execute(ToolInput input);
}
