# Requirements Document

## Introduction

The AI Data Analyst Agent is a production-style full-stack web application that allows business users to query a MySQL database using natural language. The system accepts plain-English questions, generates safe read-only SQL, executes it, analyzes the results, selects an appropriate chart type, and returns a structured response containing a natural-language business insight, a chart, a data table, and the generated SQL. The frontend is built with Angular and communicates with a Spring Boot REST API backend that hosts an AI agent powered by an LLM with tool/function-calling capability.

---

## Glossary

- **Agent**: The AI-powered orchestration component that receives a user question, calls tools in sequence, and synthesizes the final structured response.
- **AnalysisEngine**: The backend service responsible for interpreting SQL result data, deriving business insights, and producing a natural-language summary.
- **ChatUI**: The Angular frontend component that renders the conversation area, question input, AI response, chart, and data table.
- **DatabaseTool**: The backend component responsible for executing validated, read-only SQL queries against the MySQL database and returning result sets.
- **LLM**: The large language model (external API) used by the Agent for natural-language understanding, SQL generation, result interpretation, and chart recommendation.
- **QueryValidator**: The backend service that inspects generated SQL and enforces the read-only security policy before any SQL is executed.
- **SchemaInspector**: The backend service that retrieves and caches the database schema (table names, column names, types) and provides it to the Agent as context.
- **SQLTool**: The backend service that generates SQL from the Agent's intent, calling the LLM with schema context and the user's question.
- **VisualizationPlanner**: The backend service that maps query result structure and user question semantics to a chart type (bar, line, pie, scatter) and axis configuration.
- **ConversationContext**: The per-session state that stores the history of questions and responses to enable follow-up questions.
- **AnalysisResponse**: The structured JSON payload returned to the frontend containing question, SQL, chartType, axis config, data, and summary.
- **System**: The AI Data Analyst Agent application as a whole.
- **API**: The Spring Boot REST API layer.
- **Revenue**: Calculated as `quantity × unit_price`.
- **Profit**: Calculated as `(quantity × unit_price) − (quantity × cost_price)`.

---

## Requirements

---

### Requirement 1: Natural Language Question Processing

**User Story:** As a business user, I want to ask questions about business data in plain English, so that I can gain insights without writing SQL.

#### Acceptance Criteria

1. WHEN a user submits a natural-language question via the ChatUI, THE API SHALL accept the question as a non-empty string of up to 1000 characters.
2. WHEN a question is received, THE Agent SHALL pass the question along with the current ConversationContext to the LLM for intent understanding; IF no prior conversation turns exist for the session, THE Agent SHALL pass an empty ConversationContext.
3. IF a submitted question is empty or contains only whitespace, THEN THE API SHALL return an HTTP 400 response with an error message that explicitly states the input was empty.
4. IF a submitted question exceeds 1000 characters, THEN THE API SHALL return an HTTP 400 response with an error message that explicitly states the input exceeded the character limit.
5. THE API SHALL accept questions in a request body DTO and SHALL NOT accept question text as a path parameter or query string.
6. IF the LLM is unavailable or does not respond within 30 seconds, THEN THE Agent SHALL return an HTTP 503 response with a message indicating the AI service is temporarily unavailable, and THE Agent SHALL NOT modify the ConversationContext for that request.

---

### Requirement 2: Database Schema Awareness

**User Story:** As a business user, I want the agent to understand the database structure, so that it can generate accurate SQL for my questions.

#### Acceptance Criteria

