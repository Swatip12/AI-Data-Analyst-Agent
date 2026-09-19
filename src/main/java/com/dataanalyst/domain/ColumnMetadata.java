package com.dataanalyst.domain;

/**
 * Immutable value object representing a single database column's metadata.
 *
 * <p>Instances are retrieved from {@code information_schema.columns} at startup
 * by {@code SchemaInspector} and cached for the lifetime of the application.
 *
 * @param columnName the column name as returned by {@code information_schema}
 * @param dataType   the column data type as returned by {@code information_schema}
 *                   (e.g., {@code "varchar"}, {@code "int"}, {@code "date"})
 */
public record ColumnMetadata(String columnName, String dataType) {
}
