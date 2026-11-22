package com.personal.jobagent.discovery;

import com.personal.jobagent.common.MaintenanceModeService;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.discovery.plan.ExecutionFencedException;
import com.personal.jobagent.discovery.plan.SearchPlanRepository;
import com.personal.jobagent.discovery.platform.JobIdentity;
import com.personal.jobagent.events.OutboxWriter;
import com.personal.jobagent.notifications.NotificationEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
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
    private final MaintenanceModeService maintenanceMode;
    private final SearchPlanRepository searchPlanRepository;

    @Autowired
    public JobDiscoveryService(JdbcTemplate jdbcTemplate,
                               OutboxWriter outboxWriter,
                               @Autowired(required = false) MaintenanceModeService maintenanceMode,
                               @Autowired(required = false) SearchPlanRepository searchPlanRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.outboxWriter = outboxWriter;
        this.maintenanceMode = maintenanceMode != null ? maintenanceMode : new MaintenanceModeService(false);
        this.searchPlanRepository = searchPlanRepository;
    }

    public JobDiscoveryService(JdbcTemplate jdbcTemplate,
                               OutboxWriter outboxWriter,
                               MaintenanceModeService maintenanceMode) {
        this(jdbcTemplate, outboxWriter, maintenanceMode, null);
    }

    public JobDiscoveryService(JdbcTemplate jdbcTemplate, OutboxWriter outboxWriter) {
        this(jdbcTemplate, outboxWriter, new MaintenanceModeService(false), null);
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
        return ingestJob(cmd, null, null);
    }

    /**
     * Persists one discovered job under optional search plan execution lease fencing.
     * When searchPlanId and executionOwnerToken are supplied, atomically verifies that
     * the worker holds an active lease via FOR NO KEY UPDATE lock on search_plans before
     * performing any job, observation, or outbox writes.
     */
    @Transactional
    public IngestResult ingestJob(IngestJobCommand cmd, UUID searchPlanId, UUID executionOwnerToken) {
        if (searchPlanId != null && executionOwnerToken != null && searchPlanRepository != null) {
            searchPlanRepository.assertLeaseOwnership(searchPlanId, executionOwnerToken);
        }
        SourceMeta meta = sourceMeta(cmd.sourceId());
        String provider = meta != null ? meta.kind() : null;
        String org = meta != null ? meta.orgIdentifier() : null;
        String url = (cmd.canonicalUrl() != null && !cmd.canonicalUrl().isBlank())
                ? cmd.canonicalUrl() : cmd.applicationUrl();
        String dedupKey = computeDedupKey(provider, org, cmd.externalId(), url,
                cmd.companyNameRaw(), cmd.title(), cmd.locationRaw());
        // JDBC cannot infer a type for java.time.Instant via setObject(); bind
        // the driver-supported java.sql.Timestamp instead.
        java.sql.Timestamp postedAtTs = cmd.postedAt() == null ? null : java.sql.Timestamp.from(cmd.postedAt());
        String contentHash = sha256(cmd.title() + "\n" + cmd.descriptionText());
        // Aggregator pages are provenance, never the employer's application page.
        String applicationUrl = JobIdentity.isAggregatorUrl(cmd.applicationUrl()) ? null : cmd.applicationUrl();

        List<JobIdentity.Key> keys = JobIdentity.keys(cmd.sourceId(), cmd.externalId(), cmd.canonicalUrl(),
                applicationUrl, cmd.companyNameRaw(), cmd.requisitionId());
        List<JobIdentity.Key> lookupKeys = JobIdentity.lookupKeys(cmd.sourceId(), cmd.externalId(), cmd.canonicalUrl(),
                applicationUrl, cmd.companyNameRaw(), cmd.requisitionId());
        lockKeys(lookupKeys);
        Match match = resolve(lookupKeys, cmd);

        if (match != null) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "select content_hash, source_id, application_url, canonical_url, last_discovered_event_at from jobs where id = ?", match.jobId());
            Map<String, Object> row = rows.isEmpty() ? Map.of() : rows.get(0);
            UUID existingId = match.jobId();
            UUID primarySource = (UUID) row.get("source_id");
            boolean crossSource = primarySource != null && cmd.sourceId() != null && !primarySource.equals(cmd.sourceId());
            String prevHash = (String) row.get("content_hash");
            improveUrls(existingId, (String) row.get("application_url"), (String) row.get("canonical_url"),
                    applicationUrl, cmd.canonicalUrl());

            String incomingKind = sourceKind(cmd.sourceId());
            boolean authoritative = incomingKind != null && !"TSENTA".equals(incomingKind)
                    && !"FREEHIRE".equals(incomingKind) && !"ARBEITNOW".equals(incomingKind) && !"ADZUNA".equals(incomingKind);

            String action;
            if (crossSource && promoteFromAggregator(existingId, primarySource, cmd, contentHash)) {
                action = "UPDATED";
            } else if (crossSource || contentHash.equals(prevHash)) {
                // Seen again (or seen through another source): freshness only.
                // Aggregator cannot resurrect a job closed by an authoritative source.
                if (postedAtTs == null || crossSource) {
                    jdbcTemplate.update("""
                            update jobs set last_seen_at = now(),
                                deleted_at = case
                                    when ? then null
                                    when exists (
                                        select 1 from job_source_observations o
                                        join job_sources s on s.id = o.source_id
                                        where o.job_id = jobs.id
                                          and s.kind not in ('TSENTA', 'FREEHIRE', 'ARBEITNOW', 'ADZUNA')
                                          and o.source_status in ('MISSING', 'CLOSED')
                                    ) then deleted_at
                                    else null
                                end
                            where id = ?
                            """, authoritative, existingId);
                } else {
                    jdbcTemplate.update("""
                            update jobs set last_seen_at = now(),
                                posted_at = ?::timestamptz,
                                posted_date_source = 'EXPLICIT',
                                deleted_at = case
                                    when ? then null
                                    when exists (
                                        select 1 from job_source_observations o
                                        join job_sources s on s.id = o.source_id
                                        where o.job_id = jobs.id
                                          and s.kind not in ('TSENTA', 'FREEHIRE', 'ARBEITNOW', 'ADZUNA')
                                          and o.source_status in ('MISSING', 'CLOSED')
                                    ) then deleted_at
                                    else null
                                end
                            where id = ?
                            """, postedAtTs, authoritative, existingId);
                }
                action = "TOUCHED";
            } else {
                // Repost or content updated by the job's own source.
                jdbcTemplate.update("""
                        update jobs set content_hash = ?, title = ?, description_text = ?,
                            repost_count = repost_count + 1, last_seen_at = now(),
                            deleted_at = case
                                when ? then null
                                when exists (
                                    select 1 from job_source_observations o
                                    join job_sources s on s.id = o.source_id
                                    where o.job_id = jobs.id
                                      and s.kind not in ('TSENTA', 'FREEHIRE', 'ARBEITNOW', 'ADZUNA')
                                      and o.source_status in ('MISSING', 'CLOSED')
                                ) then deleted_at
                                else null
                            end,
                            posted_at = coalesce(?::timestamptz, posted_at),
                            posted_date_source = case when ?::timestamptz is null then posted_date_source else 'EXPLICIT' end
                        where id = ?
                        """, contentHash, cmd.title(), cmd.descriptionText(), authoritative, postedAtTs, postedAtTs, existingId);
                action = "UPDATED";
            }
            storeKeys(existingId, keys, cmd.sourceId());
            recordObservation(existingId, cmd, contentHash);

            boolean shouldEmit = true;
            if ("TOUCHED".equals(action) && touchedEventIntervalHours > 0) {
                Object lastEventObj = row.get("last_discovered_event_at");
                if (lastEventObj instanceof java.sql.Timestamp lastTs) {
                    long hours = java.time.Duration.between(lastTs.toInstant(), Instant.now()).toHours();
                    if (hours < touchedEventIntervalHours) {
                        shouldEmit = false;
                    }
                }
            }
            if (shouldEmit) {
                emitDiscovered(existingId, action, cmd.sourceId());
                jdbcTemplate.update("update jobs set last_discovered_event_at = now() where id = ?", existingId);
            }
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
                                  repost_count, content_hash, status, last_discovered_event_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz,
                        case when ?::timestamptz is null then 'UNKNOWN' else 'EXPLICIT' end, now(), now(), 0, ?, 'DISCOVERED', now())
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

    public record SourceMeta(String kind, String orgIdentifier) {}

    private SourceMeta sourceMeta(UUID sourceId) {
        if (sourceId == null) return null;
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("select kind, org_identifier from job_sources where id = ?", sourceId);
            if (rows.isEmpty()) return null;
            Map<String, Object> r = rows.get(0);
            return new SourceMeta((String) r.get("kind"), (String) r.get("org_identifier"));
        } catch (Exception e) {
            return null;
        }
    }

    private String sourceKind(UUID sourceId) {
        SourceMeta meta = sourceMeta(sourceId);
        return meta != null ? meta.kind() : null;
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

    /**
     * Migration switch (database migration Phase 2, plan §10.7): while
     * discovery is paused no job is "seen", so the hourly sweep below would
     * archive jobs only because the pause outlasted 30 days.
     * {@code app.discovery.maintenance-enabled=false} skips it. Default: on.
     */
    @Value("${app.discovery.maintenance-enabled:true}")
    private boolean maintenanceEnabled = true;

    @Value("${app.discovery.touched-event-interval-hours:0}")
    private int touchedEventIntervalHours = 0;

    @Value("${app.discovery.aggregator-observation-ttl-days:7}")
    private int aggregatorTtlDays = 7;

    /**
     * Hourly cleanup: expires stale aggregator observations, soft-deletes only the jobs whose observations this
     * sweep expired and that have no remaining live observation (F9), then archives jobs unseen for 30 days.
     *
     * <p><b>Lock order (F9).</b> {@link #ingestJob} locks a job row and then that job's observation. This sweep
     * therefore locks every job row it may modify first, in ascending id order and in a single statement, before
     * it touches any observation, so both workflows take locks in the same order (job, then observation) and
     * cannot deadlock. PostgreSQL applies {@code ORDER BY} before the locking clause, so the row locks are acquired
     * in ascending id order. Observations are then expired only for jobs this sweep already holds, so no job lock
     * is ever requested while an observation lock is held.
     *
     * <p>The job rows are locked {@code FOR NO KEY UPDATE}, the strength of the sweep's own updates, which change
     * no key column. That conflicts with ingestion's update of the same job, so ingestion waits for the sweep. It
     * does not conflict with the {@code KEY SHARE} locks that foreign-key checks take, so inserts that only
     * reference a locked job (matches, applications, bookmarks, notifications) are not held up for the length of
     * the sweep.
     *
     * <p>Taking the job locks in their own statement also matters for correctness. Every later statement in this
     * READ COMMITTED transaction starts with a fresh snapshot that already includes any ingestion this sweep
     * waited for, so the deletion check cannot miss an observation committed concurrently. The isolation level is
     * pinned for that reason: under REPEATABLE READ every statement would share the first snapshot.
     *
     * <p>Only one sweep runs at a time. A second concurrent sweep (another instance) returns immediately instead
     * of queueing behind the first. The whole sweep is one transaction: any failure rolls back every step.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    @Scheduled(cron = "0 0 * * * *") // hourly scheduled refresh for source health and stale jobs
    public void scheduledDiscoveryMaintenance() {
        if (!maintenanceEnabled || maintenanceMode.isMaintenanceMode()) {
            return;
        }
        Boolean acquired = jdbcTemplate.queryForObject(
                "select pg_try_advisory_xact_lock(hashtextextended('job-discovery-maintenance', 0))", Boolean.class);
        if (!Boolean.TRUE.equals(acquired)) {
            log.info("Discovery maintenance skipped: another sweep is already running");
            return;
        }

        jdbcTemplate.execute("""
                create temporary table if not exists maintenance_locked_job_ids (
                    job_id uuid primary key
                ) on commit drop
                """);
        jdbcTemplate.execute("""
                create temporary table if not exists expired_job_ids (
                    job_id uuid
                ) on commit drop
                """);
        jdbcTemplate.update("truncate table maintenance_locked_job_ids");
        jdbcTemplate.update("truncate table expired_job_ids");

        // 1. Lock every job this sweep may modify - jobs with an expirable aggregator observation, and jobs due
        //    for archiving - in ascending id order, before any observation is touched (FOR NO KEY UPDATE: see above).
        jdbcTemplate.update("""
                with locked as (
                    select j.id from jobs j
                    where j.id in (
                        select o.job_id from job_source_observations o
                        where o.job_id is not null
                          and o.source_status = 'SEEN'
                          and o.last_seen_at < now() - make_interval(days => ?)
                          and (
                              o.source_id in (select id from job_sources where kind in ('TSENTA', 'FREEHIRE', 'ARBEITNOW', 'ADZUNA'))
                              or o.source_type = 'WEB'
                              or o.source_id is null
                          )
                        union
                        select a.id from jobs a
                        where a.status = 'DISCOVERED' and a.last_seen_at < now() - interval '30 days'
                    )
                    order by j.id
                    for no key update
                )
                insert into maintenance_locked_job_ids(job_id)
                select id from locked
                """, aggregatorTtlDays);

        // 2. Expire stale aggregator observations (P1-1), only for jobs locked above (job_id is NOT NULL, V018).
        int expiredObs = jdbcTemplate.update("""
                with expired as (
                    update job_source_observations o
                    set source_status = 'MISSING', removed_at = now()
                    where o.source_status = 'SEEN'
                      and o.last_seen_at < now() - make_interval(days => ?)
                      and (
                          o.source_id in (select id from job_sources where kind in ('TSENTA', 'FREEHIRE', 'ARBEITNOW', 'ADZUNA'))
                          or o.source_type = 'WEB'
                          or o.source_id is null
                      )
                      and o.job_id in (select job_id from maintenance_locked_job_ids)
                    returning o.job_id
                )
                insert into expired_job_ids(job_id)
                select job_id from expired
                """, aggregatorTtlDays);

        // 3. Soft-delete only the jobs whose observations this sweep expired and that have no live observation left.
        if (expiredObs > 0) {
            int deletedJobs = jdbcTemplate.update("""
                    update jobs j set deleted_at = now()
                    where j.deleted_at is null
                      and j.id in (select job_id from expired_job_ids)
                      and j.id in (select job_id from maintenance_locked_job_ids)
                      and not exists (
                          select 1 from job_source_observations o
                          where o.job_id = j.id and o.source_status = 'SEEN'
                      )
                    """);
            log.info("Expired {} stale aggregator observations older than {} days; soft-deleted {} affected jobs with no remaining live observation",
                    expiredObs, aggregatorTtlDays, deletedJobs);
        }

        // 4. Mark jobs not seen in 30 days as ARCHIVED; every candidate was locked in step 1.
        int archived = jdbcTemplate.update("""
                update jobs set status = 'ARCHIVED'
                where status = 'DISCOVERED' and last_seen_at < now() - interval '30 days'
                  and id in (select job_id from maintenance_locked_job_ids)
                """);
        if (archived > 0) {
            log.info("Archived {} stale jobs older than 30 days", archived);
        }
    }

    /**
     * Computes the deduplication key following the strict identity hierarchy:
     * 1. Prefer provider + organisation + stable external job ID
     * 2. Canonical source URL as fallback
     * 3. title/company/location as last resort
     */
    public static String computeDedupKey(String provider, String org, String externalId,
                                         String canonicalUrl, String company, String title, String location) {
        if (externalId != null && !externalId.isBlank()) {
            String p = provider != null ? provider.trim().toLowerCase(java.util.Locale.ROOT) : "";
            String o = org != null ? org.trim().toLowerCase(java.util.Locale.ROOT) : "";
            if (p.isEmpty() && o.isEmpty() && canonicalUrl != null) {
                String ats = JobIdentity.atsKey(canonicalUrl);
                if (ats != null) {
                    return sha256("id:" + ats);
                }
            }
            String prefix = "";
            if (!p.isEmpty() || !o.isEmpty()) {
                prefix = p + ":" + o + ":";
            }
            return sha256("id:" + prefix + externalId.trim().toLowerCase(java.util.Locale.ROOT));
        }
        if (canonicalUrl != null && !canonicalUrl.isBlank()) {
            String normalized = JobIdentity.normalizeUrl(canonicalUrl);
            String urlToUse = (normalized != null && !normalized.isBlank())
                    ? normalized : canonicalUrl.trim().toLowerCase(java.util.Locale.ROOT);
            return sha256("url:" + urlToUse);
        }
        String raw = (company != null ? company.trim().toLowerCase(java.util.Locale.ROOT) : "") + "|"
                + (title != null ? title.trim().toLowerCase(java.util.Locale.ROOT) : "") + "|"
                + (location != null ? location.trim().toLowerCase(java.util.Locale.ROOT) : "");
        return sha256(raw);
    }

    public static String computeDedupKey(String company, String title, String location) {
        return computeDedupKey(null, null, null, null, company, title, location);
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