1. THE SchemaInspector SHALL retrieve table names, column names, and column data types from the MySQL `information_schema` for the configured analytics database at application startup.
2. THE SchemaInspector SHALL store the retrieved schema in an in-memory cache keyed by table name.
3. WHEN the Agent requests schema metadata and the in-memory cache is populated, THE SchemaInspector SHALL return the cached schema without querying the database.
4. WHEN the Agent requests schema metadata and the in-memory cache is empty (cache-miss), THE SchemaInspector SHALL query `information_schema`, populate the cache, and return the result.
5. WHEN the Agent begins processing a question, THE Agent SHALL retrieve the current schema from the SchemaInspector and include it in the LLM prompt context before generating SQL.
6. IF the SchemaInspector cannot establish a database connection during the startup retrieval, THEN THE System SHALL log the error at ERROR level and return an HTTP 503 response for any subsequent analysis requests until the cache is successfully populated.
7. THE SchemaInspector SHALL expose schema metadata for exactly the following tables: `customers`, `products`, and `orders`.

---

### Requirement 3: SQL Generation

**User Story:** As a business user, I want the system to generate SQL from my question, so that I do not need database expertise.

#### Acceptance Criteria

1. WHEN the Agent has completed intent understanding and schema retrieval, THE SQLTool SHALL invoke the LLM with a prompt that includes the full schema context and the user's original question to produce a SQL statement.
2. THE SQLTool SHALL produce exactly one SQL statement per invocation.
3. WHEN the LLM returns a response, THE SQLTool SHALL extract the SQL text by removing any surrounding markdown code fences, leading/trailing whitespace, and explanatory prose before passing the raw SQL string to the QueryValidator.
4. IF the LLM response does not contain a recognisable SQL statement (i.e., no string beginning with the `SELECT` keyword is extractable after cleaning), THEN THE SQLTool SHALL return a structured error result with reason "SQL_GENERATION_FAILED", and THE Agent SHALL return an HTTP 422 response with a message stating that the question could not be translated to a query.
5. IF the LLM produces a statement that does not begin with `SELECT` (e.g., a DML or DDL statement), THEN THE SQLTool SHALL treat it as a generation failure, return a structured error result with reason "UNSAFE_SQL_GENERATED", and THE Agent SHALL return an HTTP 422 response without passing the statement to the QueryValidator or DatabaseTool.
6. WHEN the user's question contains the word "revenue" or "profit" (case-insensitive), THE SQLTool SHALL ensure the LLM prompt instructs the model to express Revenue as `(quantity * unit_price)` and Profit as `((quantity * unit_price) - (quantity * cost_price))` as derived column expressions in the generated SQL.

---

### Requirement 4: SQL Validation and Security

**User Story:** As a system administrator, I want all generated SQL to be validated before execution, so that the database is protected from destructive operations.

#### Acceptance Criteria

1. WHEN a SQL statement is submitted to the QueryValidator, THE QueryValidator SHALL trim leading and trailing whitespace and then verify that the statement begins with the keyword `SELECT` using a case-insensitive comparison.
2. IF the statement does not begin with `SELECT` after trimming, THE QueryValidator SHALL return a validation failure result with reason "MISSING_SELECT_KEYWORD".
3. THE QueryValidator SHALL perform a case-insensitive scan of the full statement and reject it IF the statement contains any of the following keywords as whole tokens: `INSERT`, `UPDATE`, `DELETE`, `DROP`, `ALTER`, `TRUNCATE`, `CREATE`, `RENAME`, `GRANT`, `REVOKE`; on rejection the QueryValidator SHALL return a validation failure result with reason "FORBIDDEN_KEYWORD" and include the matched keyword in the result.
4. THE QueryValidator SHALL reject any SQL statement that contains a semicolon (`;`) followed by any non-whitespace character; on rejection it SHALL return a validation failure result with reason "MULTIPLE_STATEMENTS".
5. THE QueryValidator SHALL reject any SQL statement that contains the character sequences `--` or `/*`; on rejection it SHALL return a validation failure result with reason "SQL_COMMENT_DETECTED".
6. IF a SQL statement fails validation for any reason, THEN THE QueryValidator SHALL return a structured validation failure result containing the boolean field `valid: false` and a string field `reason` populated with the specific rejection reason; THE Agent SHALL return an HTTP 422 response to the caller without executing the statement.
7. WHEN a SQL statement passes all validation checks, THE QueryValidator SHALL return a structured validation success result containing the boolean field `valid: true`; THE Agent SHALL then pass the validated SQL to the DatabaseTool for execution.
8. IF the QueryValidator receives a null or empty string as input, THEN THE QueryValidator SHALL return a validation failure result with reason "EMPTY_STATEMENT" without performing further checks.

