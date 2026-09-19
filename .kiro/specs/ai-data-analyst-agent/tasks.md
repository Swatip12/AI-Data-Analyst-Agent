# Implementation Plan: AI Data Analyst Agent

## Overview

Implement a production-style full-stack web application allowing business users to query a MySQL database in plain English. The Spring Boot 3.x (Java 21) backend orchestrates an LLM-powered tool-calling loop; the Angular 17+ frontend renders the conversation, chart, data table, and SQL. Implementation proceeds from infrastructure and shared types, through core backend services, agent orchestration, REST layer, and finally the Angular UI.

---

## Tasks

- [ ] 1. Project scaffolding and shared domain types
  - [ ] 1.1 Initialize Spring Boot project structure with required dependencies
    - Create Maven/Gradle project with Spring Boot 3.x, Spring Data JDBC, Spring Web, Spring Validation, jqwik, JUnit 5, Mockito, Testcontainers, and OpenAI-compatible HTTP client (e.g., `spring-ai` or `OkHttp`)
    - Create base packages: `controller`, `agent`, `service`, `domain`, `config`, `exception`
    - Add `application.yml` skeleton with placeholders for `spring.datasource.*`, `openai.api-key`, and `openai.base-url`
    - Add `application-secrets.yml` to `.gitignore`; add startup guard bean that fails fast when `openai.api-key` is absent (requirement 13.8)
    - _Requirements: 13.1, 13.2, 13.7, 13.8_

  - [ ] 1.2 Define all shared domain record types and interfaces
    - Create `ToolInput`, `ToolResult` (with static `ok`/`error` factories), `ValidationResult`, `QueryResult`, `VisualizationPlan`, `TurnPair`, `ColumnMetadata`, `SchemaContext` (with `toPromptString()`), `AnalysisRequest`, `AnalysisResponse`, `StepResult`, `ErrorResponse`
    - Create `AgentTool` interface with `name()` and `execute(ToolInput)` methods
    - Create custom exception classes: `SessionNotFoundException`, `SqlGenerationException`, `ValidationFailureException`, `AxisResolutionException`, `LlmUnavailableException`, `DatabaseUnavailableException`, `SchemaUnavailableException`
    - _Requirements: 3.4, 3.5, 4.6, 8.1, 9.3, 14.1_

- [x] 2. Security and configuration infrastructure
  - [x] 2.1 Configure dual DataSources and CORS
    - Define a read-only DataSource bean (`analytics`) configured with a MySQL user that has only `SELECT` privileges; configure the default DataSource for schema inspection
    - Add CORS configuration allowing `http://localhost:4200` and production domain on `/api/**` endpoints
    - _Requirements: 5.1, 8.8_

- [ ] 3. SchemaInspector service
  - [x] 3.1 Implement `SchemaInspector` with startup cache population
    - Implement `ApplicationListener<ApplicationReadyEvent>` to query `information_schema.columns` for tables `customers`, `products`, `orders` at startup
    - Store results in `ConcurrentHashMap<String, List<ColumnMetadata>>` keyed by table name
    - Implement `getSchema()` — return cache if populated; throw `SchemaUnavailableException` if empty
    - Implement cache-miss path: on `getSchema()` call when cache is empty, query `information_schema` before returning
    - Log at ERROR level and mark system unhealthy on connection failure at startup
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.6, 2.7_

  - [ ]* 3.2 Write unit tests for SchemaInspector
    - Test cache-hit path: subsequent calls return cached schema without DB query
    - Test cache-miss path: `getSchema()` triggers DB query and populates cache
    - Test startup failure: logs ERROR, `getSchema()` throws `SchemaUnavailableException`
    - _Requirements: 2.3, 2.4, 2.6_

