package com.dataanalyst.config;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.zaxxer.hikari.HikariDataSource;

/**
 * Configures dual DataSources for the AI Data Analyst Agent:
 *
 * <ol>
 *   <li><b>Default DataSource (primary)</b> — used by Spring Data JDBC and the
 *       {@code SchemaInspector} for {@code information_schema} queries. Configured
 *       from {@code spring.datasource.*} properties (typically an admin-level user
 *       with read access to {@code information_schema}).</li>
 *   <li><b>Analytics DataSource</b> — named {@code analyticsDataSource}; used
 *       exclusively by the {@code DatabaseTool} to execute agent-generated SELECT
 *       queries. Configured from {@code analytics.datasource.*} properties and
 *       MUST be backed by a MySQL user that holds only {@code SELECT} privileges
 *       on the analytics schema (requirement 5.1).</li>
 * </ol>
 *
 * <p>Spring Boot's auto-configuration wires the {@code @Primary} bean as the
 * default JDBC connection, while the analytics bean is injected by qualifier
 * wherever a restricted connection is required.
 */
@Configuration
public class DataSourceConfig {

    // -------------------------------------------------------------------------
    // Primary DataSource — schema inspection + Spring Data JDBC
    // -------------------------------------------------------------------------

    /**
     * Properties for the primary DataSource, bound from the standard
     * {@code spring.datasource.*} namespace by Spring Boot auto-configuration.
     * Declared here so it can be referenced by {@link #defaultDataSource()}.
     */
    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties defaultDataSourceProperties() {
        return new DataSourceProperties();
    }

    /**
     * Primary DataSource used by Spring Data JDBC and the SchemaInspector.
     * Marked {@code @Primary} so that Spring Boot auto-configuration and any
     * bean that injects {@code DataSource} without a qualifier receive this one.
     *
     * @return a fully configured HikariCP DataSource
     */
    @Bean
    @Primary
    public DataSource defaultDataSource() {
        return defaultDataSourceProperties()
                .initializeDataSourceBuilder()
                .build();
    }

    // -------------------------------------------------------------------------
    // Analytics DataSource — read-only SELECT-only MySQL user (requirement 5.1)
    // -------------------------------------------------------------------------

    /**
     * Properties for the analytics (read-only) DataSource, bound from the
     * {@code analytics.datasource.*} namespace in {@code application.yml}.
     */
    @Bean
    @ConfigurationProperties("analytics.datasource")
    public DataSourceProperties analyticsDataSourceProperties() {
        return new DataSourceProperties();
    }

    /**
     * Read-only DataSource used exclusively by the {@code DatabaseTool}.
     *
     * <p>The underlying MySQL user MUST have only {@code SELECT} privileges on
     * the analytics schema, ensuring that even if SQL validation is bypassed,
     * the database cannot be modified through this connection (requirement 5.1).
     *
     * <p>Hikari pool settings are kept small because this pool is used only for
     * agent-generated queries; pool size can be tuned via
     * {@code analytics.datasource.hikari.*} properties.
     *
     * @return a fully configured read-only HikariCP DataSource
     */
    @Bean("analyticsDataSource")
    public DataSource analyticsDataSource() {
        DataSource ds = analyticsDataSourceProperties()
                .initializeDataSourceBuilder()
                .build();

        // If the datasource is Hikari, mark connections as read-only at the
        // pool level as an additional defence-in-depth measure.
        if (ds instanceof HikariDataSource hikari) {
            hikari.setReadOnly(true);
        }

        return ds;
    }
}
