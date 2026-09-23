package com.personal.jobagent.common;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Array;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Small conversion helpers backing the JDBC-not-JPA persistence decision
 * (see PHASE1-BLUEPRINT.md §2a) — every jsonb/text[] read or write in the
 * profile/preferences/audit/events packages goes through these rather than
 * each repository reinventing the same getArray()/getObject(jsonb) dance.
 * The underlying patterns (array read/write, jsonb read/write) were
 * verified against live Postgres during P1-b/d implementation.
 */
public final class JdbcConversions {

    private JdbcConversions() {
    }

    @SuppressWarnings("unchecked")
    public static List<String> readStringArray(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        if (array == null) {
            return List.of();
        }
        String[] values = (String[]) array.getArray();
        return List.of(values);
    }

    public static Array toSqlArray(Connection connection, List<String> values) throws SQLException {
        return connection.createArrayOf("text", values.toArray());
    }

    public static Map<String, Object> readJsonMap(ResultSet rs, String column, ObjectMapper objectMapper) throws SQLException {
        String json = rs.getString(column);
        if (json == null) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            throw new SQLException("Failed to parse jsonb column " + column + " as Map", e);
        }
    }

    /**
     * Parses a json/jsonb column into whatever shape it actually holds — object, array or
     * scalar. Use this instead of {@link #readJsonMap} when the column is not guaranteed to be
     * a JSON object (benchmark {@code metrics}, event {@code payload}, …); it returns
     * {@code null} for a SQL NULL rather than masking it as an empty object.
     */
    public static Object readJson(ResultSet rs, String column, ObjectMapper objectMapper) throws SQLException {
        String json = rs.getString(column);
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            throw new SQLException("Failed to parse jsonb column " + column + " as JSON", e);
        }
    }

    /**
     * Normalises a raw row from {@code JdbcTemplate#queryForList}/{@code queryForMap} so Jackson
     * can render it. pgjdbc hands back a {@code java.sql.Array} (PgArray) for text[]/uuid[]/…,
     * and Jackson bean-serialises that driver class — including {@code PgArray#getResultSet()},
     * which builds a live JDBC ResultSet out of driver internals — so the response fails with a
     * JsonMappingException (HTTP 500). Array values become plain {@code List}s instead.
     *
     * <p>json/jsonb columns are deliberately NOT handled here: select them with an explicit
     * {@code ::text} cast and parse with {@link #readJson} (the convention the audit log and
     * outbox queries already use) so the payload keeps its real structure instead of the
     * driver's type/value wrapper object.
     */
    public static Map<String, Object> jsonSafeRow(Map<String, Object> row) {
        row.replaceAll((column, value) -> value instanceof Array array ? toList(array) : value);
        return row;
    }

    /** Row-wise {@link #jsonSafeRow} for a whole {@code queryForList} result. */
    public static List<Map<String, Object>> jsonSafeRows(List<Map<String, Object>> rows) {
        rows.forEach(JdbcConversions::jsonSafeRow);
        return rows;
    }

    private static List<Object> toList(Array array) {
        try {
            Object raw = array.getArray();
            return raw instanceof Object[] elements ? new ArrayList<>(Arrays.asList(elements)) : List.of();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read SQL array value", e);
        }
    }

    public static String toJson(Object value, ObjectMapper objectMapper) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize value to JSON", e);
        }
    }
}
