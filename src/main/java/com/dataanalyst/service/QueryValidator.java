package com.dataanalyst.service;

import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.dataanalyst.agent.AgentTool;
import com.dataanalyst.domain.ToolInput;
import com.dataanalyst.domain.ToolResult;
import com.dataanalyst.domain.ValidationResult;

/**
 * Validates a SQL string against a strict set of safety and structure rules
 * before execution.
 *
 * <h2>Validation sequence (short-circuit — first failure wins)</h2>
 * <ol>
 *   <li>Null / blank input → {@code EMPTY_STATEMENT}</li>
 *   <li>Trimmed, case-insensitive {@code SELECT} prefix check → {@code MISSING_SELECT_KEYWORD}</li>
 *   <li>Whole-token forbidden DML/DDL keyword scan → {@code FORBIDDEN_KEYWORD} + matched keyword</li>
 *   <li>Semicolon followed by non-whitespace → {@code MULTIPLE_STATEMENTS}</li>
 *   <li>{@code --} or {@code /*} present → {@code SQL_COMMENT_DETECTED}</li>
 * </ol>
 *
 * <p>Implements {@link AgentTool} so that the agent can invoke this validator
 * as a named tool ({@code "validate_sql"}).
 */
@Service
public class QueryValidator implements AgentTool {

    // -------------------------------------------------------------------------
    // Constants
    // -------------------------------------------------------------------------

    /** Forbidden DML / DDL keywords (requirement 4.3). */
    private static final List<String> FORBIDDEN_KEYWORDS = List.of(
            "INSERT", "UPDATE", "DELETE", "DROP", "ALTER",
            "TRUNCATE", "CREATE", "RENAME", "GRANT", "REVOKE"
    );

    /**
     * Pre-compiled {@link Pattern} objects for each forbidden keyword.
     * Using {@code \b...\b} ensures only whole-token matches (requirement 4.3).
     */
    private static final List<Pattern> FORBIDDEN_PATTERNS = FORBIDDEN_KEYWORDS.stream()
            .map(kw -> Pattern.compile("\\b" + kw + "\\b", Pattern.CASE_INSENSITIVE))
            .toList();

    // -------------------------------------------------------------------------
    // AgentTool contract
    // -------------------------------------------------------------------------

    /** {@inheritDoc} */
    @Override
    public String name() {
        return "validate_sql";
    }

    /**
     * Extracts the {@code "sql"} parameter from {@code input.params()}, delegates
     * to {@link #validate(String)}, and wraps the result in a {@link ToolResult}.
     *
     * <ul>
     *   <li>On validation success → {@link ToolResult#ok(Object)} carrying the
     *       {@link ValidationResult}</li>
     *   <li>On validation failure → {@link ToolResult#error(String)} carrying
     *       the failure reason</li>
     * </ul>
     *
     * @param input must not be {@code null}; the {@code "sql"} key is expected
     *              under {@code input.params()}
     * @return a {@link ToolResult} wrapping the {@link ValidationResult}
     */
    @Override
    public ToolResult execute(ToolInput input) {
        Object sqlParam = input.params().get("sql");
        String sql = (sqlParam instanceof String s) ? s : (sqlParam == null ? null : sqlParam.toString());

        ValidationResult result = validate(sql);
        if (result.valid()) {
            return ToolResult.ok(result);
        }
        return ToolResult.error(result.reason());
    }

    // -------------------------------------------------------------------------
    // Core validation logic
    // -------------------------------------------------------------------------

    /**
     * Runs the five-step short-circuit validation sequence against the supplied SQL.
     *
     * @param sql the SQL string to validate; may be {@code null}
     * @return a {@link ValidationResult} — {@code valid=true} on full pass,
     *         {@code valid=false} with a reason code on the first failure
     */
    public ValidationResult validate(String sql) {

        // Step 1 — Null / blank check (requirement 4.8)
        if (sql == null || sql.isBlank()) {
            return ValidationResult.fail("EMPTY_STATEMENT");
        }

        String trimmed = sql.trim();

        // Step 2 — Must start with SELECT (case-insensitive) (requirements 4.1, 4.2)
        if (!trimmed.toUpperCase().startsWith("SELECT")) {
            return ValidationResult.fail("MISSING_SELECT_KEYWORD");
        }

        // Step 3 — Whole-token forbidden keyword scan (requirement 4.3)
        for (int i = 0; i < FORBIDDEN_KEYWORDS.size(); i++) {
            if (FORBIDDEN_PATTERNS.get(i).matcher(trimmed).find()) {
                return ValidationResult.failWithKeyword("FORBIDDEN_KEYWORD", FORBIDDEN_KEYWORDS.get(i));
            }
        }

        // Step 4 — Semicolon followed by non-whitespace → multiple statements (requirement 4.4)
        if (trimmed.matches("(?s).*;\\S.*")) {
            return ValidationResult.fail("MULTIPLE_STATEMENTS");
        }

        // Step 5 — SQL comment markers (requirement 4.5)
        if (trimmed.contains("--") || trimmed.contains("/*")) {
            return ValidationResult.fail("SQL_COMMENT_DETECTED");
        }

        // All checks passed (requirements 4.6, 4.7)
        return ValidationResult.ok();
    }
}
