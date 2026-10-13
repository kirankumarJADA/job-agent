package com.personal.jobagent.application;

import com.personal.jobagent.apply.ApplicationIdentityService;
import com.personal.jobagent.apply.DuplicateApplicationException;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.jobs.HardFilterResult;
import com.personal.jobagent.jobs.HardFilterService;
import com.personal.jobagent.jobs.JobMatchService;
import com.personal.jobagent.jobs.JobRecord;
import com.personal.jobagent.jobs.JobRepository;
import com.personal.jobagent.notifications.NotificationEvents;
import com.personal.jobagent.notifications.NotificationService;
import com.personal.jobagent.preferences.PreferenceSetRepository;
import com.personal.jobagent.profile.ProfileRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Connects the existing discovery, matching and application foundations into
 * one pipeline — this class adds dispatch, not a new architecture.
 *
 * <p><b>Ingestion → matching.</b> {@link #onJobIngested} is invoked once per
 * successful ingestion (see {@code JobDiscoveryService.ingestJob}) and runs
 * the existing {@link JobMatchService#evaluateMatch} for every candidate
 * profile. The match is idempotent by construction: {@code job_matches} is
 * upserted on {@code (profile_id, job_id)}, so replaying an ingestion never
 * duplicates match state. Matching all profiles is what makes the pipeline
 * multi-user-correct — each decision is stored against its owner (V022).
 *
 * <p><b>Match → application.</b> Creation is deliberately NOT synchronous
 * with the match. {@code evaluateMatch} already emits {@code JOB_MATCHED}
 * through the transactional outbox (atomically with the {@code job_matches}
 * write), and {@link com.personal.jobagent.application.ApplicationPipelineEventHandler}
 * consumes that event: an APPLY recommendation creates exactly one
 * application per profile/job, which then flows to preparation via a second
 * event. Crash-safety comes from the outbox (at-least-once delivery) plus
 * idempotent creation — the combination is exactly-once effect.
 *
 * <p><b>Matching policy.</b> The scorer, thresholds (APPLY ≥ 70, REVIEW ≥ 50)
 * and profile-ownership semantics are the existing ones, untouched. Candidate
 * salary expectations come from the profile's active preference set;
 * the remote constraint is passed as unconstrained (null) because the
 * existing scorer signature takes a single remote type while preferences
 * carry a list, and jobs discovered from structured boards often have no
 * workplace type at all — passing a constraint would zero the remote fit for
 * unknown-workplace jobs. Extending the scorer for list-valued remote
 * preferences is a separate, explicit change.
 */
@Service
public class ApplicationPipelineService {

    private static final Logger log = LoggerFactory.getLogger(ApplicationPipelineService.class);

    private final HardFilterService hardFilterService;
    private final JobMatchService jobMatchService;
    private final JobRepository jobRepository;
    private final PreferenceSetRepository preferenceSets;
    private final ProfileRepository profileRepository;
    private final JdbcTemplate db;
    private final NotificationService notifications;
    private final ObjectMapper objectMapper;
    private final ApplicationIdentityService identityService;
    private final TransactionTemplate transactionTemplate;

    public ApplicationPipelineService(HardFilterService hardFilterService,
                                      JobMatchService jobMatchService,
                                      JobRepository jobRepository,
                                      PreferenceSetRepository preferenceSets,
                                      ProfileRepository profileRepository,
                                      JdbcTemplate db,
                                      NotificationService notifications,
                                      ObjectMapper objectMapper,
                                      ApplicationIdentityService identityService,
                                      PlatformTransactionManager transactionManager) {
        this.hardFilterService = hardFilterService;
        this.jobMatchService = jobMatchService;
        this.jobRepository = jobRepository;
        this.preferenceSets = preferenceSets;
        this.profileRepository = profileRepository;
        this.db = db;
        this.notifications = notifications;
        this.objectMapper = objectMapper;
        this.identityService = identityService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        // Creation runs in its OWN transaction so a duplicate-key race rolls
        // back only the insert (and its timeline/notification writes), leaving
        // the caller's transaction usable for the duplicate resolution below.
        this.transactionTemplate.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record CreatedApplication(UUID applicationId, boolean created, String status) {}

    /**
     * Pipeline entry point: called after a job row was successfully ingested
     * (INSERTED, UPDATED or TOUCHED). Matches the job for every candidate
     * profile. Never throws — a pipeline failure must not fail the ingestion
     * that already committed; re-discovery self-heals (every successful
     * ingestion re-triggers matching, and matching is idempotent).
     */
    public void onJobIngested(UUID jobId, String action) {
        JobRecord job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            log.warn("Pipeline skip: job {} not found", jobId);
            return;
        }

        List<UUID> profiles = profileRepository.findAllIds();
        for (UUID profileId : profiles) {
            try {
                var preferences = preferenceSets.findActiveByProfileId(profileId).orElse(null);

                // Hard filters run FIRST, before expensive scoring
                HardFilterResult filterResult = hardFilterService.evaluate(job, preferences);
                if (!filterResult.passed()) {
                    persistFilterRejection(jobId, filterResult);
                    log.debug("Pipeline hard-filter REJECT job={} profile={} reasons={}",
                            jobId, profileId, filterResult.reasons().size());
                    continue; // skip scoring entirely
                }

                // Scoring only runs for jobs that pass all hard filters
                Number desiredSalaryMin = preferences == null ? null : preferences.salaryMinGbp();
                var match = jobMatchService.evaluateMatch(jobId, profileId, desiredSalaryMin, null);
                log.debug("Pipeline match job={} profile={} score={} recommendation={}",
                        jobId, profileId, match.overall(), match.recommendation());
            } catch (Exception e) {
                log.error("Automatic matching failed for job={} profile={}", jobId, profileId, e);
            }
        }
    }

    /**
     * Persists the hard-filter rejection: writes structured filter_reasons
     * to the jobs table and sets status to FILTERED_OUT.
     */
    private void persistFilterRejection(UUID jobId, HardFilterResult result) {
        try {
            String reasonsJson = objectMapper.writeValueAsString(
                    result.reasons().stream().map(r -> java.util.Map.of(
                            "criterion", r.criterion(),
                            "expected", r.expected(),
                            "actual", r.actual(),
                            "message", r.message()
                    )).toList());

            db.update(
                    "update jobs set filter_reasons = ?::jsonb, status = 'FILTERED_OUT' where id = ? and status = 'DISCOVERED'",
                    reasonsJson, jobId);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize filter reasons for job={}", jobId, e);
        }
    }

    /**
     * Creates the profile's application for a job the match scored APPLY —
     * exactly one live application per (profile, job), enforced by the
     * pre-check plus the existing partial unique index {@code
     * applications_profile_job_live_uq}. The insert, the creation timeline
     * row and the {@code application.created} outbox event commit in ONE
     * transaction, so a crash can never leave an application without its
     * preparation trigger.
     *
     * @return the application id and whether THIS call created the row
     *         (false = a live application already existed — idempotent replay)
     */
    public CreatedApplication createApplicationFromMatch(UUID profileId, UUID jobId) {
        // Fast path: an existing live application means this is a replay.
        Optional<UUID> existing = findLiveApplication(profileId, jobId);
        if (existing.isPresent()) {
            return new CreatedApplication(existing.get(), false, statusOf(existing.get()));
        }

        // Cross-source duplicate protection (Phase 8.2): the same role seen
        // through another board already has one of this owner's applications.
        // Owner-scoped by construction — never another candidate's record.
        String identityKey = identityService.identityKeyForJob(jobId);
        for (var duplicate : identityService.findDuplicates(profileId, jobId)) {
            throw new DuplicateApplicationException(duplicate.applicationId(), duplicate.jobId(),
                    duplicate.identityKey(), duplicate.matchReason());
        }

        String mode = preferenceSets.findActiveByProfileId(profileId)
                .map(p -> p.applicationMode() == null || p.applicationMode().isBlank()
                        ? "ASSISTED" : p.applicationMode())
                .orElse("ASSISTED");
        UUID applicationId = UuidV7.generate();

        CreatedApplication created;
        try {
            created = transactionTemplate.execute(tx -> {
                // The pre-check ran outside the transaction, so the insert is
                // the arbiter: the partial unique index (V022) rejects a second
                // live application for the same (profile, job), and V036's
                // identity index rejects a second live application for the
                // same role across sources. The conflict target names the
                // one-per-job index only, so an identity violation surfaces as
                // a constraint error instead of being silently swallowed.
                int inserted = db.update("""
                        insert into applications (id, job_id, profile_id, mode, identity_key)
                        values (?, ?, ?, ?, ?)
                        on conflict (profile_id, job_id) where status not in ('FAILED','WITHDRAWN') do nothing
                        """, applicationId, jobId, profileId, mode, identityKey);

                if (inserted == 0) {
                    UUID winner = findLiveApplication(profileId, jobId)
                            .orElseThrow(() -> new IllegalStateException(
                                    "application insert conflicted but no live row exists for " + profileId + "/" + jobId));
                    return new CreatedApplication(winner, false, statusOf(winner));
                }
                db.update("""
                    insert into application_events (id, application_id, type, payload, actor)
                    values (?, ?, 'APPLICATION_CREATED', ?::jsonb, 'SYSTEM')
                    """, UuidV7.generate(), applicationId,
                    "{\"event_key\":\"application-created:" + applicationId
                            + "\",\"source\":\"auto-match\",\"job_id\":\"" + jobId + "\"}");

                var job = jobTitle(profileId, jobId);
                notifications.emit(new NotificationService.NotificationCommand(
                    NotificationEvents.APPLICATION_CREATED,
                    "APPLICATION",
                    applicationId,
                    Map.of(
                            "application_id", applicationId.toString(),
                            "profile_id", profileId.toString(),
                            "job_id", jobId.toString(),
                            "job_title", job.jobTitle(),
                            "company", job.company(),
                            "message", "Application created — " + job.jobTitle() + " at " + job.company(),
                            "detail", "Matched with an APPLY recommendation. CV, cover letter and answers are being prepared.",
                            "dedup_key", "application-created:" + applicationId
                    ),
                    UuidV7.generate(),
                    null));

                log.info("Pipeline created application {} for profile={} job={} (mode={})",
                        applicationId, profileId, jobId, mode);
                return new CreatedApplication(applicationId, true, "READY_TO_APPLY");
            });
        } catch (DuplicateKeyException e) {
            // Lost a race with a concurrent create. The failed transaction
            // rolled back entirely, so the winner is resolved fresh: the same
            // (profile, job) row makes this an idempotent replay; a live row
            // with the same identity key is a cross-source duplicate.
            Optional<UUID> sameJob = findLiveApplication(profileId, jobId);
            if (sameJob.isPresent()) {
                return new CreatedApplication(sameJob.get(), false, statusOf(sameJob.get()));
            }
            for (var duplicate : identityService.findDuplicates(profileId, jobId)) {
                throw new DuplicateApplicationException(duplicate.applicationId(), duplicate.jobId(),
                        duplicate.identityKey(), duplicate.matchReason());
            }
            if (identityKey != null) {
                for (var duplicate : identityService.findByIdentity(profileId, identityKey)) {
                    throw new DuplicateApplicationException(duplicate.applicationId(), duplicate.jobId(),
                            duplicate.identityKey(), duplicate.matchReason());
                }
            }
            throw e;
        }
        return created;
    }

    private record JobSummary(String jobTitle, String company) {}

    private JobSummary jobTitle(UUID profileId, UUID jobId) {
        var rows = db.queryForList(
                "select title, coalesce(company_name_raw, '') as company from jobs where id = ?", jobId);
        if (rows.isEmpty()) {
            throw new IllegalStateException("job vanished during application creation: " + jobId);
        }
        return new JobSummary(String.valueOf(rows.get(0).get("title")), String.valueOf(rows.get(0).get("company")));
    }

    private Optional<UUID> findLiveApplication(UUID profileId, UUID jobId) {
        return db.query("""
                select id from applications
                where profile_id = ? and job_id = ? and status not in ('FAILED','WITHDRAWN')
                limit 1
                """, (rs, n) -> (UUID) rs.getObject(1), profileId, jobId).stream().findFirst();
    }

    private String statusOf(UUID applicationId) {
        String status = db.queryForObject("select status from applications where id = ?",
                String.class, applicationId);
        return status == null ? "READY_TO_APPLY" : status;
    }
}
