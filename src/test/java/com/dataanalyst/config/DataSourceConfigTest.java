package com.dataanalyst.config;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Tests for {@link DataSourceConfig} (requirement 5.1).
 *
 * <p>Verifies that:
 * <ul>
 *   <li>The primary DataSource bean is present and connectable.</li>
 *   <li>The {@code analyticsDataSource} bean is present, distinct from the
 *       primary, and connectable.</li>
 * </ul>
 *
 * <p>In the test environment both sources point to the same H2 in-memory
 * database (see {@code src/test/resources/application.yml}), so the
 * structural assertions are what matter here.
 */
@SpringBootTest
class DataSourceConfigTest {

    @Autowired
    private DataSource defaultDataSource;

    @Autowired
    @Qualifier("analyticsDataSource")
    private DataSource analyticsDataSource;

    @Test
    void defaultDataSourceBean_shouldBePresent() {
        assertThat(defaultDataSource).isNotNull();
    }

    @Test
    void analyticsDataSourceBean_shouldBePresent() {
        assertThat(analyticsDataSource).isNotNull();
    }

    @Test
    void analyticsDataSource_shouldBeDistinctFromDefaultDataSource() {
        // The two beans should be separate instances even if they resolve
        // to the same underlying DB in the test environment.
        assertThat(analyticsDataSource).isNotSameAs(defaultDataSource);
    }

    @Test
    void defaultDataSource_shouldBeConnectable() throws Exception {
        try (var conn = defaultDataSource.getConnection()) {
            assertThat(conn.isValid(5)).isTrue();
        }
    }

    @Test
    void analyticsDataSource_shouldBeConnectable() throws Exception {
        try (var conn = analyticsDataSource.getConnection()) {
            assertThat(conn.isValid(5)).isTrue();
        }
    }
}
