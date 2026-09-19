package com.dataanalyst.domain;

import java.util.List;
import java.util.Map;

/**
 * Snapshot of the database schema as seen by the AI agent.
 *
 * <p>Holds a mapping from table name to the ordered list of {@link ColumnMetadata}
 * entries for that table. The {@link #toPromptString()} method formats the schema
 * as CREATE TABLE-style DDL suitable for inclusion in an LLM prompt.
 *
 * @param tables unmodifiable map of table name → column metadata list
 */
public record SchemaContext(Map<String, List<ColumnMetadata>> tables) {

    /**
     * Formats the schema as CREATE TABLE-style DDL for inclusion in an LLM prompt.
     *
     * <p>Example output for the {@code customers} table:
     * <pre>{@code
     * CREATE TABLE customers (
     *   customer_id int,
     *   customer_name varchar,
     *   city varchar,
     *   segment varchar
     * );
     * }</pre>
     *
     * @return DDL string representing all cached tables
     */
    public String toPromptString() {
        if (tables == null || tables.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        tables.forEach((tableName, columns) -> {
            sb.append("CREATE TABLE ").append(tableName).append(" (\n");
            for (int i = 0; i < columns.size(); i++) {
                ColumnMetadata col = columns.get(i);
                sb.append("  ").append(col.columnName()).append(" ").append(col.dataType());
                if (i < columns.size() - 1) {
                    sb.append(",");
                }
                sb.append("\n");
            }
            sb.append(");\n\n");
        });

        return sb.toString().stripTrailing();
    }
}