- [x] 4. QueryValidator service
  - [x] 4.1 Implement `QueryValidator` with all validation checks
    - Implement the five-step short-circuit validation sequence in order:
      1. Null/blank → `EMPTY_STATEMENT`
      2. Trimmed, case-insensitive `SELECT` prefix check → `MISSING_SELECT_KEYWORD`
      3. Whole-token forbidden keyword scan (`INSERT`, `UPDATE`, `DELETE`, `DROP`, `ALTER`, `TRUNCATE`, `CREATE`, `RENAME`, `GRANT`, `REVOKE`) → `FORBIDDEN_KEYWORD` + matched keyword
      4. Semicolon followed by non-whitespace → `MULTIPLE_STATEMENTS`
      5. `--` or `/*` present → `SQL_COMMENT_DETECTED`
    - Implement `AgentTool.execute(ToolInput)` wrapper
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 4.8_

  - [ ]* 4.2 Write property test for QueryValidator — valid SELECT acceptance (Property 5)
    - **Property 5: QueryValidator accepts well-formed SELECT statements**
    - **Validates: Requirements 4.1, 4.7**
    - Use jqwik `@Property` with 100 tries; generate arbitrary SELECT statements with no forbidden keywords, no `--`/`/*`, no `; + non-whitespace`
    - Assert `ValidationResult.valid == true` for all generated inputs

  - [ ]* 4.3 Write property test for QueryValidator — non-SELECT rejection (Property 6)
    - **Property 6: QueryValidator rejects non-SELECT statements**
    - **Validates: Requirements 4.1, 4.2**
    - Generate arbitrary strings whose first non-whitespace token is not `SELECT`
    - Assert `valid=false`, `reason="MISSING_SELECT_KEYWORD"`

  - [ ]* 4.4 Write property test for QueryValidator — forbidden keyword rejection (Property 7)
    - **Property 7: QueryValidator rejects forbidden DML/DDL keywords**
    - **Validates: Requirements 4.3**
    - Generate SELECT statements injected with one of the 10 forbidden keywords as a whole token
    - Assert `valid=false`, `reason="FORBIDDEN_KEYWORD"`, matched keyword populated

  - [ ]* 4.5 Write property test for QueryValidator — multi-statement rejection (Property 8)
    - **Property 8: QueryValidator rejects multi-statement SQL**
    - **Validates: Requirements 4.4**
    - Generate strings containing `; ` followed by non-whitespace
    - Assert `valid=false`, `reason="MULTIPLE_STATEMENTS"`

  - [ ]* 4.6 Write property test for QueryValidator — comment rejection (Property 9)
    - **Property 9: QueryValidator rejects SQL with comment sequences**
    - **Validates: Requirements 4.5**
    - Generate strings containing `--` or `/*`
    - Assert `valid=false`, `reason="SQL_COMMENT_DETECTED"`

