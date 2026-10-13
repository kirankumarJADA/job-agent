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
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public JobRepository(JdbcTemplate jdbcTemplate, com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Columns are listed explicitly rather than {@code select *}.
     *
     * <p>The shared {@code jobs} row has historically carried columns that were
     * NOT shared — V007 put the per-candidate match decision (match_score,
     * match_recommendation, match_breakdown) here — and a {@code select *} is how
     * such a column silently reaches every authenticated user the next time
     * someone adds one. V022 moved that data to {@code job_matches} and dropped
     * the columns; this list is the second half of that fix.
     */
    private static final String JOB_COLUMNS =
            "id, source_id, external_id, company_id, company_name_raw, title, location_raw, city, country, "
                    + "remote_type, employment_type, experience_level, salary_min, salary_max, salary_currency, "
                    + "description_text, skills_extracted, application_url, canonical_url, posted_at, status";

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
        String sql = "select " + JOB_COLUMNS + " from jobs " + where
                + " order by first_seen_at desc, id desc limit ?";
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
        return jdbcTemplate.query("select " + JOB_COLUMNS + " from jobs where id = ?", ROW_MAPPER, id)
                .stream().findFirst();
    }

    /**
     * A posting is stale when no discovery run has seen it for this many days.
     * Same threshold the review queue uses to refuse approval
     * (ApplicationDecisionService: {@code last_seen_at < now() - interval '30 days'}),
     * so the FIND feed and the APPLY gate never disagree about freshness.
     */
    public static final int STALE_AFTER_DAYS = 30;

    /**
     * One row of the FIND feed: the shared catalogue posting, its freshness and
     * source, and ONLY the caller's own match result. The match columns come
     * from a left join on {@code job_matches} constrained to the caller's
     * profile, so another candidate's score can never appear here; a null
     * profile simply yields no match.
     */
    public record FeedItem(JobRecord job, java.time.Instant firstSeenAt, java.time.Instant lastSeenAt,
                           boolean stale, boolean removed, String sourceName, String sourceKind,
                           Integer matchScore, String matchRecommendation, java.time.Instant matchScoredAt) {
    }

    public record FeedPage(List<FeedItem> items, String nextCursor) {
    }

    private static final String FEED_SELECT = "select "
            + JOB_COLUMNS.replaceAll("(^|, )(\\w+)", "$1j.$2")
            + ", j.first_seen_at, j.last_seen_at, (j.deleted_at is not null) as removed"
            + ", (j.last_seen_at < now() - interval '" + STALE_AFTER_DAYS + " days') as stale"
            + ", s.display_name as source_name, s.kind as source_kind"
            + ", m.score as match_score, m.recommendation as match_recommendation, m.scored_at as match_scored_at"
            + " from jobs j"
            + " left join job_sources s on s.id = j.source_id"
            + " left join job_matches m on m.job_id = j.id and m.profile_id = ?::uuid";

    private static final RowMapper<FeedItem> FEED_MAPPER = (rs, rowNum) -> new FeedItem(
            ROW_MAPPER.mapRow(rs, rowNum),
            rs.getTimestamp("first_seen_at").toInstant(),
            rs.getTimestamp("last_seen_at").toInstant(),
            rs.getBoolean("stale"),
            rs.getBoolean("removed"),
            rs.getString("source_name"),
            rs.getString("source_kind"),
            (Integer) rs.getObject("match_score"),
            rs.getString("match_recommendation"),
            rs.getTimestamp("match_scored_at") == null ? null : rs.getTimestamp("match_scored_at").toInstant());

    /**
     * The FIND feed. Same filters and keyset pagination as {@link #findJobs}
     * (status, full-text {@code q}, cursor on first_seen_at/id) but soft-deleted
     * postings are excluded, because they can no longer be applied to and the
     * review queue already hides them.
     */
    public FeedPage findFeed(UUID profileId, String status, String query, int limit, String cursor) {
        List<String> conditions = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        params.add(profileId);
        conditions.add("j.deleted_at is null");
        if (status != null) {
            conditions.add("j.status = ?");
            params.add(status);
        }
        if (query != null && !query.isBlank()) {
            conditions.add("j.search_vector @@ plainto_tsquery('english', ?)");
            params.add(query);
        }
        if (cursor != null && !cursor.isBlank()) {
            String[] decoded = new String(Base64.getDecoder().decode(cursor)).split("\\|", 2);
            conditions.add("(j.first_seen_at, j.id) < (?::timestamptz, ?::uuid)");
            params.add(decoded[0]);
            params.add(decoded[1]);
        }
        String sql = FEED_SELECT + " where " + String.join(" and ", conditions)
                + " order by j.first_seen_at desc, j.id desc limit ?";
        params.add(limit + 1);

        List<FeedItem> rows = jdbcTemplate.query(sql, FEED_MAPPER, params.toArray());
        boolean hasMore = rows.size() > limit;
        List<FeedItem> page = hasMore ? rows.subList(0, limit) : rows;
        String nextCursor = hasMore ? encodeCursor(page.get(page.size() - 1).job().id()) : null;
        return new FeedPage(page, nextCursor);
    }

    /** One posting with its freshness, source and the caller's own match — soft-deleted rows included and flagged. */
    public Optional<FeedItem> findFeedItem(UUID profileId, UUID jobId) {
        return jdbcTemplate.query(FEED_SELECT + " where j.id = ?", FEED_MAPPER, profileId, jobId)
                .stream().findFirst();
    }

    /** One candidate's own match decision for a posting, or empty if they have not scored it. */
    public Optional<JobMatch> findMatch(UUID profileId, UUID jobId) {
        if (profileId == null || jobId == null) {
            return Optional.empty();
        }
        return jdbcTemplate.query("""
                        select score, recommendation, breakdown, scored_at
                        from job_matches where profile_id = ? and job_id = ?
                        """,
                (rs, rowNum) -> new JobMatch(
                        rs.getInt("score"),
                        rs.getString("recommendation"),
                        JdbcConversions.readJsonMap(rs, "breakdown", objectMapper),
                        rs.getTimestamp("scored_at").toInstant()),
                profileId, jobId).stream().findFirst();
    }

    /**
     * A candidate's match result for one posting. Scoped to (profile, job) by
     * construction: it is a row of {@code job_matches}, so it cannot represent
     * anyone else's decision.
     */
    public record JobMatch(int score, String recommendation, java.util.Map<String, Object> breakdown,
                           java.time.Instant scoredAt) {
    }
}
