package com.personal.jobagent.jobs;

import com.personal.jobagent.common.JdbcConversions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    private final RowMapper<FeedItem> feedMapper;

    public JobRepository(JdbcTemplate jdbcTemplate, com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.feedMapper = (rs, rowNum) -> new FeedItem(
                ROW_MAPPER.mapRow(rs, rowNum),
                rs.getTimestamp("first_seen_at").toInstant(),
                rs.getTimestamp("last_seen_at").toInstant(),
                rs.getBoolean("stale"),
                rs.getBoolean("removed"),
                rs.getString("source_name"),
                rs.getString("source_kind"),
                (Integer) rs.getObject("match_score"),
                rs.getString("match_recommendation"),
                rs.getTimestamp("match_scored_at") == null ? null : rs.getTimestamp("match_scored_at").toInstant(),
                JdbcConversions.readJsonMap(rs, "match_breakdown", objectMapper));
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
                           Integer matchScore, String matchRecommendation, java.time.Instant matchScoredAt,
                           Map<String, Object> matchBreakdown) {
        public FeedItem(JobRecord job, java.time.Instant firstSeenAt, java.time.Instant lastSeenAt,
                        boolean stale, boolean removed, String sourceName, String sourceKind,
                        Integer matchScore, String matchRecommendation, java.time.Instant matchScoredAt) {
            this(job, firstSeenAt, lastSeenAt, stale, removed, sourceName, sourceKind, matchScore, matchRecommendation, matchScoredAt, null);
        }
    }

    public record FeedPage(List<FeedItem> items, String nextCursor) {
    }

    private static final String FEED_SELECT = "select "
            + JOB_COLUMNS.replaceAll("(^|, )(\\w+)", "$1j.$2")
            + ", j.first_seen_at, j.last_seen_at, (j.deleted_at is not null) as removed"
            + ", (j.last_seen_at < now() - interval '" + STALE_AFTER_DAYS + " days') as stale"
            + ", s.display_name as source_name, s.kind as source_kind"
            + ", m.score as match_score, m.recommendation as match_recommendation, m.scored_at as match_scored_at"
            + ", m.breakdown as match_breakdown"
            + " from jobs j"
            + " left join job_sources s on s.id = j.source_id"
            + " left join job_matches m on m.job_id = j.id and m.profile_id = ?::uuid";



    /**
     * Candidate-facing filters beyond {@code status}/{@code q}. Every field is
     * optional (null = not applied). Each one is evaluated only against a
     * genuinely stated job field — a job with no value for that field is
     * EXCLUDED when the filter is active, never silently treated as a match
     * (e.g. an unknown salary never satisfies a {@code salaryMin} floor; an
     * un-analysed job never satisfies a {@code sponsorship} requirement).
     *
     * @param location        matched against city, country or the raw
     *                        location text — the same case-insensitive
     *                        equality-or-contains shape
     *                        {@code JobMatchService#computeLocationCategory}
     *                        already uses, so this filter and the scoring
     *                        engine's own location fit never disagree about
     *                        what "matches"
     * @param remoteType      exact match against the real {@code remote_type}
     *                        check-constraint values (REMOTE/HYBRID/ONSITE/UNKNOWN)
     * @param employmentType  case-insensitive substring match — {@code employment_type}
     *                        is free text populated differently by every
     *                        source (see ArbeitnowAdapter's own joined
     *                        job_types string vs. a clean ATS-provided label),
     *                        so an exact-match dropdown would silently miss
     *                        most real records
     * @param experienceLevel case-insensitive substring match, for the same
     *                        reason — no controlled vocabulary for this field
     *                        exists anywhere in Robin today (not even in the
     *                        candidate's own preferences UI)
     * @param salaryMin       only jobs with a KNOWN {@code salary_max} at or
     *                        above this floor qualify
     * @param sponsorship     exact match against the job's most recent
     *                        {@code job_analyses.sponsorship_status} — only
     *                        jobs that have actually been analysed qualify
     * @param postedAfter     only jobs with a KNOWN {@code posted_at} at or
     *                        after this instant qualify
     */
    public record FeedFilters(String location, String remoteType, String employmentType,
                              String experienceLevel, BigDecimal salaryMin, String sponsorship,
                              Instant postedAfter) {
        public static FeedFilters none() {
            return new FeedFilters(null, null, null, null, null, null, null);
        }

        boolean needsSponsorshipJoin() {
            return sponsorship != null;
        }
    }

    /** Candidate-facing sort orders the feed actually supports — see {@link #sortSpec}. */
    public static final Set<String> VALID_SORTS = Set.of("recent", "match", "posted");

    /**
     * One supported sort's SQL shape. {@code keysetColumns} is the exact pair
     * the {@code order by} is built from, so the keyset predicate below is
     * always comparing the same expressions the rows were actually ordered
     * by — the structural guarantee that pagination can never skip or repeat
     * a row. Every nullable sort value is {@code coalesce}d to a sentinel
     * guaranteed outside its real range (-1 for a 0-100 score, the minimum
     * representable timestamp for a date) specifically so the keyset
     * predicate is a plain two-column tuple comparison with no NULL operand —
     * Postgres row-comparison semantics do not resolve a tie through a later
     * column when an earlier one is NULL, which would silently break the
     * deterministic tiebreaker.
     */
    private record SortSpec(String orderBy, String keysetColumns) {
    }

    private static SortSpec sortSpec(String sort) {
        return switch (sort) {
            case "match" -> new SortSpec(
                    "coalesce(m.score, -1) desc, j.id desc",
                    "(coalesce(m.score, -1), j.id)");
            case "posted" -> new SortSpec(
                    "coalesce(j.posted_at, '-infinity'::timestamptz) desc, j.id desc",
                    "(coalesce(j.posted_at, '-infinity'::timestamptz), j.id)");
            default -> new SortSpec(
                    "j.first_seen_at desc, j.id desc",
                    "(j.first_seen_at, j.id)");
        };
    }

    /** Thrown when a cursor was issued for a different sort than the one now requested. */
    public static final class CursorMismatchException extends RuntimeException {
        public CursorMismatchException(String message) {
            super(message);
        }
    }

    /**
     * The FIND feed. Same {@code status}/{@code q} handling as {@link #findJobs},
     * plus the Phase 4 filter/sort set, but soft-deleted postings are excluded,
     * because they can no longer be applied to and the review queue already
     * hides them.
     *
     * <p>Sorting happens here, in SQL, before the {@code limit} is applied —
     * never client-side on an already-paginated page — and the keyset cursor
     * is built from the exact same sort key the query ordered by, so moving
     * between pages under a given sort can never duplicate or skip a row.
     *
     * @param sort one of {@link #VALID_SORTS}; {@code null} or unrecognised
     *             falls back to {@code "recent"} (the pre-existing default
     *             order, preserved unchanged)
     */
    public FeedPage findFeed(UUID profileId, String status, String query, FeedFilters filters, String sort,
                             int limit, String cursor) {
        String sortKey = sort != null && VALID_SORTS.contains(sort) ? sort : "recent";
        SortSpec spec = sortSpec(sortKey);
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
        if (filters.location() != null) {
            conditions.add("(lower(j.city) = lower(?) or lower(j.country) = lower(?) or lower(j.location_raw) like '%'||lower(?)||'%')");
            params.add(filters.location());
            params.add(filters.location());
            params.add(filters.location());
        }
        if (filters.remoteType() != null) {
            conditions.add("j.remote_type = ?");
            params.add(filters.remoteType());
        }
        if (filters.employmentType() != null) {
            conditions.add("lower(j.employment_type) like '%'||lower(?)||'%'");
            params.add(filters.employmentType());
        }
        if (filters.experienceLevel() != null) {
            conditions.add("lower(j.experience_level) like '%'||lower(?)||'%'");
            params.add(filters.experienceLevel());
        }
        if (filters.salaryMin() != null) {
            conditions.add("j.salary_max is not null and j.salary_max >= ?");
            params.add(filters.salaryMin());
        }
        if (filters.postedAfter() != null) {
            conditions.add("j.posted_at is not null and j.posted_at >= ?");
            params.add(Timestamp.from(filters.postedAfter()));
        }
        if (filters.sponsorship() != null) {
            conditions.add("la.sponsorship_status = ?");
            params.add(filters.sponsorship());
        }
        if (cursor != null && !cursor.isBlank()) {
            String[] decoded = new String(Base64.getDecoder().decode(cursor)).split("\\|", 3);
            if (decoded.length != 3 || !decoded[0].equals(sortKey)) {
                throw new CursorMismatchException("cursor was issued for sort '" + (decoded.length > 0 ? decoded[0] : "?")
                        + "', not the requested '" + sortKey + "'");
            }
            String castType = sortKey.equals("match") ? "::int" : "::timestamptz";
            conditions.add(spec.keysetColumns() + " < (?" + castType + ", ?::uuid)");
            params.add(decoded[1]);
            params.add(decoded[2]);
        }
        // The sponsorship-analysis lookup is a LATERAL join, added only when
        // actually filtered on — every other query keeps the exact join shape
        // it always had.
        String lateralJoin = filters.needsSponsorshipJoin()
                ? " left join lateral (select a.sponsorship_status from job_analyses a"
                + " where a.job_id = j.id order by a.created_at desc limit 1) la on true"
                : "";
        String sql = FEED_SELECT + lateralJoin + " where " + String.join(" and ", conditions)
                + " order by " + spec.orderBy() + " limit ?";
        params.add(limit + 1);

        List<FeedItem> rows = jdbcTemplate.query(sql, feedMapper, params.toArray());
        boolean hasMore = rows.size() > limit;
        List<FeedItem> page = hasMore ? rows.subList(0, limit) : rows;
        String nextCursor = hasMore ? encodeFeedCursor(sortKey, page.get(page.size() - 1)) : null;
        return new FeedPage(page, nextCursor);
    }

    private String encodeFeedCursor(String sortKey, FeedItem last) {
        String value = switch (sortKey) {
            case "match" -> String.valueOf(last.matchScore() == null ? -1 : last.matchScore());
            case "posted" -> last.job().postedAt() == null ? "-infinity" : last.job().postedAt().toString();
            default -> last.firstSeenAt().toString();
        };
        String payload = sortKey + "|" + value + "|" + last.job().id();
        return Base64.getEncoder().encodeToString(payload.getBytes());
    }

    /** One posting with its freshness, source and the caller's own match — soft-deleted rows included and flagged. */
    public Optional<FeedItem> findFeedItem(UUID profileId, UUID jobId) {
        return jdbcTemplate.query(FEED_SELECT + " where j.id = ?", feedMapper, profileId, jobId)
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
