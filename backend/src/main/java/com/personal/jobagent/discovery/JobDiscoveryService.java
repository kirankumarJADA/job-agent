package com.personal.jobagent.discovery;

import com.personal.jobagent.common.UuidV7;
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
            Instant postedAt
    ) {
        /** Backwards-compatible constructor for existing ingestion callers. */
        public IngestJobCommand(UUID sourceId, String externalId, UUID companyId, String companyNameRaw,
                                String title, String locationRaw, String city, String country, String remoteType,
                                String employmentType, String experienceLevel, Number salaryMin, Number salaryMax,
                                String salaryCurrency, String descriptionText, List<String> skillsExtracted,
                                String applicationUrl, String canonicalUrl) {
            this(sourceId, externalId, companyId, companyNameRaw, title, locationRaw, city, country, remoteType,
                    employmentType, experienceLevel, salaryMin, salaryMax, salaryCurrency, descriptionText,
                    skillsExtracted, applicationUrl, canonicalUrl, null);
        }
    }

    public record IngestResult(UUID jobId, String action, String dedupKey, String contentHash) {}

    /**
     * Persists one discovered job and publishes {@code job.discovered} on the
     * outbox IN THE SAME TRANSACTION, so the matching pipeline sees exactly
     * what ingestion committed. TOUCHED is emitted too, not just
     * INSERTED/UPDATED: a previous pipeline run may have failed after the job
     * row landed, and every successful re-ingestion is the self-heal that
     * re-triggers idempotent matching.
     */
    @Transactional
    public IngestResult ingestJob(IngestJobCommand cmd) {
        String dedupKey = computeDedupKey(cmd.companyNameRaw(), cmd.title(), cmd.locationRaw());
        // JDBC cannot infer a type for java.time.Instant via setObject(); bind
        // the driver-supported java.sql.Timestamp instead.
        java.sql.Timestamp postedAtTs = cmd.postedAt() == null ? null : java.sql.Timestamp.from(cmd.postedAt());
        String contentHash = sha256(cmd.title() + "\n" + cmd.descriptionText());

        // Check if job exists by dedup_key or (source_id, external_id)
        List<Map<String, Object>> existing = jdbcTemplate.queryForList(
                "select id, content_hash, repost_count from jobs where dedup_key = ? or (source_id = ? and external_id = ?)",
                dedupKey, cmd.sourceId(), cmd.externalId());

        if (!existing.isEmpty()) {
            Map<String, Object> row = existing.get(0);
            UUID existingId = (UUID) row.get("id");
            String prevHash = (String) row.get("content_hash");

            if (contentHash.equals(prevHash)) {
                // Stale / identical job seen again: update last_seen_at
                if (postedAtTs == null) {
                    jdbcTemplate.update("update jobs set last_seen_at = now() where id = ?", existingId);
                } else {
                    jdbcTemplate.update("update jobs set last_seen_at = now(), posted_at = ?::timestamptz, posted_date_source = 'EXPLICIT' where id = ?",
                            postedAtTs, existingId);
                }
                recordObservation(existingId, cmd, contentHash);
                emitDiscovered(existingId, "TOUCHED", cmd.sourceId());
                return new IngestResult(existingId, "TOUCHED", dedupKey, contentHash);
            } else {
                // Repost or content updated
                jdbcTemplate.update("""
                        update jobs set content_hash = ?, repost_count = repost_count + 1, last_seen_at = now(),
                            posted_at = coalesce(?::timestamptz, posted_at),
                            posted_date_source = case when ?::timestamptz is null then posted_date_source else 'EXPLICIT' end
                        where id = ?
                        """, contentHash, postedAtTs, postedAtTs, existingId);
                recordObservation(existingId, cmd, contentHash);
                emitDiscovered(existingId, "UPDATED", cmd.sourceId());
                return new IngestResult(existingId, "UPDATED", dedupKey, contentHash);
            }
        }

        UUID id = UuidV7.generate();
        String[] skillsArray = cmd.skillsExtracted() != null ? cmd.skillsExtracted().toArray(new String[0]) : new String[0];

        jdbcTemplate.update("""
                insert into jobs (id, source_id, external_id, dedup_key, company_id, company_name_raw,
                                  title, location_raw, city, country, remote_type, employment_type,
                                  experience_level, salary_min, salary_max, salary_currency,
                                  description_text, skills_extracted, application_url, canonical_url,
                                  posted_at, posted_date_source, first_seen_at, last_seen_at,
                                  repost_count, content_hash, status)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz,
                        case when ?::timestamptz is null then 'UNKNOWN' else 'EXPLICIT' end, now(), now(), 0, ?, 'DISCOVERED')
                """,
                id, cmd.sourceId(), cmd.externalId(), dedupKey, cmd.companyId(), cmd.companyNameRaw(),
                cmd.title(), cmd.locationRaw(), cmd.city(), cmd.country(), cmd.remoteType(), cmd.employmentType(),
                cmd.experienceLevel(), cmd.salaryMin(), cmd.salaryMax(), cmd.salaryCurrency(),
                cmd.descriptionText(), skillsArray, cmd.applicationUrl(), cmd.canonicalUrl(),
                postedAtTs, postedAtTs, contentHash);

        recordObservation(id, cmd, contentHash);
        emitDiscovered(id, "INSERTED", cmd.sourceId());
        return new IngestResult(id, "INSERTED", dedupKey, contentHash);
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
            jdbcTemplate.update("insert into job_source_observations(id,job_id,source_type,provider,source_url,external_job_id,content_hash,extraction_hash,extraction_confidence,last_seen_at) values(?,?,?,?,?,?,?,?,?,now()) on conflict(provider,source_url,external_job_id) do update set job_id=excluded.job_id,last_seen_at=now(),content_hash=excluded.content_hash", UuidV7.generate(), jobId, "JOB_SOURCE", "job-source:" + cmd.sourceId(), cmd.canonicalUrl(), cmd.externalId() == null ? "" : cmd.externalId(), contentHash, contentHash, 1.0);
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

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}