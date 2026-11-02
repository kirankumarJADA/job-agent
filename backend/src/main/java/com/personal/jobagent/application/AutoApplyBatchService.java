package com.personal.jobagent.application;

import com.personal.jobagent.apply.DuplicateApplicationException;
import com.personal.jobagent.audit.AuditEntry;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.common.QuotaService;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.jobs.JobRepository;
import com.personal.jobagent.profile.ProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 5 (Robin x Tsenta continuation): the explicit, bounded Auto Apply
 * batch workflow.
 *
 * <p>This is a user-triggered sweep over the candidate's EXISTING matched-job
 * backlog (persisted {@code job_matches} scores — nothing here re-scores a
 * job), gated by {@link AutoApplySettingsService} and bounded by the
 * settings' own {@code maxBatchSize} and {@code dailyCap}. It is deliberately
 * separate from {@link ApplicationDecisionService}'s continuous, event-driven
 * engine, which keeps running unconditionally regardless of these settings —
 * see that class and {@link AutoApplySettingsService} for why.
 *
 * <p>Every eligible job is handed to the exact same preparation primitive the
 * rest of Robin uses, {@link ApplicationPipelineService#createApplicationFromMatch},
 * which performs the actual idempotent creation, duplicate detection, and
 * (via the existing event-driven {@code application.created} handler) kicks
 * off the existing asynchronous CV/cover-letter/answer preparation pipeline.
 * This service never generates a document itself and never calls any ATS or
 * worker endpoint — "prepared" here means "an application was created and
 * handed to the existing preparation pipeline", never "submitted".
 *
 * <p>Quota safety: capacity is reserved ({@link QuotaService#tryReserve})
 * BEFORE {@code createApplicationFromMatch} runs and released in every path
 * that does not end in a genuinely new application (duplicate, idempotent
 * replay, or an exception) — the same reserve/release discipline
 * {@link ApplicationPipelineEventHandler} already uses for the continuous
 * engine, applied here against this batch's own quota key so the two
 * mechanisms cannot interfere with each other's counters.
 */
@Service
public class AutoApplyBatchService {

    private static final Logger log = LoggerFactory.getLogger(AutoApplyBatchService.class);

    /** This batch feature's own quota key — separate from the continuous engine's "application_daily". */
    static final String QUOTA_KEY = "auto_apply_batch_daily";

    private final AutoApplySettingsService settingsService;
    private final JobRepository jobRepository;
    private final ProfileRepository profileRepository;
    private final ApplicationPipelineService pipeline;
    private final QuotaService quotaService;
    private final JdbcTemplate db;
    private final AuditLogWriter audit;

    public AutoApplyBatchService(AutoApplySettingsService settingsService,
                                 JobRepository jobRepository,
                                 ProfileRepository profileRepository,
                                 ApplicationPipelineService pipeline,
                                 QuotaService quotaService,
                                 JdbcTemplate db,
                                 AuditLogWriter audit) {
        this.settingsService = settingsService;
        this.jobRepository = jobRepository;
        this.profileRepository = profileRepository;
        this.pipeline = pipeline;
        this.quotaService = quotaService;
        this.db = db;
        this.audit = audit;
    }

    public record PreviewItem(UUID jobId, String jobTitle, String companyName, Integer matchScore, String predictedOutcome, String detail) {}
    public record PreviewResult(int jobsConsidered, int jobsEligible, List<PreviewItem> items) {}

    public static final class BatchDisabledException extends RuntimeException {
        public BatchDisabledException(String message) { super(message); }
    }

    public static final class ProfileNotReadyException extends RuntimeException {
        public ProfileNotReadyException(String message) { super(message); }
    }

    /**
     * Read-only prediction of what a run would do right now, against the
     * candidate's current settings and current backlog — no reservation, no
     * application is created, nothing is persisted. Used so a candidate can
     * see the effect of their settings before explicitly starting a run.
     */
    public PreviewResult preview(UUID profileId) {
        AutoApplySettingsService.Settings settings = settingsService.get(profileId);
        List<JobRepository.FeedItem> candidates = fetchCandidates(profileId, settings);
        long usedToday = usedToday(profileId);

        List<PreviewItem> items = new ArrayList<>();
        int eligible = 0;
        long wouldReserve = 0;
        for (JobRepository.FeedItem item : candidates) {
            Integer score = item.matchScore();
            if (score == null || score < settings.minScore()) {
                items.add(new PreviewItem(item.job().id(), item.job().title(), item.job().companyNameRaw(),
                        score, "SKIPPED_THRESHOLD", "Below your configured minimum score (" + settings.minScore() + ")"));
                continue;
            }
            eligible++;
            if (hasLiveApplication(profileId, item.job().id())) {
                items.add(new PreviewItem(item.job().id(), item.job().title(), item.job().companyNameRaw(),
                        score, "SKIPPED_DUPLICATE", "An application already exists for this job"));
                continue;
            }
            if (usedToday + wouldReserve >= settings.dailyCap()) {
                items.add(new PreviewItem(item.job().id(), item.job().title(), item.job().companyNameRaw(),
                        score, "QUOTA_LIMITED", "Your daily cap (" + settings.dailyCap() + ") would be reached"));
                continue;
            }
            wouldReserve++;
            items.add(new PreviewItem(item.job().id(), item.job().title(), item.job().companyNameRaw(),
                    score, "WOULD_PREPARE", null));
        }
        return new PreviewResult(candidates.size(), eligible, items);
    }

    /**
     * Runs one bounded batch now. Throws {@link BatchDisabledException} if the
     * candidate has not explicitly enabled the feature, and
     * {@link ProfileNotReadyException} if their profile is not ready for a
     * valid application — in both cases NOTHING is attempted, per the
     * requirement that a disabled configuration or an insufficient profile
     * must never fabricate progress.
     */
    public UUID runBatch(UUID profileId) {
        AutoApplySettingsService.Settings settings = settingsService.get(profileId);
        if (!settings.enabled()) {
            throw new BatchDisabledException("Auto Apply batch preparation is disabled. Enable it in settings first.");
        }
        boolean ready = profileRepository.findById(profileId)
                .map(p -> "READY".equals(p.setupStatus()))
                .orElse(false);
        if (!ready) {
            throw new ProfileNotReadyException(
                    "Your profile setup is incomplete, so a valid application cannot be prepared. "
                            + "Complete your profile first.");
        }

        UUID runId = UuidV7.generate();
        db.update("insert into auto_apply_runs (id, profile_id, status) values (?, ?, 'RUNNING')", runId, profileId);
        audit.write(new AuditEntry("SYSTEM", "AUTO_APPLY_BATCH_STARTED", "PROFILE", profileId,
                null, Map.of("runId", runId.toString()), null, UuidV7.generate()));

        int considered = 0, eligible = 0, prepared = 0, skippedDuplicate = 0, skippedThreshold = 0,
                skippedMissingProfile = 0, skippedUnsupported = 0, quotaLimited = 0, failures = 0;
        try {
            List<JobRepository.FeedItem> candidates = fetchCandidates(profileId, settings);
            considered = candidates.size();

            boolean quotaExhausted = false;
            for (JobRepository.FeedItem item : candidates) {
                UUID jobId = item.job().id();
                Integer score = item.matchScore();

                if (score == null || score < settings.minScore()) {
                    skippedThreshold++;
                    recordItem(runId, jobId, score, "SKIPPED_THRESHOLD",
                            "Below the configured minimum score (" + settings.minScore() + ")");
                    continue;
                }
                eligible++;

                if (quotaExhausted) {
                    quotaLimited++;
                    recordItem(runId, jobId, score, "QUOTA_LIMITED", "Daily cap (" + settings.dailyCap() + ") already reached this run");
                    continue;
                }

                QuotaService.ReservationResult reservation = quotaService.tryReserve(profileId, QUOTA_KEY, 1, settings.dailyCap());
                if (!reservation.reserved()) {
                    quotaExhausted = true;
                    quotaLimited++;
                    recordItem(runId, jobId, score, "QUOTA_LIMITED",
                            "Daily cap reached (" + (int) reservation.used() + "/" + (int) reservation.limit() + ")");
                    continue;
                }

                boolean released = false;
                try {
                    ApplicationPipelineService.CreatedApplication created = pipeline.createApplicationFromMatch(profileId, jobId);
                    if (created == null || created.applicationId() == null) {
                        quotaService.release(profileId, QUOTA_KEY, 1, reservation.periodStart());
                        released = true;
                        failures++;
                        recordItem(runId, jobId, score, "FAILED", "No application could be created");
                        continue;
                    }
                    if (!created.created()) {
                        // Idempotent replay: a live application already existed
                        // (created by this same batch feature earlier, by the
                        // continuous engine, or by the candidate directly).
                        quotaService.release(profileId, QUOTA_KEY, 1, reservation.periodStart());
                        released = true;
                        skippedDuplicate++;
                        recordApplicationItem(runId, jobId, created.applicationId(), score, "SKIPPED_DUPLICATE",
                                "An application already exists for this job");
                        continue;
                    }
                    prepared++;
                    recordApplicationItem(runId, jobId, created.applicationId(), score, "PREPARED",
                            "Application created; tailored CV, cover letter and answers are being prepared");
                } catch (DuplicateApplicationException duplicate) {
                    quotaService.release(profileId, QUOTA_KEY, 1, reservation.periodStart());
                    released = true;
                    skippedDuplicate++;
                    recordItem(runId, jobId, score, "SKIPPED_DUPLICATE", duplicate.matchReason());
                } catch (Exception e) {
                    if (!released) quotaService.release(profileId, QUOTA_KEY, 1, reservation.periodStart());
                    failures++;
                    log.warn("Auto Apply batch: preparing job {} failed for profile {}: {}", jobId, profileId, e.toString());
                    recordItem(runId, jobId, score, "FAILED", e.getClass().getSimpleName());
                }
            }

            db.update("""
                    update auto_apply_runs set status = 'COMPLETED', completed_at = now(),
                        jobs_considered = ?, jobs_eligible = ?, prepared = ?, skipped_duplicate = ?,
                        skipped_threshold = ?, skipped_missing_profile = ?, skipped_unsupported_flow = ?,
                        quota_limited = ?, failures = ?
                    where id = ?
                    """, considered, eligible, prepared, skippedDuplicate, skippedThreshold,
                    skippedMissingProfile, skippedUnsupported, quotaLimited, failures, runId);
            audit.write(new AuditEntry("SYSTEM", "AUTO_APPLY_BATCH_COMPLETED", "PROFILE", profileId,
                    null, Map.of("runId", runId.toString(), "prepared", prepared, "considered", considered),
                    null, UuidV7.generate()));
        } catch (Exception e) {
            // A single failing job is handled above and never reaches here —
            // this only catches a failure in the run's own setup (e.g. the
            // candidate query itself), so the run is honestly marked FAILED
            // rather than silently reported as a successful empty run.
            log.error("Auto Apply batch run {} failed for profile {}", runId, profileId, e);
            db.update("""
                    update auto_apply_runs set status = 'FAILED', completed_at = now(),
                        jobs_considered = ?, jobs_eligible = ?, prepared = ?, skipped_duplicate = ?,
                        skipped_threshold = ?, skipped_missing_profile = ?, skipped_unsupported_flow = ?,
                        quota_limited = ?, failures = ?, error = ?
                    where id = ?
                    """, considered, eligible, prepared, skippedDuplicate, skippedThreshold,
                    skippedMissingProfile, skippedUnsupported, quotaLimited, failures,
                    e.getClass().getSimpleName(), runId);
            audit.write(new AuditEntry("SYSTEM", "AUTO_APPLY_BATCH_FAILED", "PROFILE", profileId,
                    null, Map.of("runId", runId.toString(), "error", e.getClass().getSimpleName()), null, UuidV7.generate()));
        }
        return runId;
    }

    /**
     * The candidate's current matched-job backlog, ordered by persisted
     * score, filtered per settings. A job this candidate has never been
     * scored against (no {@code job_matches} row) is not a batch candidate at
     * all — it is excluded here rather than counted as "considered": {@code
     * findFeed} left-joins the shared job catalogue and coalesces a missing
     * score to sort last, it does not filter such jobs out, and this is the
     * one place that distinction has to be enforced so "jobs considered"
     * means "jobs this candidate has a persisted match for", never "every
     * job in the catalogue, scored or not".
     */
    private List<JobRepository.FeedItem> fetchCandidates(UUID profileId, AutoApplySettingsService.Settings settings) {
        JobRepository.FeedFilters filters = new JobRepository.FeedFilters(
                settings.location(), settings.remoteType(), settings.employmentType(),
                null, settings.salaryMin(), null, null);
        // One page, sized to the configured batch bound plus headroom for
        // items that will turn out ineligible (below threshold, duplicate) —
        // fetched once, in descending match-score order, never re-scored.
        int fetchLimit = Math.min(200, Math.max(settings.maxBatchSize() * 4, settings.maxBatchSize()));
        JobRepository.FeedPage page = jobRepository.findFeed(profileId, null, null, filters, "match", fetchLimit, null);
        List<JobRepository.FeedItem> scored = page.items().stream().filter(i -> i.matchScore() != null).toList();
        return scored.size() > settings.maxBatchSize() ? scored.subList(0, settings.maxBatchSize()) : scored;
    }

    private boolean hasLiveApplication(UUID profileId, UUID jobId) {
        Integer count = db.queryForObject("""
                select count(*) from applications where profile_id = ? and job_id = ? and status not in ('FAILED','WITHDRAWN')
                """, Integer.class, profileId, jobId);
        return count != null && count > 0;
    }

    private long usedToday(UUID profileId) {
        List<Map<String, Object>> rows = db.queryForList(
                "select used from usage_quotas where profile_id = ? and quota_key = ? and period_start = current_date",
                profileId, QUOTA_KEY);
        if (rows.isEmpty()) return 0;
        Object used = rows.get(0).get("used");
        return used instanceof Number n ? n.longValue() : 0;
    }

    private void recordItem(UUID runId, UUID jobId, Integer score, String outcome, String detail) {
        db.update("""
                insert into auto_apply_run_items (id, run_id, job_id, match_score, outcome, detail)
                values (?, ?, ?, ?, ?, ?)
                """, UuidV7.generate(), runId, jobId, score, outcome, detail);
    }

    private void recordApplicationItem(UUID runId, UUID jobId, UUID applicationId, Integer score, String outcome, String detail) {
        db.update("""
                insert into auto_apply_run_items (id, run_id, job_id, application_id, match_score, outcome, detail)
                values (?, ?, ?, ?, ?, ?, ?)
                """, UuidV7.generate(), runId, jobId, applicationId, score, outcome, detail);
    }

    // ── Run history ──────────────────────────────────────────────────

    public record RunSummary(UUID id, String status, int jobsConsidered, int jobsEligible, int prepared,
                             int skippedDuplicate, int skippedThreshold, int skippedMissingProfile,
                             int skippedUnsupportedFlow, int quotaLimited, int failures,
                             Instant startedAt, Instant completedAt, String error) {}

    public List<RunSummary> history(UUID profileId, int limit) {
        int bounded = Math.max(1, Math.min(limit, 100));
        return db.query("""
                select id, status, jobs_considered, jobs_eligible, prepared, skipped_duplicate,
                       skipped_threshold, skipped_missing_profile, skipped_unsupported_flow,
                       quota_limited, failures, started_at, completed_at, error
                from auto_apply_runs where profile_id = ? order by started_at desc limit ?
                """, (rs, n) -> new RunSummary(
                        (UUID) rs.getObject("id"), rs.getString("status"),
                        rs.getInt("jobs_considered"), rs.getInt("jobs_eligible"), rs.getInt("prepared"),
                        rs.getInt("skipped_duplicate"), rs.getInt("skipped_threshold"), rs.getInt("skipped_missing_profile"),
                        rs.getInt("skipped_unsupported_flow"), rs.getInt("quota_limited"), rs.getInt("failures"),
                        rs.getTimestamp("started_at").toInstant(),
                        rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant(),
                        rs.getString("error")),
                profileId, bounded);
    }

    /** One run's summary, owner-scoped. Empty when missing or not owned by this profile. */
    public Optional<RunSummary> findRun(UUID profileId, UUID runId) {
        return db.query("""
                select id, status, jobs_considered, jobs_eligible, prepared, skipped_duplicate,
                       skipped_threshold, skipped_missing_profile, skipped_unsupported_flow,
                       quota_limited, failures, started_at, completed_at, error
                from auto_apply_runs where id = ? and profile_id = ?
                """, (rs, n) -> new RunSummary(
                        (UUID) rs.getObject("id"), rs.getString("status"),
                        rs.getInt("jobs_considered"), rs.getInt("jobs_eligible"), rs.getInt("prepared"),
                        rs.getInt("skipped_duplicate"), rs.getInt("skipped_threshold"), rs.getInt("skipped_missing_profile"),
                        rs.getInt("skipped_unsupported_flow"), rs.getInt("quota_limited"), rs.getInt("failures"),
                        rs.getTimestamp("started_at").toInstant(),
                        rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant(),
                        rs.getString("error")),
                runId, profileId).stream().findFirst();
    }

    public record RunItem(UUID jobId, String jobTitle, String companyName, UUID applicationId,
                          Integer matchScore, String outcome, String detail, Instant createdAt) {}

    /** Items for one run, owner-scoped via the run (never trusts a bare item id). */
    public List<RunItem> findRunItems(UUID profileId, UUID runId) {
        if (findRun(profileId, runId).isEmpty()) return List.of();
        return db.query("""
                select i.job_id, j.title as job_title, j.company_name_raw as company_name,
                       i.application_id, i.match_score, i.outcome, i.detail, i.created_at
                from auto_apply_run_items i
                join jobs j on j.id = i.job_id
                where i.run_id = ?
                order by i.created_at
                """, (rs, n) -> new RunItem(
                        (UUID) rs.getObject("job_id"), rs.getString("job_title"), rs.getString("company_name"),
                        (UUID) rs.getObject("application_id"),
                        rs.getObject("match_score") == null ? null : rs.getInt("match_score"),
                        rs.getString("outcome"), rs.getString("detail"),
                        rs.getTimestamp("created_at").toInstant()),
                runId);
    }
}
