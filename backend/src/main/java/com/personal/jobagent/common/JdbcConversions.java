package com.personal.jobagent.common;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Array;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
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