---

### Requirement 5: Read-Only SQL Execution

**User Story:** As a system administrator, I want SQL to be executed in a read-only context, so that the database cannot be modified through the agent.

#### Acceptance Criteria

1. WHEN the DatabaseTool executes a SQL query, THE DatabaseTool SHALL use a database connection configured with a MySQL user that has only `SELECT` privileges on the analytics schema.
2. THE DatabaseTool SHALL be configured with a query execution timeout of 30 seconds for all query executions.
3. IF a query execution exceeds 30 seconds, THEN THE DatabaseTool SHALL cancel the query and return a structured error result with reason "QUERY_TIMEOUT" to the Agent.
4. THE DatabaseTool SHALL append `LIMIT 1000` to every query before execution to cap result sets at 1000 rows; WHEN the result set is capped, THE DatabaseTool SHALL include a boolean flag `truncated: true` in the result to notify the caller.
5. IF a SQL execution error occurs (excluding timeout), THEN THE DatabaseTool SHALL log the error at ERROR level including the offending SQL and return a structured error result with reason "QUERY_EXECUTION_ERROR" and a sanitized message that does not include raw database error text to the Agent.
6. WHEN execution succeeds, THE DatabaseTool SHALL return the result set as a list of `Map<String, Object>` entries where each key is the column name as returned by the JDBC result set metadata and each value is the corresponding cell value.

---

### Requirement 6: Result Analysis and Business Insight Generation

**User Story:** As a business user, I want the AI to explain what the data means in plain English, so that I can understand the business insight without interpreting raw numbers.

#### Acceptance Criteria

1. WHEN the DatabaseTool returns a non-empty result set, THE AnalysisEngine SHALL pass the result set, the original user question, and the generated SQL to the LLM to produce a natural-language business summary.
2. THE AnalysisEngine SHALL produce a summary of no more than 3 sentences; the summary SHALL explicitly address the subject named in the user's original question (e.g., if the question asks about a city, the summary must reference a city).
3. IF the result set is empty, THEN THE AnalysisEngine SHALL return the fixed summary string "No data was found matching your query criteria." without calling the LLM.
4. WHEN the result set contains at least one numeric column, THE AnalysisEngine SHALL ensure the LLM prompt instructs the model to include at least one specific numeric metric value from the result set in the summary.
5. THE AnalysisEngine SHALL NOT include SQL statement text, table names, column names as they appear in the schema, or stack trace information in the generated summary.
6. IF the LLM call within the AnalysisEngine fails or does not respond within 30 seconds, THEN THE AnalysisEngine SHALL return an HTTP 503 response with the message "Analysis service temporarily unavailable." without including partial or fallback summary text.

---

### Requirement 7: Chart Type Recommendation

**User Story:** As a business user, I want the system to automatically select the most appropriate chart for my data, so that I receive a meaningful visualization without choosing one manually.

#### Acceptance Criteria