- [~] 5. SQLTool service
  - [-] 5.1 Implement `SQLTool` with SQL extraction and LLM prompt construction
    - Implement `extractSql(String llmOutput)` — strip markdown fences (` ```sql ... ``` ` and bare ` ``` `), leading/trailing prose, and whitespace; locate first occurrence of a `SELECT`-starting token
    - Implement `buildSqlPrompt(String question, SchemaContext schema, boolean hasRevenue, boolean hasProfit)` — include schema DDL, inject `revenue = (quantity * unit_price)` and `profit = ((quantity * unit_price) - (quantity * cost_price))` derivation instructions when relevant flags are true
    - Return `ToolResult.error("SQL_GENERATION_FAILED")` when no SELECT extractable
    - Return `ToolResult.error("UNSAFE_SQL_GENERATED")` when extracted statement does not start with SELECT
    - Call LLM via HTTP client with 30 s timeout; throw `LlmUnavailableException` on timeout
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 3.6_

  - [ ]* 5.2 Write property test for SQLTool — markdown extraction round-trip (Property 3)
    - **Property 3: SQL markdown extraction round-trip**
    - **Validates: Requirements 3.3**
    - Generate arbitrary valid SELECT SQL strings; wrap in random markdown fence variants (` ```sql`, ` ``` `, no fence), random leading/trailing prose
    - Assert `extractSql(wrapped)` equals original SQL (trimmed)

  - [ ]* 5.3 Write property test for SQLTool — non-SELECT inputs rejected as unsafe (Property 4)
    - **Property 4: Non-SELECT inputs are rejected as unsafe**
    - **Validates: Requirements 3.5**
    - Generate strings beginning with DML/DDL keywords
    - Assert `ToolResult.errorReason == "UNSAFE_SQL_GENERATED"`

  - [ ]* 5.4 Write unit tests for SQLTool
    - Test revenue/profit keyword detection (case-insensitive) injects correct derivation instructions in prompt
    - Test LLM response with no recognizable SQL → `SQL_GENERATION_FAILED`
    - Test LLM timeout → `LlmUnavailableException`
    - _Requirements: 3.4, 3.6_

- [ ] 6. DatabaseTool service
  - [ ] 6.1 Implement `DatabaseTool` with LIMIT appending, timeout, and result mapping
    - Inject the read-only `analytics` DataSource
    - Implement `execute(String sql)`:
      - Unconditionally append `LIMIT 1000` to every SQL string before JDBC execution
      - Set `Statement.setQueryTimeout(30)` on every statement
      - Map each ResultSet row to `Map<String, Object>` using `ResultSetMetaData` column names
      - Set `truncated = true` in `QueryResult` when row count equals 1000
      - On `SQLTimeoutException` → return `QueryResult` with `error = "QUERY_TIMEOUT"`
      - On other SQL exceptions → log at ERROR level with offending SQL; return `QueryResult` with `error = "QUERY_EXECUTION_ERROR"` and sanitized message (no raw DB error text)
    - Implement `AgentTool.execute(ToolInput)` wrapper
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6_

  - [ ]* 6.2 Write property test for DatabaseTool — LIMIT 1000 always appended (Property 10)
    - **Property 10: DatabaseTool always appends LIMIT 1000**
    - **Validates: Requirements 5.4**
    - Generate arbitrary valid SQL SELECT strings; intercept JDBC call (mock DataSource)
    - Assert the SQL string submitted to JDBC ends with `LIMIT 1000` (case-insensitive)

  - [ ]* 6.3 Write property test for DatabaseTool — result set columns fully preserved (Property 11)
    - **Property 11: DatabaseTool result set columns are fully preserved**
    - **Validates: Requirements 5.6**
    - Generate mock ResultSets with arbitrary N columns and M rows
    - Assert returned `QueryResult.rows` has M entries, each map has exactly N keys matching column names with correct values

  - [ ]* 6.4 Write unit tests for DatabaseTool
    - Test timeout scenario: `setQueryTimeout(30)` called; returns `QUERY_TIMEOUT`
    - Test `truncated = true` when result set has exactly 1000 rows
    - Test SQL execution error: logs at ERROR level; returns sanitized `QUERY_EXECUTION_ERROR`
    - _Requirements: 5.2, 5.3, 5.4, 5.5_

- [ ] 7. AnalysisEngine service
  - [ ] 7.1 Implement `AnalysisEngine` with empty-result short-circuit and LLM summary
    - Implement `analyze(List<Map<String, Object>> rows, String question, String sql)`:
      - Return fixed string `"No data was found matching your query criteria."` immediately when `rows` is empty (no LLM call)
      - When rows are non-empty, build LLM prompt including result set, question, SQL; when any numeric column present, instruct LLM to include at least one numeric metric value
      - Enforce ≤ 3-sentence constraint in prompt instructions
      - Call LLM with 30 s timeout; throw `LlmUnavailableException` on timeout/unavailability
    - Implement `AgentTool.execute(ToolInput)` wrapper
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6_

  - [ ]* 7.2 Write property test for AnalysisEngine — summary length constraint (Property 12)
    - **Property 12: AnalysisEngine summary length constraint**
    - **Validates: Requirements 6.2**
    - Generate arbitrary non-empty result sets and question strings (mock LLM to return plausible summaries of varying sentence counts)
    - Assert returned summary contains ≤ 3 sentence-terminal punctuation marks followed by whitespace or end-of-string

  - [ ]* 7.3 Write unit tests for AnalysisEngine
    - Test empty result set → fixed string returned, LLM not called
    - Test LLM timeout → `LlmUnavailableException` thrown
    - Test prompt with numeric column → numeric metric instruction present
    - _Requirements: 6.3, 6.4, 6.6_

- [ ] 8. VisualizationPlanner service
  - [ ] 8.1 Implement `VisualizationPlanner` with priority-ordered chart type rules
    - Implement `plan(List<Map<String, Object>> rows, String question)`:
      - Detect time-dimension columns: name contains `month`, `year`, `quarter` (case-insensitive) or value type is `java.sql.Date`/`java.time.LocalDate`/`java.time.LocalDateTime`
      - Apply rules in priority order: `line` → `scatter` → `pie` → `bar` → default `bar`
      - Rule 1 (`line`): ≥1 time-dimension column AND ≥1 numeric column
      - Rule 2 (`scatter`): exactly 2 numeric columns AND no time-dimension
      - Rule 3 (`pie`): exactly 1 categorical + 1 numeric, 2–6 rows, no time-dimension, not exactly 2 numerics
      - Rule 4 (`bar`): ≥1 categorical + ≥1 numeric, no higher rule
      - Default: `bar`
      - Resolve `xAxis` and `yAxis` from result set metadata; return `ToolResult.error("AXIS_RESOLUTION_FAILED")` if unresolvable
    - Implement `AgentTool.execute(ToolInput)` wrapper
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5, 7.6, 7.7, 7.8, 7.9_

  - [ ]* 8.2 Write property test for VisualizationPlanner — always valid chart type (Property 13)
    - **Property 13: VisualizationPlanner always returns a valid chart type**
    - **Validates: Requirements 7.1**
    - Generate arbitrary non-empty result sets (varying column types and row counts)
    - Assert `VisualizationPlan.chartType` is one of `"bar"`, `"line"`, `"pie"`, `"scatter"`

  - [ ]* 8.3 Write property test for VisualizationPlanner — time-dimension produces line (Property 14)
    - **Property 14: Time-dimension result sets produce line charts**
    - **Validates: Requirements 7.3**
    - Generate result sets with at least one time-named/typed column and at least one numeric column
    - Assert `chartType == "line"`

  - [ ]* 8.4 Write property test for VisualizationPlanner — dual-numeric produces scatter (Property 15)
    - **Property 15: Dual-numeric result sets produce scatter charts**
    - **Validates: Requirements 7.4**
    - Generate result sets with exactly 2 numeric columns and no time-dimension column
    - Assert `chartType == "scatter"`

  - [ ]* 8.5 Write property test for VisualizationPlanner — small categorical-numeric produces pie (Property 16)
    - **Property 16: Small categorical-numeric result sets produce pie charts**
    - **Validates: Requirements 7.5**
    - Generate result sets with exactly 1 categorical + 1 numeric column, 2–6 rows, no time-dimension
    - Assert `chartType == "pie"`

  - [ ]* 8.6 Write property test for VisualizationPlanner — axis columns are valid keys (Property 17)
    - **Property 17: VisualizationPlanner axis columns are valid result set keys**
    - **Validates: Requirements 7.7**
    - Generate arbitrary non-empty result sets that produce a successful plan
    - Assert `xAxis` and `yAxis` values both appear as keys in `rows.get(0)`

  - [ ]* 8.7 Write unit tests for VisualizationPlanner
    - Test each chart-type rule in isolation with boundary conditions
    - Test single-column result set → default `bar`
    - Test axis resolution failure → `AXIS_RESOLUTION_FAILED` returned
    - _Requirements: 7.1–7.9_

- [ ] 9. ConversationContextStore service
  - [ ] 9.1 Implement `ConversationContextStore` with TTL eviction and session lifecycle
    - Back with `ConcurrentHashMap<String, ConversationContext>`
    - `getOrCreate(String sessionId)` — create new context with `UUID.randomUUID().toString()` if null/absent
    - `get(String sessionId)` — throw `SessionNotFoundException` for unknown/evicted sessions
    - `save(String sessionId, ConversationContext)` — update map and reset `lastActivityTime`
    - `ConversationContext` holds `Deque<TurnPair>` (max 5, evict oldest on overflow) and `lastActivityTime`
    - `@Scheduled(fixedDelay = 60_000)` eviction task: remove sessions with `lastActivityTime` older than 30 minutes
    - `getRecentTurns(int max)` returns up to 5 most recent `TurnPair` entries
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 9.6, 9.7_

  - [ ]* 9.2 Write unit tests for ConversationContextStore
    - Test TTL eviction: sessions idle > 30 min are removed by the scheduled task
    - Test max-5-turn cap: 6th turn evicts oldest
    - Test `get()` on unknown session throws `SessionNotFoundException`
    - Test `getOrCreate()` with null sessionId creates new UUID-keyed context
    - _Requirements: 9.3, 9.4, 9.6, 9.7_

- [ ] 10. Checkpoint — core services complete
  - Ensure all unit and property tests for QueryValidator, SQLTool, DatabaseTool, AnalysisEngine, VisualizationPlanner, SchemaInspector, and ConversationContextStore pass. Ask the user if any clarification is needed before proceeding.

- [ ] 11. GlobalExceptionHandler and error response wiring
  - [ ] 11.1 Implement `GlobalExceptionHandler` with full exception-to-status mapping
    - Annotate with `@RestControllerAdvice`
    - Map `MethodArgumentNotValidException` → HTTP 400 with field-level message
    - Map `SessionNotFoundException` → HTTP 400 with `"Session not found or has expired."`
    - Map `SqlGenerationException` → HTTP 422
    - Map `ValidationFailureException` → HTTP 422 with `detail` field containing the reason code
    - Map `AxisResolutionException` → HTTP 422
    - Map `LlmUnavailableException` → HTTP 503
    - Map `DatabaseUnavailableException` / `SchemaUnavailableException` → HTTP 503
    - Catch-all `Exception` → HTTP 500; generate UUID `errorId`; log full stack trace at ERROR level with same UUID; return generic `message` only
    - Ensure no stack traces, exception class names, DB error codes, or raw exception messages appear in any response body
    - Set `Content-Type: application/json` on all error responses
    - _Requirements: 8.3, 8.4, 8.5, 8.6, 8.7, 8.8, 14.1, 14.2, 14.3, 14.4_

  - [ ]* 11.2 Write unit tests for GlobalExceptionHandler
    - Test each exception type produces the correct HTTP status, `message`, and optional `detail`/`errorId`
    - Test catch-all handler: `errorId` is valid UUID, logged with same UUID, no stack trace in body
    - Test no sensitive data in response (no `at com.`, no `Caused by:`)
    - _Requirements: 14.1, 14.2, 14.3, 14.4_

- [ ] 12. Agent orchestration loop
  - [ ] 12.1 Implement `Agent` with tool-calling loop and tool registry
    - Build tool registry as `Map<String, AgentTool>` populated by Spring injection of all `AgentTool` beans
    - Implement the tool-calling loop:
      1. Load or create `ConversationContext` via `ConversationContextStore`
      2. Fetch schema via `SchemaInspector`
      3. Build initial LLM messages including schema DDL, question, and up to 5 prior turns
      4. Loop up to 10 tool invocations: call LLM, dispatch tool by name, append tool result to messages
      5. When LLM returns a final answer (no tool call), break loop and build `AnalysisResponse`
      6. When loop limit reached, return partial `AnalysisResponse` with `incomplete = true`
    - On successful completion, call `ConversationContextStore.save()` to persist the new turn
    - Do NOT update `ConversationContext` on failed requests (requirement 1.6)
    - _Requirements: 1.2, 1.6, 9.2, 9.5, 9.6, 9.8, 15.1, 15.2, 15.4, 15.5_

  - [ ]* 12.2 Write property test for Agent — tool-call loop limit (Property 22)
    - **Property 22: Agent tool-call loop never exceeds 10 invocations**
    - **Validates: Requirements 15.4**
    - Mock LLM to always return a tool-call response; count dispatched tool invocations
    - Assert loop halts after exactly 10 invocations; `incomplete = true` in response

  - [ ]* 12.3 Write property test for Agent — LLM prompt includes at most 5 turns (Property 21)
    - **Property 21: LLM prompt includes at most 5 conversation turns**
    - **Validates: Requirements 9.6**
    - Generate `ConversationContext` with N turns (N ranging 0–20); capture LLM prompt payload
    - Assert prompt contains at most `min(N, 5)` turn entries

  - [ ]* 12.4 Write unit tests for Agent
    - Test happy-path single tool-call cycle → `AnalysisResponse` with all fields populated
    - Test LLM unavailability on first call → `LlmUnavailableException` propagated; context not updated
    - Test unknown sessionId → `SessionNotFoundException` propagated
    - Test `incomplete = true` returned when tool-call limit is reached
    - _Requirements: 1.6, 9.3, 15.4, 15.5_

- [ ] 13. AnalysisController REST endpoint
  - [ ] 13.1 Implement `AnalysisController` with input validation and Agent delegation
    - Implement `POST /api/analysis` with `@Valid @RequestBody AnalysisRequest`
    - Delegate to `Agent.analyze(request)` and return `ResponseEntity<AnalysisResponse>` with HTTP 200
    - Rely on `GlobalExceptionHandler` for all non-200 responses; do not catch exceptions in the controller
    - Ensure `sessionId` is echoed in every `AnalysisResponse` (requirement 9.8)
    - _Requirements: 1.1, 1.3, 1.4, 1.5, 8.1, 8.2, 8.8, 9.1, 9.8_

  - [ ]* 13.2 Write property test for AnalysisController — question length boundary enforcement (Property 1)
    - **Property 1: Question length boundary enforcement**
    - **Validates: Requirements 1.1, 1.3, 1.4**
    - Use `@WebMvcTest` with mocked Agent; generate strings of length 0, 1, 500, 1000, 1001, and arbitrary lengths
    - Assert HTTP 200 for trimmed length 1–1000; HTTP 400 for trimmed-empty or length > 1000

  - [ ]* 13.3 Write property test for AnalysisController — whitespace-only questions rejected (Property 2)
    - **Property 2: Whitespace-only questions are rejected**
    - **Validates: Requirements 1.3**
    - Generate strings composed solely of whitespace characters
    - Assert HTTP 400 with error message explicitly stating the input was empty

  - [ ]* 13.4 Write property test for AnalysisController — no sensitive data in responses (Property 19)
    - **Property 19: No sensitive data in any API response body**
    - **Validates: Requirements 8.7, 13.3, 14.4**
    - Generate requests that trigger each error path; serialize response bodies
    - Assert no LLM API key, no `at com.`, no `Caused by:`, no `java.`, no connection string in any response

  - [ ]* 13.5 Write property test for AnalysisController — new requests get valid UUID sessionId (Property 20)
    - **Property 20: New requests without sessionId always receive a valid UUID**
    - **Validates: Requirements 9.4**
    - Submit valid requests with no `sessionId`; capture `AnalysisResponse.sessionId`
    - Assert value matches UUID v4 pattern `[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}`

  - [ ]* 13.6 Write property test for AnalysisResponse completeness (Property 18)
    - **Property 18: AnalysisResponse always contains all required fields**
    - **Validates: Requirements 8.1, 9.8**
    - For any successful analysis request, assert `question`, `sql`, `chartType`, `xAxis`, `yAxis`, `data`, `summary`, and `sessionId` are all non-null in the response

  - [ ]* 13.7 Write property test for multi-step steps field consistency (Property 23)
    - **Property 23: Multi-step analysis steps field is consistent with executed queries**
    - **Validates: Requirements 15.3**
    - Mock Agent to return N steps (1 ≤ N ≤ 10); assert `AnalysisResponse.steps` has exactly N entries, each with non-null `sql` and `data`

- [ ] 14. Checkpoint — backend complete
  - Ensure all backend unit tests, property tests, and integration tests pass. Verify the application starts successfully and `/api/analysis` responds correctly with a mock LLM. Ask the user if any clarification is needed.

- [ ] 15. Angular project scaffolding and shared types
  - [ ] 15.1 Initialize Angular project structure with required libraries
    - Create Angular 17+ workspace (standalone components preferred); install `chart.js` and `ng2-charts` (or `chart.js` directly), `ngx-highlightjs` (or `highlight.js`), `fast-check` (for PBT)
    - Create module/feature structure: `core/`, `features/chat/`, `shared/components/`
    - Define TypeScript interfaces: `AnalysisRequest`, `AnalysisResponse`, `StepResult`, `ErrorResponse`
    - Create Angular environment files with `apiBaseUrl` pointing to `http://localhost:8080` for dev
    - _Requirements: 10.1, 11.1, 13.4, 13.5_

  - [ ] 15.2 Implement `AnalysisService` and `SessionService`
    - `AnalysisService`: wraps `HttpClient.post<AnalysisResponse>('/api/analysis', body)`; accepts `AnalysisRequest`; propagates errors as typed `ErrorResponse`
    - `SessionService`: holds current `sessionId` as a `BehaviorSubject<string | null>`; updates on each successful `AnalysisResponse`
    - Add Angular `HttpInterceptor` to catch non-2xx responses, transform to user-friendly messages, and suppress raw JSON/stack traces from display
    - _Requirements: 10.7, 10.8, 13.4, 13.5_

- [ ] 16. Core Angular UI components
  - [ ] 16.1 Implement `AppComponent` with sidebar navigation
    - Render sidebar with navigation items: "New Analysis", "Previous Questions", "Dashboard"
    - Render main content area hosting the conversation thread and question input
    - _Requirements: 10.1, 10.2_

  - [ ] 16.2 Implement `QuestionInputComponent` with submission and disable logic
    - Render question textarea and send button
    - On send button click or Enter key: emit question only if trimmed value is non-empty; set `isLoading = true`
    - While `isLoading` is true: disable send button, set textarea to `readonly`
    - Reset `isLoading = false` when `AnalysisResponse` or error is received
    - _Requirements: 10.3, 10.4_

  - [ ]* 16.3 Write unit tests for `QuestionInputComponent`
    - Test disabled state during in-flight request: send button disabled, textarea readonly
    - Test Enter-key submission fires only when trimmed value is non-empty
    - Test whitespace-only input does not trigger submission
    - _Requirements: 10.3, 10.4_

- [ ] 17. `SqlViewerComponent` — syntax-highlighted SQL display
  - [ ] 17.1 Implement `SqlViewerComponent` using `ngx-highlightjs`
    - Accept `sql: string` as `@Input()`
    - Render inside a styled `<pre><code>` block with SQL syntax highlighting via `ngx-highlightjs` or `highlight.js`
    - _Requirements: 10.5_

- [ ] 18. `DataTableComponent` — paginated data table with number formatting
  - [ ] 18.1 Implement `DataTableComponent` with 50-row cap and number formatting
    - Accept `data: Record<string, unknown>[]` as `@Input()`
    - Derive column headers from `data[0]` keys preserving original key order
    - Render at most 50 rows; display `"Showing 50 of N rows"` when `data.length > 50`
    - Format integers with thousand-separator commas using `Intl.NumberFormat` (no decimals)
    - Format decimals with thousand-separator commas and exactly 2 decimal places
    - _Requirements: 12.1, 12.2, 12.3, 12.4, 12.5, 12.6_

  - [ ]* 18.2 Write property test for `DataTableComponent` — row limit with count message (Property 26)
    - **Property 26: Data table renders at most 50 rows with correct count message**
    - **Validates: Requirements 12.3, 12.4**
    - Use fast-check; generate arrays of length N (0–200); render component; assert exactly `min(N, 50)` rows rendered; assert "Showing 50 of N rows" present iff N > 50

  - [ ]* 18.3 Write property test for `DataTableComponent` — column headers match data keys (Property 27)
    - **Property 27: Data table column headers match data object keys**
    - **Validates: Requirements 12.2**
    - Generate arbitrary objects with random keys; assert rendered `<th>` elements match `Object.keys(data[0])` in order

  - [ ]* 18.4 Write property test for `DataTableComponent` — number formatting (Property 28)
    - **Property 28: Numeric values are correctly formatted with thousand separators**
    - **Validates: Requirements 12.5, 12.6**
    - Generate arbitrary integers and decimals; assert integer cells render with commas only; decimal cells render with commas and exactly 2 decimal places

  - [ ]* 18.5 Write unit tests for `DataTableComponent`
    - Test with exactly 50 rows: no truncation message shown
    - Test with 51 rows: truncation message shows "Showing 50 of 51 rows"
    - Test with empty data: table not rendered
    - _Requirements: 12.3, 12.4_

- [ ] 19. `ChartComponent` — dynamic Chart.js chart rendering
  - [ ] 19.1 Implement `ChartComponent` with all four chart types and fallback messages
    - Accept `chartType: string`, `xAxis: string`, `yAxis: string`, `data: Record<string, unknown>[]` as `@Input()` values
    - When `data` is empty: display `"No data available to visualize"` in place of the canvas (no Chart.js instantiation)
    - When `chartType` is not one of `bar`, `line`, `pie`, `scatter`: display `"Chart type not supported"` (do not render canvas)
    - For each valid chart type:
      - `bar`: category labels from `xAxis` column values; heights from `yAxis` column values
      - `line`: x-axis from `xAxis` column values; y-axis from `yAxis` column values
      - `pie`: labels from `xAxis` column values; sizes from `yAxis` column values
      - `scatter`: x from `xAxis` column values; y from `yAxis` column values
    - Display axis labels using exact `xAxis` and `yAxis` field strings
    - _Requirements: 11.1, 11.2, 11.3, 11.4, 11.5, 11.6, 11.7, 11.8_

  - [ ]* 19.2 Write property test for `ChartComponent` — unsupported chart types show fallback (Property 25)
    - **Property 25: Unsupported chart types always show fallback message**
    - **Validates: Requirements 11.8**
    - Use fast-check; generate arbitrary strings that are not `bar`, `line`, `pie`, or `scatter`
    - Assert `"Chart type not supported"` is rendered and no `<canvas>` element is present

  - [ ]* 19.3 Write property test for `ChartComponent` — axis labels match AnalysisResponse fields (Property 24)
    - **Property 24: Chart axis labels match AnalysisResponse axis fields**
    - **Validates: Requirements 11.6**
    - Generate arbitrary non-empty `xAxis` and `yAxis` strings with non-empty data; render component
    - Assert rendered axis label text content equals the provided `xAxis` and `yAxis` strings exactly

  - [ ]* 19.4 Write unit tests for `ChartComponent`
    - Test each valid chart type renders the correct Chart.js type
    - Test empty data renders `"No data available to visualize"` with no canvas
    - Test unsupported `chartType` renders `"Chart type not supported"` with no canvas
    - _Requirements: 11.2–11.8_

- [ ] 20. `MessageComponent` and `ChatComponent` — conversation thread assembly
  - [ ] 20.1 Implement `MessageComponent` to assemble a full AI response bubble
    - Accept `response: AnalysisResponse` as `@Input()`
    - When `data` is non-empty: render summary text, `SqlViewerComponent`, `DataTableComponent`, `ChartComponent`
    - When `data` is empty: render summary text, `SqlViewerComponent`; display `"No data available to visualize"` in chart area and omit table
    - Display error messages (from interceptor) within the conversation thread; never show raw JSON, SQL, or stack traces
    - _Requirements: 10.5, 10.6, 10.7_

  - [ ] 20.2 Implement `ChatComponent` as conversation thread container
    - Maintain ordered list of `AnalysisResponse | ErrorMessage` objects for the session
    - Show loading indicator inside conversation thread while request is in-flight
    - On `AnalysisResponse` received: add to list, pass `sessionId` to `SessionService`, clear loading indicator
    - On error: add user-friendly error entry to list, clear loading indicator
    - _Requirements: 10.3, 10.5, 10.6, 10.7_

- [ ] 21. Checkpoint — frontend complete
  - Ensure all Angular unit tests and property-based tests (fast-check) pass. Verify the application compiles without errors and components render correctly. Ask the user if any clarification is needed.

- [ ] 22. Integration wiring and end-to-end validation
  - [ ] 22.1 Wire Spring Boot CORS, JSON serialization, and OpenAPI configuration
    - Configure `@CrossOrigin` or `WebMvcConfigurer` CORS for `http://localhost:4200` and the production domain
    - Configure `Jackson` to omit `null` fields in `ErrorResponse` (`@JsonInclude(NON_NULL)`) and to serialize `List<Map<String, Object>>` faithfully
    - Verify `Content-Type: application/json` is set on all responses
    - _Requirements: 8.8_

  - [ ] 22.2 Write integration test: happy-path analysis request end-to-end
    - Use Testcontainers MySQL with the analytics schema and seed data
    - Mock LLM HTTP calls; invoke `POST /api/analysis` with a valid question and no `sessionId`
    - Assert HTTP 200, all required `AnalysisResponse` fields are non-null, `sessionId` is a valid UUID
    - Assert subsequent request with returned `sessionId` completes successfully (session follow-up)
    - _Requirements: 8.1, 8.2, 9.4, 9.8_

  - [ ]* 22.3 Write integration test: schema load on startup (Testcontainers)
    - Start Testcontainers MySQL with `customers`, `products`, `orders` tables
    - Assert `SchemaInspector` cache is populated at `ApplicationReadyEvent`
    - Assert `getSchema()` returns correct table/column metadata without a DB query (cache hit)
    - _Requirements: 2.1, 2.2, 2.3_

  - [ ]* 22.4 Write integration test: CORS header validation
    - Send a preflight `OPTIONS` request from `http://localhost:4200` origin to `/api/analysis`
    - Assert `Access-Control-Allow-Origin` header is present and allows the Angular dev origin
    - _Requirements: 8.8_

- [ ] 23. Final checkpoint — full system
  - Run the complete test suite (backend JUnit/jqwik, frontend Jest/fast-check, integration Testcontainers). Fix any failures. Confirm all requirements are satisfied. Ask the user if any clarification is needed before handoff.

---

## Notes

- Tasks marked with `*` are optional and can be skipped for a faster MVP, but are highly recommended for production readiness.
- All property-based tests must be tagged with `// Feature: ai-data-analyst-agent, Property N: <property text>` as specified in the design.
- Backend property tests use **jqwik** with `@Property(tries = 100)` minimum.
- Frontend property tests use **fast-check** with `fc.assert(fc.property(...), { numRuns: 100 })` minimum.
- Each task references specific requirements for full traceability.
- The `application-secrets.yml` file containing the LLM API key MUST be listed in `.gitignore` before first commit.
- The read-only MySQL user for the `DatabaseTool` DataSource must be provisioned separately; document the required `GRANT SELECT ON analytics.* TO 'analyst'@'%'` statement in README.
- Checkpoints ensure incremental validation and surface integration issues early.

---

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "1.2"] },
    { "id": 1, "tasks": ["2.1", "3.1", "15.1"] },
    { "id": 2, "tasks": ["3.2", "4.1", "9.1", "15.2"] },
    { "id": 3, "tasks": ["4.2", "4.3", "4.4", "4.5", "4.6", "5.1", "9.2"] },
    { "id": 4, "tasks": ["5.2", "5.3", "5.4", "6.1"] },
    { "id": 5, "tasks": ["6.2", "6.3", "6.4", "7.1"] },
    { "id": 6, "tasks": ["7.2", "7.3", "8.1"] },
    { "id": 7, "tasks": ["8.2", "8.3", "8.4", "8.5", "8.6", "8.7", "11.1"] },
    { "id": 8, "tasks": ["11.2", "12.1", "16.1", "16.2", "17.1", "18.1"] },
    { "id": 9, "tasks": ["12.2", "12.3", "12.4", "13.1", "16.3", "18.2", "18.3", "18.4", "18.5", "19.1"] },
    { "id": 10, "tasks": ["13.2", "13.3", "13.4", "13.5", "13.6", "13.7", "19.2", "19.3", "19.4", "20.1"] },
    { "id": 11, "tasks": ["20.2"] },
    { "id": 12, "tasks": ["22.1"] },
    { "id": 13, "tasks": ["22.2", "22.3", "22.4"] }
  ]
}
```
