package com.dataanalyst.service;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.dataanalyst.agent.AgentTool;
import com.dataanalyst.domain.SchemaContext;
import com.dataanalyst.domain.ToolInput;
import com.dataanalyst.domain.ToolResult;
import com.dataanalyst.exception.LlmUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Agent tool that generates a SQL {@code SELECT} statement from a natural-language
 * question and a database schema snapshot.
 *
 * <h2>Behaviour overview</h2>
 * <ol>
 *   <li>Extracts {@code "question"} and {@code "schema"} from {@link ToolInput#params()}.</li>
 *   <li>Detects whether the question contains the words <em>revenue</em> or
 *       <em>profit</em> (case-insensitive) and, if so, injects derivation
 *       formulae into the prompt (requirement 3.6).</li>
 *   <li>Calls the configured OpenAI-compatible Chat Completions endpoint via
 *       OkHttp (requirement 3.1).</li>
 *   <li>Strips markdown fences and locates the first {@code SELECT}-starting
 *       token in the LLM response (requirement 3.3).</li>
 *   <li>Returns {@link ToolResult#error(String) error("SQL_GENERATION_FAILED")} when
 *       no {@code SELECT} can be extracted (requirement 3.4).</li>
 *   <li>Returns {@link ToolResult#error(String) error("UNSAFE_SQL_GENERATED")} when
 *       the extracted statement does not begin with {@code SELECT}
 *       (requirement 3.5).</li>
 *   <li>Returns {@link ToolResult#ok(Object) ok(sql)} on success (requirement 3.2).</li>
 * </ol>
 */
@Service
public class SQLTool implements AgentTool {

    // -------------------------------------------------------------------------
    // Configuration
    // -------------------------------------------------------------------------

    @Value("${openai.api-key:}")
    private String apiKey;

    @Value("${openai.base-url:https://api.openai.com/v1}")
    private String baseUrl;

    @Value("${openai.model:gpt-4o}")
    private String model;

    @Value("${openai.timeout-seconds:30}")
    private int timeoutSeconds;

    // -------------------------------------------------------------------------
    // Infrastructure
    // -------------------------------------------------------------------------

    private final ObjectMapper objectMapper = new ObjectMapper();

    // -------------------------------------------------------------------------
    // AgentTool contract
    // -------------------------------------------------------------------------

    /** {@inheritDoc} */
    @Override
    public String name() {
        return "generate_sql";
    }

    /**
     * Generates a SQL {@code SELECT} statement for the given question and schema.
     *
     * <p>Expects the following keys in {@code input.params()}:
     * <ul>
     *   <li>{@code "question"} — the natural-language question ({@link String})</li>
     *   <li>{@code "schema"} — the database schema ({@link SchemaContext})</li>
     * </ul>
     *
     * @param input tool-specific parameters; must not be {@code null}
     * @return {@link ToolResult#ok(Object)} carrying the generated SQL string on
     *         success, or a {@link ToolResult#error(String)} with one of
     *         {@code "SQL_GENERATION_FAILED"} or {@code "UNSAFE_SQL_GENERATED"} on failure
     * @throws LlmUnavailableException if the LLM endpoint times out or returns
     *         a non-2xx HTTP response
     */
    @Override
    public ToolResult execute(ToolInput input) {
        String question = (String) input.params().get("question");
        SchemaContext schema = (SchemaContext) input.params().get("schema");

        // Requirement 3.6 — detect revenue/profit keywords
        boolean hasRevenue = question != null && question.toLowerCase().contains("revenue");
        boolean hasProfit  = question != null && question.toLowerCase().contains("profit");

        String prompt = buildSqlPrompt(question, schema, hasRevenue, hasProfit);

        // Requirement 3.1 — call LLM with schema + question
        String llmResponse = callLlm(prompt);

        // Requirements 3.3, 3.4 — strip fences, find first SELECT
        String sql = extractSql(llmResponse);

        if (sql == null) {
            // Requirement 3.4 — no recognisable SELECT found
            return ToolResult.error("SQL_GENERATION_FAILED");
        }

        if (!sql.trim().toUpperCase().startsWith("SELECT")) {
            // Requirement 3.5 — extracted SQL does not begin with SELECT
            return ToolResult.error("UNSAFE_SQL_GENERATED");
        }

        // Requirement 3.2 — exactly one SQL statement returned
        return ToolResult.ok(sql);
    }

    // -------------------------------------------------------------------------
    // Package-private helpers (accessible from unit tests)
    // -------------------------------------------------------------------------

    /**
     * Strips markdown code fences and prose surrounding the SQL block, then
     * locates and returns everything from the first {@code SELECT} token onward.
     *
     * <h2>Processing steps</h2>
     * <ol>
     *   <li>Return {@code null} for {@code null} or blank input.</li>
     *   <li>Remove all {@code ```sql} / {@code ```SQL} / {@code ```} fence
     *       delimiters (with optional language hint).</li>
     *   <li>Trim leading/trailing whitespace from the result.</li>
     *   <li>Scan for the first occurrence of {@code SELECT} at the start of
     *       a word boundary (case-insensitive).</li>
     *   <li>Return the substring from that {@code SELECT} to the end, trimmed.</li>
     *   <li>Return {@code null} if no such token is found.</li>
     * </ol>
     *
     * @param llmOutput the raw text returned by the LLM; may be {@code null}
     * @return the extracted SQL string, or {@code null} if extraction failed
     */
    String extractSql(String llmOutput) {
        if (llmOutput == null || llmOutput.isBlank()) {
            return null;
        }

        // Requirement 3.3 — remove markdown code fences (with optional "sql" language hint)
        String stripped = llmOutput.replaceAll("(?is)```(?:sql)?\\s*", "").trim();

        // Find first SELECT token (case-insensitive, word-boundary anchored)
        Pattern selectPattern = Pattern.compile("(?is)(SELECT\\b.*)");
        Matcher matcher = selectPattern.matcher(stripped);

        if (matcher.find()) {
            return matcher.group(1).trim();
        }

        return null;
    }

    /**
     * Builds the user-facing prompt sent to the LLM.
     *
     * <p>Always includes the schema DDL (via {@link SchemaContext#toPromptString()}) and
     * the user question. When {@code hasRevenue} or {@code hasProfit} is {@code true},
     * appends the corresponding derivation formula to the prompt (requirement 3.6).
     *
     * @param question   the natural-language question; may be {@code null}
     * @param schema     the schema context to embed; may be {@code null}
     * @param hasRevenue {@code true} if the question contains the word <em>revenue</em>
     * @param hasProfit  {@code true} if the question contains the word <em>profit</em>
     * @return a fully assembled prompt string ready to be sent to the LLM
     */
    String buildSqlPrompt(String question, SchemaContext schema, boolean hasRevenue, boolean hasProfit) {
        StringBuilder sb = new StringBuilder();

        sb.append("You are an expert SQL generator. Given the database schema below, ");
        sb.append("generate a single SQL SELECT query that answers the user's question.\n\n");

        // Embed schema DDL
        sb.append("## Database Schema\n\n");
        if (schema != null) {
            sb.append(schema.toPromptString());
        } else {
            sb.append("(no schema provided)");
        }
        sb.append("\n\n");

        // Embed user question
        sb.append("## Question\n\n");
        sb.append(question != null ? question : "(no question provided)");
        sb.append("\n\n");

        // Requirement 3.6 — inject derivation instructions when applicable
        if (hasRevenue || hasProfit) {
            sb.append("## Derivation Rules\n\n");
            if (hasRevenue) {
                sb.append("Revenue must be expressed as `(quantity * unit_price)`.\n");
            }
            if (hasProfit) {
                sb.append("Profit must be expressed as `((quantity * unit_price) - (quantity * cost_price))`.\n");
            }
            sb.append("\n");
        }

        sb.append("## Instructions\n\n");
        sb.append("Return ONLY the SQL query — no explanations, no prose, no comments. ");
        sb.append("Wrap the query inside a ```sql ... ``` code block. ");
        sb.append("The query must start with SELECT.");

        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // LLM HTTP call
    // -------------------------------------------------------------------------

    /**
     * Sends the prompt to the configured OpenAI-compatible Chat Completions
     * endpoint and returns the assistant's reply text.
     *
     * <p>Constructs a {@code POST} request with a JSON body in OpenAI Chat
     * Completions format, including a system message and the supplied user prompt.
     * Uses a fresh {@link OkHttpClient} configured with a call timeout equal to
     * {@code openai.timeout-seconds}.
     *
     * @param prompt the user prompt to send
     * @return the raw text content of {@code choices[0].message.content}
     * @throws LlmUnavailableException if a socket/interrupt timeout occurs, or if
     *         the server returns a non-2xx HTTP status code
     */
    private String callLlm(String prompt) {
        OkHttpClient client = new OkHttpClient.Builder()
                .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .build();

        // Build request JSON
        ObjectNode requestBody = objectMapper.createObjectNode();
        requestBody.put("model", model);
        requestBody.put("temperature", 0);

        ArrayNode messages = objectMapper.createArrayNode();

        ObjectNode systemMessage = objectMapper.createObjectNode();
        systemMessage.put("role", "system");
        systemMessage.put("content",
                "You are an expert SQL generator. You produce exactly one SQL SELECT statement per request. " +
                "Return only the SQL query inside a ```sql ... ``` code block with no additional prose.");
        messages.add(systemMessage);

        ObjectNode userMessage = objectMapper.createObjectNode();
        userMessage.put("role", "user");
        userMessage.put("content", prompt);
        messages.add(userMessage);

        requestBody.set("messages", messages);

        String jsonBody;
        try {
            jsonBody = objectMapper.writeValueAsString(requestBody);
        } catch (Exception e) {
            throw new LlmUnavailableException("Failed to serialize LLM request", e);
        }

        String url = baseUrl.endsWith("/")
                ? baseUrl + "chat/completions"
                : baseUrl + "/chat/completions";

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(jsonBody, MediaType.get("application/json")))
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new LlmUnavailableException(
                        "LLM returned non-success status: " + response.code());
            }

            okhttp3.ResponseBody responseBody = response.body();
            String responseBodyStr = responseBody != null ? responseBody.string() : "";
            JsonNode responseJson = objectMapper.readTree(responseBodyStr);

            return responseJson
                    .path("choices")
                    .path(0)
                    .path("message")
                    .path("content")
                    .asText();

        } catch (SocketTimeoutException e) {
            throw new LlmUnavailableException("LLM request timed out", e);
        } catch (InterruptedIOException e) {
            throw new LlmUnavailableException("LLM request interrupted", e);
        } catch (LlmUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmUnavailableException("LLM request failed: " + e.getMessage(), e);
        }
    }
}