1. WHEN result data is available, THE VisualizationPlanner SHALL recommend exactly one chart type from the enumerated values: `bar`, `line`, `pie`, `scatter`.
2. THE following rule priority order SHALL apply when multiple rules match: `line` > `scatter` > `pie` > `bar`; the highest-priority matching rule determines the chart type.
3. WHEN the result set contains at least one column whose name or type indicates a time-based dimension (month, year, quarter, or a DATE/DATETIME SQL type) and at least one numeric column, THE VisualizationPlanner SHALL recommend `line`.
4. WHEN the result set contains exactly two numeric columns and no time-based dimension column, THE VisualizationPlanner SHALL recommend `scatter`.
5. WHEN the result set contains exactly one categorical column and one numeric column, and the number of result rows is between 2 and 6 inclusive, THE VisualizationPlanner SHALL recommend `pie`.
6. WHEN the result set contains at least one categorical column (city, category, product name, segment, or any non-numeric, non-date column) and at least one numeric column, and no higher-priority rule applies, THE VisualizationPlanner SHALL recommend `bar`.
7. THE VisualizationPlanner SHALL identify the x-axis column name and y-axis column name from the result set metadata and include them as string fields `xAxis` and `yAxis` in the AnalysisResponse.
8. IF the VisualizationPlanner cannot resolve the x-axis or y-axis column names from the result set metadata, THEN THE VisualizationPlanner SHALL return an error result with reason "AXIS_RESOLUTION_FAILED" and THE Agent SHALL return an HTTP 422 response.
9. IF no rule matches the result set structure (e.g., a single-column result), THEN THE VisualizationPlanner SHALL recommend `bar` as the default chart type.

---

### Requirement 8: Structured Analysis Response

**User Story:** As a frontend developer, I want the API to return a consistent structured JSON response, so that the Angular app can reliably render charts, tables, and insights.

#### Acceptance Criteria

1. THE API SHALL return an AnalysisResponse JSON object containing the following fields: `question` (string), `sql` (string), `chartType` (string — one of: `bar`, `line`, `pie`, `scatter`), `xAxis` (string), `yAxis` (string), `data` (array of objects, maximum 1000 entries), `summary` (string), and `sessionId` (string).
2. WHEN an analysis request succeeds, THE API SHALL return HTTP 200 with the AnalysisResponse body.
3. IF the request payload fails input validation, THE API SHALL return HTTP 400.
4. IF SQL generation or validation fails, THE API SHALL return HTTP 422 with a JSON error body containing a `message` field and an optional `detail` field.
5. IF a downstream service (LLM or database) is unavailable, THE API SHALL return HTTP 503 with a JSON error body containing a `message` field and an optional `detail` field.
6. IF an unexpected internal error occurs, THE API SHALL return HTTP 500 with a JSON error body containing a `message` field and a `errorId` field.
7. THE API SHALL NOT include internal stack traces, database connection strings, LLM API keys, or raw exception messages in any response body.
8. THE API SHALL set `Content-Type: application/json` on all responses.
9. IF the result set is empty, THE API SHALL return HTTP 200 with an AnalysisResponse where `data` is an empty array, `chartType` is `bar`, `xAxis` and `yAxis` are empty strings, and `summary` is "No data was found matching your query criteria."

---

### Requirement 9: Conversation Context (Session Memory)

**User Story:** As a business user, I want to ask follow-up questions that reference my previous questions, so that I can conduct a multi-turn analysis conversation.

#### Acceptance Criteria

1. THE API SHALL accept an optional `sessionId` string field in the analysis request DTO.
2. WHEN a `sessionId` is provided in the request and a ConversationContext exists for that ID, THE Agent SHALL load and use that ConversationContext for the current request.
3. IF a provided `sessionId` does not match any active session (unknown or evicted), THEN THE API SHALL return HTTP 400 with a message stating the session was not found or has expired.
4. WHEN a `sessionId` is not provided, THE API SHALL generate a new UUID as the `sessionId`, create a new empty ConversationContext for it, and include the new `sessionId` in the AnalysisResponse.
5. WHEN a request is successfully completed, THE Agent SHALL append the question-response pair to the ConversationContext identified by the `sessionId`.
6. THE Agent SHALL include up to the last 5 successfully completed question-response pairs from the ConversationContext in the LLM prompt for each new request.
7. WHEN a ConversationContext has received no new successfully completed messages for 30 consecutive minutes, THE System SHALL evict that session from memory.
8. THE API SHALL return the `sessionId` in every AnalysisResponse so that the ChatUI can include it in subsequent requests.

