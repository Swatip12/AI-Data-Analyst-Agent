package com.dataanalyst.domain;

import java.util.Map;

/**
 * Immutable value object that carries the input parameters for an {@code AgentTool}
 * invocation.
 *
 * <p>Parameters are stored as a generic {@code Map<String, Object>} so that each
 * tool can define and extract the keys it needs without requiring a dedicated DTO
 * per tool.
 *
 * @param params tool-specific input parameters; must not be {@code null}
 */
public record ToolInput(Map<String, Object> params) {
}
