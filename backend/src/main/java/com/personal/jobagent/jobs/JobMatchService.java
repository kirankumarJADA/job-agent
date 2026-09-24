package com.personal.jobagent.jobs;

import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.notifications.NotificationEvents;
import com.personal.jobagent.notifications.NotificationService;
import com.personal.jobagent.profile.ProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Feature 8 producer: the "job matched" moment of the pipeline.
 *
 * Weights a discovered job against the candidate profile (skill overlap,
 * remote fit, salary fit), stores the decision on the job row (status
 * SCORED + score metadata) and — when the recommendation is APPLY — emits
 * a JOB_MATCHED event through the transactional outbox so the notification
 * fan-out fires. The outbox append happens in the SAME transaction as the
 * job-row update (TransactionTemplate), preserving the outbox atomicity
 * guarantee: crash before commit loses both, crash after commit delivers
 * the notification via replay.
 *
 * Scoring is deterministic on purpose: the match decision is a
 * reproducible business rule, not an LLM opinion, so notifications and
 * their dedup keys stay stable across replays.
 */
@Service
public class JobMatchService {

    private static final Logger log = LoggerFactory.getLogger(JobMatchService.class);

    /** ≥ APPLY, ≥ REVIEW, otherwise SKIP. */
    static final int APPLY_THRESHOLD = 70;
    static final int REVIEW_THRESHOLD = 50;

    private final JobRepository jobRepository;
    private final ProfileRepository profileRepository;
    private final JdbcTemplate jdbcTemplate;
    private final NotificationService notificationService;
    private final TransactionTemplate transactionTemplate;

    public JobMatchService(JobRepository jobRepository,
                           ProfileRepository profileRepository,
                           JdbcTemplate jdbcTemplate,
                           NotificationService notificationService,
                           PlatformTransactionManager transactionManager) {
        this.jobRepository = jobRepository;
        this.profileRepository = profileRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.notificationService = notificationService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public record MatchResult(UUID jobId, int overall, String recommendation,
                              double skillOverlap, double remoteFit, double salaryFit,
                              boolean notified) {
    }

    /**
     * @param desiredSalaryMin candidate's minimum acceptable salary (may be null)
     * @param desiredRemoteType REMOTE|HYBRID|ONSITE (may be null = no constraint)
     */
    public MatchResult evaluateMatch(UUID jobId, UUID profileId,
                                     Number desiredSalaryMin, String desiredRemoteType) {
        JobRecord job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));

        double skillOverlap = computeSkillOverlap(job, profileId);
        double remoteFit = computeRemoteFit(job, desiredRemoteType);
        double salaryFit = computeSalaryFit(job, desiredSalaryMin);

        int overall = (int) Math.round(100 * (0.6 * skillOverlap + 0.2 * remoteFit + 0.2 * salaryFit));
        String recommendation = overall >= APPLY_THRESHOLD ? "APPLY"
                : overall >= REVIEW_THRESHOLD ? "REVIEW" : "SKIP";

        // Persist the decision + emit the event atomically.
        boolean notified = Boolean.TRUE.equals(transactionTemplate.execute(tx -> {
            // The decision belongs to (job, profile) — it is a function of THIS
            // candidate's skills and preferences — so it is stored against the
            // owner in job_matches, not on the shared jobs row where a second
            // candidate scoring the same posting would overwrite the first
            // (V022 moved it out; that is what those three columns used to be).
            jdbcTemplate.update("""
                    insert into job_matches (profile_id, job_id, score, recommendation, breakdown, scored_at)
                    values (?, ?, ?, ?, ?::jsonb, now())
                    on conflict (profile_id, job_id) do update set
                        score = excluded.score,
                        recommendation = excluded.recommendation,
                        breakdown = excluded.breakdown,
                        scored_at = now()
                    """,
                    profileId,
                    jobId,
                    overall,
                    recommendation,
                    breakdownJson(skillOverlap, remoteFit, salaryFit));

            // Catalogue-level marker only: "this posting has been evaluated".
            // Shared on purpose and carrying no candidate data, which is why it
            // stays on the shared row while the decision itself does not.
            jdbcTemplate.update("""
                    update jobs set status = 'SCORED'
                    where id = ? and status in ('DISCOVERED','ANALYSED')
                    """, jobId);

            if (!"APPLY".equals(recommendation)) {
                return false;
            }

            Map<String, Object> payload = new HashMap<>();
            payload.put("job_id", jobId.toString());
            // Explicit owner: this event aggregates on the JOB, which is shared
            // between candidates, so the fan-out cannot derive the recipient from
            // the aggregate and would otherwise file the notification as a
            // system notice nobody sees.
            payload.put("profile_id", profileId.toString());
            payload.put("job_title", job.title());
            payload.put("company", job.companyNameRaw() != null ? job.companyNameRaw() : "");
            payload.put("score", overall);
            payload.put("recommendation", recommendation);
            payload.put("message", "New job match — " + job.title()
                    + (job.companyNameRaw() != null ? " at " + job.companyNameRaw() : ""));
            payload.put("detail", "Scored " + overall + "/100 (skills "
                    + Math.round(skillOverlap * 100) + "%, remote " + Math.round(remoteFit * 100)
                    + "%, salary " + Math.round(salaryFit * 100) + "%)");

            notificationService.emit(new NotificationService.NotificationCommand(
                    NotificationEvents.JOB_MATCHED,
                    "JOB",
                    jobId,
                    payload,
                    UuidV7.generate(),
                    null));
            return true;
        }));

        log.info("Job {} matched: score={} recommendation={} notified={}", jobId, overall, recommendation, notified);
        return new MatchResult(jobId, overall, recommendation, skillOverlap, remoteFit, salaryFit, notified);
    }

    private double computeSkillOverlap(JobRecord job, UUID profileId) {
        if (job.skillsExtracted() == null || job.skillsExtracted().isEmpty()) {
            return 0.5; // unknown requirements — neutral, not zero
        }
        Set<String> candidateSkills = profileRepository.findSkills(profileId).stream()
                .map(s -> s.name() == null ? "" : s.name().trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toSet());
        if (candidateSkills.isEmpty()) {
            return 0.0;
        }
        long matched = job.skillsExtracted().stream()
                .filter(s -> s != null && candidateSkills.contains(s.trim().toLowerCase(Locale.ROOT)))
                .count();
        return (double) matched / job.skillsExtracted().size();
    }

    private double computeRemoteFit(JobRecord job, String desiredRemoteType) {
        if (desiredRemoteType == null || desiredRemoteType.isBlank()) {
            return 0.5;
        }
        return desiredRemoteType.trim().equalsIgnoreCase(job.remoteType()) ? 1.0 : 0.0;
    }

    private double computeSalaryFit(JobRecord job, Number desiredSalaryMin) {
        if (job.salaryMax() == null || desiredSalaryMin == null) {
            return 0.5;
        }
        double wanted = desiredSalaryMin.doubleValue();
        if (wanted <= 0) {
            return 1.0;
        }
        double offering = job.salaryMax().doubleValue();
        if (offering >= wanted) {
            return 1.0;
        }
        double ratio = offering / wanted;
        return Math.max(0.0, Math.min(1.0, (ratio - 0.8) / 0.2)); // 0 below 80%, 1 at 100%
    }

    private String breakdownJson(double skillOverlap, double remoteFit, double salaryFit) {
        return "{\"skill_overlap\":" + Math.round(skillOverlap * 100)
                + ",\"remote_fit\":" + Math.round(remoteFit * 100)
                + ",\"salary_fit\":" + Math.round(salaryFit * 100) + "}";
    }
}
