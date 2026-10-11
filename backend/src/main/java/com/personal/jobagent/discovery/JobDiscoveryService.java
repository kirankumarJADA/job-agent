package com.personal.jobagent.discovery;

import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.discovery.platform.JobIdentity;
import com.personal.jobagent.events.OutboxWriter;
import com.personal.jobagent.notifications.NotificationEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.util.UUID;

@Service
public class JobDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(JobDiscoveryService.class);

    private final JdbcTemplate jdbcTemplate;
    private final OutboxWriter outboxWriter;

    public JobDiscoveryService(JdbcTemplate jdbcTemplate, OutboxWriter outboxWriter) {
        this.jdbcTemplate = jdbcTemplate;
        this.outboxWriter = outboxWriter;
    }

    public record IngestJobCommand(
            UUID sourceId,
            String externalId,
            UUID companyId,
            String companyNameRaw,
            String title,
            String locationRaw,
            String city,
            String country,
            String remoteType,
            String employmentType,
            String experienceLevel,
            Number salaryMin,
            Number salaryMax,
            String salaryCurrency,
            String descriptionText,
            List<String> skillsExtracted,
            String applicationUrl,
            String canonicalUrl,
            Instant postedAt,
            String salaryPeriod,
            String requisitionId
    ) {
        /** Pre-8.2.5 constructor (no salary period / requisition id). */
        public IngestJobCommand(UUID sourceId, String externalId, UUID companyId, String companyNameRaw,
                                String title, String locationRaw, String city, String country, String remoteType,
                                String employmentType, String experienceLevel, Number salaryMin, Number salaryMax,
                                String salaryCurrency, String descriptionText, List<String> skillsExtracted,
                                String applicationUrl, String canonicalUrl, Instant postedAt) {
            this(sourceId, externalId, companyId, companyNameRaw, title, locationRaw, city, country, remoteType,
                    employmentType, experienceLevel, salaryMin, salaryMax, salaryCurrency, descriptionText,
                    skillsExtracted, applicationUrl, canonicalUrl, postedAt, null, null);
        }

        /** Backwards-compatible constructor for existing ingestion callers. */
        public IngestJobCommand(UUID sourceId, String externalId, UUID companyId, String companyNameRaw,
                                String title, String locationRaw, String city, String country, String remoteType,
                                String employmentType, String experienceLevel, Number salaryMin, Number salaryMax,
                                String salaryCurrency, String descriptionText, List<String> skillsExtracted,
                                String applicationUrl, String canonicalUrl) {
            this(sourceId, externalId, companyId, companyNameRaw, title, locationRaw, city, country, remoteType,
                    employmentType, experienceLevel, salaryMin, salaryMax, salaryCurrency, descriptionText,
                    skillsExtracted, applicationUrl, canonicalUrl, null, null, null);
        }
    }

    /**
     * @param matchedBy null for a new posting; otherwise the strongest identity
     *                  evidence that matched an existing job (SRC/ATS/REQ/URL,
     *                  or LEGACY for a pre-V037 row found by source id)
     * @param crossSource true when the match is a posting first seen through a
     *                    DIFFERENT source — a cross-source duplicate
     */
    public record IngestResult(UUID jobId, String action, String dedupKey, String contentHash,
                               String matchedBy, boolean crossSource) {
        public IngestResult(UUID jobId, String action, String dedupKey, String contentHash) {
            this(jobId, action, dedupKey, contentHash, null, false);
        }
    }

    /**
     * Persists one discovered job and publishes {@code job.discovered} on the
     * outbox IN THE SAME TRANSACTION, so the matching pipeline sees exactly
     * what ingestion committed. TOUCHED is emitted too, not just
     * INSERTED/UPDATED: a previous pipeline run may have failed after the job
     * row landed, and every successful re-ingestion is the self-heal that
     * re-triggers idempotent matching.
     *
     * <p>Phase 8.2.5 identity: an existing job is matched only on STRONG
     * evidence (see {@link JobIdentity}) — the same source external id, the
     * same platform job id embedded in a URL, the same employer requisition
     * id, or the same normalised job/application URL. The old
     * company|title|location {@code dedup_key} is still computed and stored but
     * is no longer used to merge postings: two different roles with the same
     * title stay separate. All identity keys are locked with transaction-scoped
     * advisory locks (in a fixed order) before lookup, so concurrent ingestion
     * of the same posting from two sources cannot create two jobs.
     *
     * <p>A match from a different source than the job's primary one is a
     * cross-source duplicate: it refreshes last_seen_at and provenance, may
     * upgrade a weaker application URL (an ATS URL beats an employer page;
     * aggregator URLs are never application URLs), and may promote an
     * aggregator-first job to its ATS source — but it never rewrites content
     * or bumps repost_count, so two sources describing one role differently
     * cannot flap it between versions.
     */
    @Transactional
    public IngestResult ingestJob(IngestJobCommand cmd) {
        String dedupKey = computeDedupKey(cmd.companyNameRaw(), cmd.title(), cmd.locationRaw());
        // JDBC cannot infer a type for java.time.Instant via setObject(); bind
        // the driver-supported java.sql.Timestamp instead.
        java.sql.Timestamp postedAtTs = cmd.postedAt() == null ? null : java.sql.Timestamp.from(cmd.postedAt());
        String contentHash = sha256(cmd.title() + "\n" + cmd.descriptionText());
        // Aggregator pages are provenance, never the employer's application page.
        String applicationUrl = JobIdentity.isAggregatorUrl(cmd.applicationUrl()) ? null : cmd.applicationUrl();

        List<JobIdentity.Key> keys = JobIdentity.keys(cmd.sourceId(), cmd.externalId(), cmd.canonicalUrl(),
                applicationUrl, cmd.companyNameRaw(), cmd.requisitionId());
        lockKeys(keys);
        Match match = resolve(keys, cmd);

        if (match != null) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "select content_hash, source_id, application_url, canonical_url from jobs where id = ?", match.jobId());
            Map<String, Object> row = rows.isEmpty() ? Map.of() : rows.get(0);
            UUID existingId = match.jobId();
            UUID primarySource = (UUID) row.get("source_id");
            boolean crossSource = primarySource != null && cmd.sourceId() != null && !primarySource.equals(cmd.sourceId());
            String prevHash = (String) row.get("content_hash");
            improveUrls(existingId, (String) row.get("application_url"), (String) row.get("canonical_url"),
                    applicationUrl, cmd.canonicalUrl());
            String action;
            if (crossSource && promoteFromAggregator(existingId, primarySource, cmd, contentHash)) {
                action = "UPDATED";
            } else if (crossSource || contentHash.equals(prevHash)) {
                // Seen again (or seen through another source): freshness only.
                if (postedAtTs == null || crossSource) {
                    jdbcTemplate.update("update jobs set last_seen_at = now(), deleted_at = null where id = ?", existingId);
                } else {
                    jdbcTemplate.update("update jobs set last_seen_at = now(), deleted_at = null, posted_at = ?::timestamptz, "
                                    + "posted_date_source = 'EXPLICIT' where id = ?", postedAtTs, existingId);
                }
                action = "TOUCHED";
            } else {
                // Repost or content updated by the job's own source.
                jdbcTemplate.update("""
                        update jobs set content_hash = ?, title = ?, description_text = ?,
                            repost_count = repost_count + 1, last_seen_at = now(), deleted_at = null,
                            posted_at = coalesce(?::timestamptz, posted_at),
                            posted_date_source = case when ?::timestamptz is null then posted_date_source else 'EXPLICIT' end
                        where id = ?
                        """, contentHash, cmd.title(), cmd.descriptionText(), postedAtTs, postedAtTs, existingId);
                action = "UPDATED";
            }
            storeKeys(existingId, keys, cmd.sourceId());
            recordObservation(existingId, cmd, contentHash);
            emitDiscovered(existingId, action, cmd.sourceId());
            return new IngestResult(existingId, action, dedupKey, contentHash, match.matchedBy(), crossSource);
        }

        UUID id = UuidV7.generate();
        String[] skillsArray = cmd.skillsExtracted() != null ? cmd.skillsExtracted().toArray(new String[0]) : new String[0];

        jdbcTemplate.update("""
                insert into jobs (id, source_id, external_id, dedup_key, company_id, company_name_raw,
                                  title, location_raw, city, country, remote_type, employment_type,
                                  experience_level, salary_min, salary_max, salary_currency, salary_period,
                                  description_text, skills_extracted, application_url, canonical_url,
                                  posted_at, posted_date_source, first_seen_at, last_seen_at,
                                  repost_count, content_hash, status)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz,
                        case when ?::timestamptz is null then 'UNKNOWN' else 'EXPLICIT' end, now(), now(), 0, ?, 'DISCOVERED')
                """,
                id, cmd.sourceId(), cmd.externalId(), dedupKey, cmd.companyId(), cmd.companyNameRaw(),
                cmd.title(), cmd.locationRaw(), cmd.city(), cmd.country(), cmd.remoteType(), cmd.employmentType(),
                cmd.experienceLevel(), cmd.salaryMin(), cmd.salaryMax(), cmd.salaryCurrency(), cmd.salaryPeriod(),
                cmd.descriptionText(), skillsArray, applicationUrl, cmd.canonicalUrl(),
                postedAtTs, postedAtTs, contentHash);

        storeKeys(id, keys, cmd.sourceId());
        recordObservation(id, cmd, contentHash);
        emitDiscovered(id, "INSERTED", cmd.sourceId());
        return new IngestResult(id, "INSERTED", dedupKey, contentHash);
    }

    private record Match(UUID jobId, String matchedBy) {}

    /** Transaction-scoped advisory locks on every identity key, in a fixed order (no deadlocks). */
    private void lockKeys(List<JobIdentity.Key> keys) {
        keys.stream().map(JobIdentity.Key::asString).sorted().forEach(key ->
                jdbcTemplate.queryForList("select pg_advisory_xact_lock(hashtextextended(?, 0))", key));
    }

    /**
     * The existing job for the strongest matching key. When different keys
     * point at different jobs (conflicting evidence) the strongest key wins
     * and nothing is merged.
     */
    private Match resolve(List<JobIdentity.Key> keys, IngestJobCommand cmd) {
        if (!keys.isEmpty()) {
            String placeholders = String.join(",", java.util.Collections.nCopies(keys.size(), "?"));
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "select key, job_id from job_identity_keys where key in (" + placeholders + ")",
                    keys.stream().map(JobIdentity.Key::asString).toArray());
            Map<String, UUID> byKey = new java.util.HashMap<>();
            for (Map<String, Object> r : rows) {
                if (r.get("key") != null && r.get("job_id") != null) byKey.put((String) r.get("key"), (UUID) r.get("job_id"));
            }
            for (JobIdentity.Key key : keys) {
                UUID job = byKey.get(key.asString());
                if (job != null) return new Match(job, key.type().name());
            }
        }
        // Pre-V037 rows inserted outside ingestion carry no keys yet.
        if (cmd.sourceId() != null && cmd.externalId() != null) {
            List<Map<String, Object>> legacy = jdbcTemplate.queryForList(
                    "select id from jobs where source_id = ? and external_id = ?", cmd.sourceId(), cmd.externalId());
            if (!legacy.isEmpty() && legacy.get(0).get("id") != null) return new Match((UUID) legacy.get(0).get("id"), "LEGACY");
        }
        return null;
    }

    private void storeKeys(UUID jobId, List<JobIdentity.Key> keys, UUID sourceId) {
        for (JobIdentity.Key key : keys) {
            // First writer wins: a key already bound to another job is conflicting
            // evidence and is left alone rather than re-pointed.
            jdbcTemplate.update("insert into job_identity_keys(key, job_id, key_type, source_id) values (?, ?, ?, ?) "
                    + "on conflict (key) do nothing", key.asString(), jobId, key.type().name(), sourceId);
        }
    }

    /** Keeps the best application URL and replaces an aggregator canonical URL with a real one. */
    private void improveUrls(UUID jobId, String currentApplication, String currentCanonical,
                             String incomingApplication, String incomingCanonical) {
        if (incomingApplication != null
                && JobIdentity.applicationUrlRank(incomingApplication) > JobIdentity.applicationUrlRank(currentApplication)) {
            jdbcTemplate.update("update jobs set application_url = ? where id = ?", incomingApplication, jobId);
        }
        if (currentCanonical != null && JobIdentity.isAggregatorUrl(currentCanonical)
                && incomingCanonical != null && !JobIdentity.isAggregatorUrl(incomingCanonical)) {
            jdbcTemplate.update("update jobs set canonical_url = ? where id = ?", incomingCanonical, jobId);
        }
    }

    /**
     * A job first found through an aggregator becomes owned by the employer's
     * own ATS source once that source lists it: the ATS text is authoritative.
     */
    private boolean promoteFromAggregator(UUID jobId, UUID primarySource, IngestJobCommand cmd, String contentHash) {
        String primaryKind = sourceKind(primarySource);
        String incomingKind = sourceKind(cmd.sourceId());
        if (!"TSENTA".equals(primaryKind) || incomingKind == null || "TSENTA".equals(incomingKind)) return false;
        int updated = jdbcTemplate.update("""
                update jobs set source_id = ?, external_id = ?, content_hash = ?, title = ?, description_text = ?,
                    last_seen_at = now(), deleted_at = null
                where id = ? and not exists (select 1 from jobs other where other.source_id = ? and other.external_id = ?)
                """, cmd.sourceId(), cmd.externalId(), contentHash, cmd.title(), cmd.descriptionText(),
                jobId, cmd.sourceId(), cmd.externalId());
        return updated > 0;
    }

    private String sourceKind(UUID sourceId) {
        if (sourceId == null) return null;
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("select kind from job_sources where id = ?", sourceId);
        return rows.isEmpty() ? null : (String) rows.get(0).get("kind");
    }

    /**
     * Publishes {@code job.discovered} on the outbox. Same transaction as the
     * job write, so the event can never describe a job row that did not
     * commit, and the row can never commit without its pipeline trigger.
     */
    private void emitDiscovered(UUID jobId, String action, UUID sourceId) {
        outboxWriter.append("JOB", jobId, NotificationEvents.JOB_DISCOVERED,
                Map.of("job_id", jobId.toString(), "action", action,
                        "source_id", sourceId == null ? "" : sourceId.toString()),
                UuidV7.generate(), null);
    }

    private void recordObservation(UUID jobId, IngestJobCommand cmd, String contentHash) {
        try {
            jdbcTemplate.update("insert into job_source_observations(id,job_id,source_type,provider,source_url,external_job_id,content_hash,extraction_hash,extraction_confidence,last_seen_at,source_id,source_status,removed_at) values(?,?,?,?,?,?,?,?,?,now(),?,'SEEN',null) on conflict(provider,source_url,external_job_id) do update set job_id=excluded.job_id,last_seen_at=now(),content_hash=excluded.content_hash,source_id=excluded.source_id,source_status='SEEN',removed_at=null", UuidV7.generate(), jobId, "JOB_SOURCE", "job-source:" + cmd.sourceId(), cmd.canonicalUrl(), cmd.externalId() == null ? "" : cmd.externalId(), contentHash, contentHash, 1.0, cmd.sourceId());
        } catch (RuntimeException ignored) {
            log.debug("Source observation persistence unavailable during discovery ingest");
        }
    }

    @Scheduled(cron = "0 0 * * * *") // hourly scheduled refresh for source health and stale jobs
    public void scheduledDiscoveryMaintenance() {
        // Mark jobs not seen in 30 days as ARCHIVED
        int archived = jdbcTemplate.update("""
                update jobs set status = 'ARCHIVED'
                where status = 'DISCOVERED' and last_seen_at < now() - interval '30 days'
                """);
        if (archived > 0) {
            log.info("Archived {} stale jobs older than 30 days", archived);
        }
    }

    public static String computeDedupKey(String company, String title, String location) {
        String raw = (company != null ? company.trim().toLowerCase() : "") + "|"
                + (title != null ? title.trim().toLowerCase() : "") + "|"
                + (location != null ? location.trim().toLowerCase() : "");
        return sha256(raw);
    }

    public static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}