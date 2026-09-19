package com.dataanalyst.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.dataanalyst.domain.ColumnMetadata;
import com.dataanalyst.domain.SchemaContext;
import com.dataanalyst.exception.SchemaUnavailableException;

/**
 * Retrieves and caches the database schema for the three analytics tables
 * ({@code customers}, {@code products}, {@code orders}) at application startup.
 *
 * <h2>Startup behaviour</h2>
 * <p>On {@link ApplicationReadyEvent}, this listener queries
 * {@code information_schema.columns} and stores the results in an in-memory
 * {@link ConcurrentHashMap}. If the database is unreachable the error is logged
 * at ERROR level and the system is marked unhealthy — subsequent calls to
 * {@link #getSchema()} will throw {@link SchemaUnavailableException} until the
 * cache is successfully populated (requirements 2.1, 2.6).
 *
 * <h2>Cache-hit path</h2>
 * <p>When the cache is populated {@link #getSchema()} returns immediately without
 * touching the database (requirement 2.3).
 *
 * <h2>Cache-miss path</h2>
 * <p>When the cache is empty (e.g., a startup failure occurred), {@link #getSchema()}
 * attempts a live query to {@code information_schema}, populates the cache, and
 * returns the result (requirement 2.4).
 */
@Service
public class SchemaInspector implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(SchemaInspector.class);

    /** Tables whose schema the agent is aware of (requirement 2.7). */
    static final List<String> TARGET_TABLES = List.of("customers", "products", "orders");

    /**
     * SQL that fetches column name and data type for the target tables from
     * {@code information_schema}. Uses {@code IN} with positional placeholders so
     * the query works with any JDBC-compliant database (MySQL + H2 in tests).
     */
    private static final String SCHEMA_QUERY =
            "SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE " +
            "FROM information_schema.COLUMNS " +
            "WHERE TABLE_NAME IN (?, ?, ?) " +
            "ORDER BY TABLE_NAME, ORDINAL_POSITION";

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    /** Thread-safe schema cache keyed by table name. */
    private final ConcurrentHashMap<String, List<ColumnMetadata>> schemaCache =
            new ConcurrentHashMap<>();

    /**
     * {@code true} while the cache is fully populated and trusted.
     * Set to {@code false} on connection failure at startup.
     */
    private final AtomicBoolean healthy = new AtomicBoolean(false);

    private final JdbcTemplate jdbcTemplate;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public SchemaInspector(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // -------------------------------------------------------------------------
    // ApplicationListener — startup population
    // -------------------------------------------------------------------------

    /**
     * Called once the application context is fully started. Attempts to populate
     * the schema cache from {@code information_schema}. On success the system is
     * marked healthy; on failure the error is logged at ERROR level and the system
     * is left in an unhealthy state (requirements 2.1, 2.6).
     *
     * @param event the Spring {@link ApplicationReadyEvent}
     */
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        log.info("SchemaInspector: populating schema cache for tables: {}", TARGET_TABLES);
        try {
            populateCache();
            healthy.set(true);
            log.info("SchemaInspector: schema cache populated successfully — {} tables loaded.",
                    schemaCache.size());
        } catch (Exception ex) {
            healthy.set(false);
            log.error(
                "SchemaInspector: STARTUP FAILURE — could not query information_schema. " +
                "The schema cache is empty. All analysis requests will return HTTP 503 " +
                "until the cache is successfully populated. Cause: {}",
                ex.getMessage(), ex
            );
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns the cached {@link SchemaContext}.
     *
     * <ul>
     *   <li>Cache-hit path: returns immediately without querying the database
     *       (requirement 2.3).</li>
     *   <li>Cache-miss path: attempts a live query to {@code information_schema},
     *       populates the cache, and returns the result (requirement 2.4).</li>
     * </ul>
     *
     * @return a {@link SchemaContext} containing metadata for all target tables
     * @throws SchemaUnavailableException if the cache is empty and the database
     *         cannot be reached (requirement 2.6)
     */
    public SchemaContext getSchema() {
        // Cache-hit: return straight away
        if (!schemaCache.isEmpty()) {
            return buildSchemaContext();
        }

        // Cache-miss: try to populate on demand
        log.warn("SchemaInspector: cache is empty on getSchema() call — attempting live query.");
        try {
            populateCache();
            healthy.set(true);
            log.info("SchemaInspector: cache populated on cache-miss path.");
            return buildSchemaContext();
        } catch (Exception ex) {
            healthy.set(false);
            log.error(
                "SchemaInspector: cache-miss live query failed. Schema is unavailable. Cause: {}",
                ex.getMessage(), ex
            );
            throw new SchemaUnavailableException(
                "Schema metadata is currently unavailable. " +
                "The database could not be reached while trying to populate the schema cache.",
                ex
            );
        }
    }

    /**
     * Returns {@code true} if the schema cache was successfully populated at
     * startup (or via the cache-miss path) and has not been cleared since.
     *
     * @return {@code true} when healthy; {@code false} when the last population
     *         attempt failed
     */
    public boolean isHealthy() {
        return healthy.get();
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Executes the {@code information_schema} query and fills {@link #schemaCache}.
     * Replaces any previously cached data atomically on a per-table basis.
     *
     * @throws org.springframework.dao.DataAccessException on JDBC failure
     */
    void populateCache() {
        // Build a temporary map and swap into the cache atomically per-table
        Map<String, List<ColumnMetadata>> temp = new ConcurrentHashMap<>();

        // Pre-seed with empty lists for every target table so that tables with
        // no rows in information_schema (e.g., not yet created) appear as empty.
        for (String table : TARGET_TABLES) {
            temp.put(table, new ArrayList<>());
        }

        jdbcTemplate.query(
            SCHEMA_QUERY,
            ps -> {
                ps.setString(1, TARGET_TABLES.get(0));
                ps.setString(2, TARGET_TABLES.get(1));
                ps.setString(3, TARGET_TABLES.get(2));
            },
            rs -> {
                String tableName  = rs.getString("TABLE_NAME").toLowerCase();
                String columnName = rs.getString("COLUMN_NAME");
                String dataType   = rs.getString("DATA_TYPE");
                temp.computeIfAbsent(tableName, k -> new ArrayList<>())
                    .add(new ColumnMetadata(columnName, dataType));
            }
        );

        // Make each list unmodifiable before publishing
        temp.replaceAll((k, v) -> Collections.unmodifiableList(v));

        // Atomically replace cache contents
        schemaCache.clear();
        schemaCache.putAll(temp);
    }

    /**
     * Builds an immutable {@link SchemaContext} from the current cache state.
     */
    private SchemaContext buildSchemaContext() {
        return new SchemaContext(Collections.unmodifiableMap(new ConcurrentHashMap<>(schemaCache)));
    }
}