---

### Requirement 10: Angular Chat Interface

**User Story:** As a business user, I want a modern chat-style web interface, so that I can interact with the AI agent intuitively.

#### Acceptance Criteria

1. THE ChatUI SHALL display a sidebar containing navigation items: "New Analysis", "Previous Questions", and "Dashboard".
2. THE ChatUI SHALL display a main content area containing the conversation thread, the question input box, and a send button.
3. WHEN the send button is clicked or the Enter key is pressed and the question input contains at least one non-whitespace character, THE ChatUI SHALL submit the question to the API and display a loading indicator within the conversation thread.
4. WHILE an analysis request is in-flight, THE ChatUI SHALL disable the send button and set the question input to a read-only state to prevent duplicate submissions.
5. WHEN an AnalysisResponse is received and the `data` array is non-empty, THE ChatUI SHALL render the AI summary text, the generated SQL in a syntax-highlighted code block, a data table, and a chart — all within the conversation thread.
6. WHEN an AnalysisResponse is received and the `data` array is empty, THE ChatUI SHALL render the AI summary text and the generated SQL code block, and SHALL display "No data available to visualize" in place of the chart and table.
7. IF the API returns an error response, THEN THE ChatUI SHALL display a user-friendly error message within the conversation thread; the message SHALL NOT contain stack traces, raw SQL, or raw exception text.
8. THE ChatUI SHALL NOT display the LLM API key or any backend configuration secrets in the browser at any time.

---

### Requirement 11: Dynamic Chart Rendering

**User Story:** As a business user, I want to see my data visualized as a chart, so that I can quickly identify patterns and trends.

#### Acceptance Criteria

1. THE ChatUI SHALL render charts using a canvas-based charting library compatible with Angular; the library SHALL NOT make direct calls to the LLM provider or any external analytics service.
2. WHEN the AnalysisResponse `chartType` is `bar`, THE ChatUI SHALL render a bar chart where category labels are sourced from the `xAxis` column values in the `data` array and bar heights are sourced from the `yAxis` column values in the `data` array.
3. WHEN the AnalysisResponse `chartType` is `line`, THE ChatUI SHALL render a line chart where the horizontal axis values are sourced from the `xAxis` column values in the `data` array and the vertical axis values are sourced from the `yAxis` column values in the `data` array.
4. WHEN the AnalysisResponse `chartType` is `pie`, THE ChatUI SHALL render a pie chart where slice labels are sourced from the `xAxis` column values in the `data` array and slice sizes are sourced from the `yAxis` column values in the `data` array.
5. WHEN the AnalysisResponse `chartType` is `scatter`, THE ChatUI SHALL render a scatter chart where horizontal axis values are sourced from the `xAxis` column values in the `data` array and vertical axis values are sourced from the `yAxis` column values in the `data` array.
6. THE ChatUI SHALL display axis labels using the exact string values of the `xAxis` and `yAxis` fields from the AnalysisResponse.
7. WHEN the `data` array in the AnalysisResponse is empty, THE ChatUI SHALL display the message "No data available to visualize" in place of the chart canvas.
8. IF the `chartType` value in the AnalysisResponse is not one of `bar`, `line`, `pie`, or `scatter`, THEN THE ChatUI SHALL display the message "Chart type not supported" in place of the chart canvas and SHALL NOT attempt to render a chart.

---

### Requirement 12: Data Table Rendering

**User Story:** As a business user, I want to see the raw query results in a table alongside the chart, so that I can verify the underlying data.

#### Acceptance Criteria

