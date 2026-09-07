package com.personal.jobagent.jobs;

import com.personal.jobagent.common.JdbcConversions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC, consistent with the rest of Phase 1's persistence approach.
 * Cursor pagination uses keyset pagination on (first_seen_at, id) rather
 * than OFFSET — correct and stable even if rows are inserted between page
 * requests, unlike an offset-based approach.
 */
@Repository
public class JobRepository {

    private final JdbcTemplate jdbcTemplate;

    public JobRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<JobRecord> ROW_MAPPER = (rs, rowNum) -> new JobRecord(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("source_id"),
            rs.getString("external_id"),
            (UUID) rs.getObject("company_id"),
            rs.getString("company_name_raw"),
            rs.getString("title"),
            rs.getString("location_raw"),
            rs.getString("city"),
            rs.getString("country"),
            rs.getString("remote_type"),
            rs.getString("employment_type"),
            rs.getString("experience_level"),
            rs.getBigDecimal("salary_min"),
            rs.getBigDecimal("salary_max"),
            rs.getString("salary_currency"),
            rs.getString("description_text"),
            JdbcConversions.readStringArray(rs, "skills_extracted"),
            rs.getString("application_url"),
            rs.getString("canonical_url"),
            rs.getTimestamp("posted_at") != null ? rs.getTimestamp("posted_at").toInstant() : null,
            rs.getString("status")
    );

    public record Page(List<JobRecord> items, String nextCursor) {
    }

    public Page findJobs(String status, String query, int limit, String cursor) {
        List<String> conditions = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        if (status != null) {
            conditions.add("status = ?");
            params.add(status);
        }
        if (query != null && !query.isBlank()) {
            conditions.add("search_vector @@ plainto_tsquery('english', ?)");
            params.add(query);
        }
        if (cursor != null && !cursor.isBlank()) {
            String[] decoded = new String(Base64.getDecoder().decode(cursor)).split("\\|", 2);
            conditions.add("(first_seen_at, id) < (?::timestamptz, ?::uuid)");
            params.add(decoded[0]);
            params.add(decoded[1]);
        }

        String where = conditions.isEmpty() ? "" : "where " + String.join(" and ", conditions);
        String sql = "select * from jobs " + where + " order by first_seen_at desc, id desc limit ?";
        params.add(limit + 1); // fetch one extra to know if there's a next page

        List<JobRecord> rows = jdbcTemplate.query(sql, ROW_MAPPER, params.toArray());

        boolean hasMore = rows.size() > limit;
        List<JobRecord> page = hasMore ? rows.subList(0, limit) : rows;
        String nextCursor = null;
        if (hasMore) {
            JobRecord last = page.get(page.size() - 1);
            nextCursor = encodeCursor(last.id());
        }

        return new Page(page, nextCursor);
    }

    private String encodeCursor(UUID lastId) {
        String firstSeenAt = jdbcTemplate.queryForObject(
                "select first_seen_at::text from jobs where id = ?", String.class, lastId);
        return Base64.getEncoder().encodeToString((firstSeenAt + "|" + lastId).getBytes());
    }

    public Optional<JobRecord> findById(UUID id) {
        return jdbcTemplate.query("select * from jobs where id = ?", ROW_MAPPER, id)
                .stream().findFirst();
    }
}