1. IF the AnalysisResponse contains a non-empty `data` array, THEN THE ChatUI SHALL render a data table within the conversation thread for that response.
2. THE ChatUI SHALL derive table column headers from the keys of the first object in the `data` array, preserving the original key order.
3. THE ChatUI SHALL render a maximum of 50 rows from the `data` array in the table.
4. WHEN the `data` array contains more than 50 entries, THE ChatUI SHALL display the message "Showing 50 of N rows" (where N is the total count from the `data` array) directly below the table.
5. THE ChatUI SHALL format integer numeric values in table cells with thousand-separator commas (e.g., 1250000 → 1,250,000).
6. THE ChatUI SHALL format decimal numeric values in table cells with thousand-separator commas and exactly two decimal places (e.g., 12500.5 → 12,500.50).

---

### Requirement 13: Security — API Key Management

**User Story:** As a system administrator, I want LLM API keys to be stored securely and never exposed to the browser, so that API credentials are not compromised.

#### Acceptance Criteria

1. THE API SHALL load the LLM API key exclusively from an environment variable or an externalized configuration file at startup.
2. THE API SHALL NOT contain the LLM API key as a hardcoded string literal in any source file committed to version control.
3. THE API SHALL NOT include the LLM API key in any HTTP response body, HTTP response header, log entry, or error message.
4. THE ChatUI SHALL communicate exclusively with the Spring Boot API for all data and analysis requests.
5. THE ChatUI SHALL NOT make direct HTTP calls to any LLM provider endpoint.
6. THE API SHALL use HTTPS for all outbound HTTP calls to the LLM provider endpoint.
7. IF the externalized configuration file containing the LLM API key exists on the filesystem, THEN the file SHALL be listed in `.gitignore` or an equivalent version-control exclusion mechanism to prevent it from being tracked.
8. IF the LLM API key environment variable or configuration value is absent at application startup, THEN THE API SHALL fail to start and log an ERROR-level message stating that the LLM API key is not configured.

---

### Requirement 14: Global Exception Handling

**User Story:** As a developer, I want all unhandled exceptions to produce consistent, safe error responses, so that internal details are never leaked to clients.

#### Acceptance Criteria

1. THE API SHALL implement a global exception handler that intercepts all unhandled exceptions and maps them to a JSON error response containing at minimum the fields `message` (string) and `errorId` (string).
2. WHEN an unhandled exception occurs, THE API SHALL return HTTP 500 with a JSON body containing a generic human-readable `message` field and a `errorId` field whose value is a UUID generated for that specific error occurrence.
3. THE API SHALL log the full exception stack trace at ERROR level; the log entry SHALL include the same UUID value as the `errorId` field returned in the HTTP response to enable log correlation.
4. THE API SHALL NOT include exception class names, stack traces, database error codes, or raw exception messages in any error response body returned to the client.

---

### Requirement 15: Multi-Step Analysis Architecture (Future Capability)

**User Story:** As a business user, I want the system to investigate "why" questions across multiple dimensions, so that I can receive deep diagnostic insights.

#### Acceptance Criteria

1. THE Agent SHALL be designed with an extensible tool-calling architecture such that additional analysis tools can be registered by adding new tool implementations without modifying the core agent orchestration loop.
2. WHEN processing a diagnostic question (e.g., "Why did revenue decrease in March?"), THE Agent SHALL be capable of executing multiple sequential SQL queries across different dimensions (monthly, city, category, product) before generating the final summary.
3. THE AnalysisResponse SHALL include a `steps` field typed as a nullable array of objects; each step object SHALL carry at minimum the fields `sql` (string) and `data` (array of objects) representing one intermediate query and its result.
4. THE Agent's tool-calling loop SHALL track the number of tool invocations per user request and SHALL NOT exceed 10 tool calls; IF the limit is reached before the Agent produces a final answer, THEN THE Agent SHALL stop the loop and return the partial analysis collected so far with a summary note indicating the analysis was incomplete.
5. WHEN the tool-call limit of 10 is reached and the loop is halted, THE Agent SHALL return HTTP 200 with the AnalysisResponse populated with whatever `steps` and `summary` were accumulated, and SHALL set a boolean flag `incomplete: true` in the AnalysisResponse to signal to the ChatUI that the analysis did not fully complete.
