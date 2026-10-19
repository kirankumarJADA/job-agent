package com.personal.jobagent.jobs;

import com.personal.jobagent.common.AutomationMetrics;
import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.notifications.NotificationEvents;
import com.personal.jobagent.notifications.NotificationService;
import com.personal.jobagent.preferences.PreferenceSetRecord;
import com.personal.jobagent.preferences.ScoringWeights;
import com.personal.jobagent.profile.ProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Feature 8 producer: the "job matched" moment of the pipeline.
 *
 * <p>Weights a discovered job against the candidate profile using the seven
 * validated {@code scoringWeights} categories ({@code skill, experience,
 * visa, location, salary, career, difficulty} — see
 * {@link com.personal.jobagent.preferences.ScoringWeights}): skill overlap
 * and salary fit are always evaluated; experience, visa and location are
 * evaluated only when the candidate has actually expressed a preference for
 * that dimension (an unconstrained category is excluded from the weighted
 * average entirely, not neutrally scored — see {@link CategoryScore});
 * career and difficulty have no computed signal anywhere in this codebase
 * today and are always excluded, honestly, rather than pretended to be used.
 * Workplace-type (remote/hybrid/onsite) fit is computed and reported for
 * context but is not one of the seven weighted categories — a mismatch there
 * is already a hard filter upstream. Stores the decision on the job row
 * (status SCORED + score metadata) and — when the recommendation is APPLY —
 * emits a JOB_MATCHED event through the transactional outbox so the
 * notification fan-out fires. The outbox append happens in the SAME
 * transaction as the job-row update (TransactionTemplate), preserving the
 * outbox atomicity guarantee: crash before commit loses both, crash after
 * commit delivers the notification via replay.
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
    private final AutomationMetrics metrics;
    private final SemanticSkillMatcher semanticSkillMatcher;

    public JobMatchService(JobRepository jobRepository,
                           SemanticSkillMatcher semanticSkillMatcher,
                           ProfileRepository profileRepository,
                           JdbcTemplate jdbcTemplate,
                           NotificationService notificationService,
                           PlatformTransactionManager transactionManager,
                           AutomationMetrics metrics) {
        this.jobRepository = jobRepository;
        this.profileRepository = profileRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.notificationService = notificationService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.metrics = metrics;
        this.semanticSkillMatcher = semanticSkillMatcher;
    }

    public record MatchResult(UUID jobId, int overall, String recommendation,
                              double skillOverlap, double remoteFit, double salaryFit,
                              double experienceFit, double visaFit, double locationFit,
                              boolean notified) {
    }

    /**
     * One weighted category's contribution to {@code overall}. A null
     * {@code score} means "not applicable" — the category is excluded from
     * BOTH the weighted sum and the total weight it is normalised against,
     * not neutrally scored at 0.5. This is what keeps a sparse-preference
     * profile (most or all optional constraints unset) from having its
     * score capped below what its one or two actual signals (skill, salary
     * — see {@link #evaluateMatch}, which treats those two as always
     * applicable) would otherwise earn: excluding "nothing was asked"
     * redistributes that category's weight across the categories that DO
     * have something to say, the same direction
     * {@code HardFilterService} already takes — an unconstrained filter
     * passes rather than penalising.
     */
    private record CategoryScore(String key, int weight, Double score) {
        static CategoryScore applicable(String key, int weight, double score) {
            return new CategoryScore(key, weight, score);
        }
        static CategoryScore notApplicable(String key, int weight) {
            return new CategoryScore(key, weight, null);
        }
    }

    /** The documented default (architecture doc §A3.5 / V002 seed) — used whenever a profile has no
     *  active preference set, or its scoringWeights is missing/invalid. */
    private static final Map<String, Integer> DEFAULT_WEIGHTS = Map.of(
            "skill", 30, "experience", 15, "visa", 20, "location", 10,
            "salary", 10, "career", 10, "difficulty", 5);

    /**
     * @param preferences the candidate's active preference set — salary floor, allowed experience
     *                    levels/locations/remote types, sponsorship policy, and the configured
     *                    {@code scoringWeights} all come from here; {@code null} means no active
     *                    preference set exists yet (every candidate-side constraint is then
     *                    treated as unset, and the documented default weights apply).
     */
    public MatchResult evaluateMatch(UUID jobId, UUID profileId, PreferenceSetRecord preferences) {
        JobRecord job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));

        List<String> allowedRemoteTypes = preferences == null ? List.of() : preferences.remoteTypes();
        List<String> allowedExperienceLevels = preferences == null ? List.of() : preferences.experienceLevels();
        List<String> allowedLocations = preferences == null ? List.of() : preferences.locationsAllowed();
        String sponsorshipPolicy = preferences == null ? null : preferences.sponsorshipPolicy();
        Number desiredSalaryMin = preferences == null ? null : preferences.salaryMinGbp();

        Map<String, Integer> weights = resolveWeights(preferences == null ? null : preferences.scoringWeights());

        SkillOverlap skillOverlap = computeSkillOverlap(job, profileId);
        double remoteFit = computeRemoteFit(job, allowedRemoteTypes);
        double salaryFit = computeSalaryFit(job, desiredSalaryMin);
        CategoryScore experience = computeExperienceCategory(job, allowedExperienceLevels, weights);
        CategoryScore visa = computeVisaCategory(job, sponsorshipPolicy, weights);
        CategoryScore location = computeLocationCategory(job, allowedLocations, weights);

        List<CategoryScore> categories = List.of(
                CategoryScore.applicable("skill", weights.get("skill"), skillOverlap.ratio()),
                experience,
                visa,
                location,
                CategoryScore.applicable("salary", weights.get("salary"), salaryFit),
                // "career" and "difficulty" have no computed signal anywhere in this
                // codebase today — never silently pretended to be used (see
                // CategoryScore's own javadoc): always excluded, their configured
                // weight redistributed across whichever categories above DO have
                // something to say, exactly like an unset candidate preference.
                CategoryScore.notApplicable("career", weights.get("career")),
                CategoryScore.notApplicable("difficulty", weights.get("difficulty")));
        int overall = weightedOverall(categories);
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
                    breakdownJson(job, overall, recommendation, skillOverlap, remoteFit, salaryFit,
                            desiredSalaryMin, allowedRemoteTypes, experience, visa, location, categories));

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
                    + Math.round(skillOverlap.ratio() * 100) + "%, remote " + Math.round(remoteFit * 100)
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

        metrics.matchRecorded(recommendation);
        log.info("Job {} matched: score={} recommendation={} notified={}", jobId, overall, recommendation, notified);
        return new MatchResult(jobId, overall, recommendation, skillOverlap.ratio(), remoteFit, salaryFit,
                experience.score() == null ? 0.5 : experience.score(),
                visa.score() == null ? 0.5 : visa.score(),
                location.score() == null ? 0.5 : location.score(),
                notified);
    }

    /**
     * Resolves which per-category weights actually apply: the profile's own
     * {@code scoringWeights} when present and valid (re-validated with the
     * same {@link ScoringWeights#validate} the preferences API already
     * enforces at save time — defensive, since this reads whatever is
     * currently persisted, not necessarily what just passed that check), the
     * documented default otherwise. A profile's own weights NEVER widen or
     * narrow which categories exist, only how much each contributes.
     */
    private Map<String, Integer> resolveWeights(Map<String, Object> configured) {
        if (configured == null || configured.isEmpty()) return DEFAULT_WEIGHTS;
        try {
            ScoringWeights.validate(configured);
        } catch (ScoringWeights.ValidationException e) {
            log.warn("Invalid scoringWeights on a preference set ({}); falling back to the default weights", e.getMessage());
            return DEFAULT_WEIGHTS;
        }
        Map<String, Integer> resolved = new HashMap<>();
        for (Map.Entry<String, Object> entry : configured.entrySet()) {
            resolved.put(entry.getKey(), ((Number) entry.getValue()).intValue());
        }
        return resolved;
    }

    /**
     * The weighted average over only the APPLICABLE categories — see
     * {@link CategoryScore}'s own javadoc for why "not applicable" is
     * excluded rather than neutrally scored. The {@code totalWeight == 0}
     * branch is a defensive fallback only: skill and salary are always
     * applicable in {@link #evaluateMatch}, so in practice every call has at
     * least some weight to normalise against.
     */
    private static int weightedOverall(List<CategoryScore> categories) {
        double totalWeight = 0;
        double weightedSum = 0;
        for (CategoryScore category : categories) {
            if (category.score() == null) continue;
            totalWeight += category.weight();
            weightedSum += category.weight() * category.score();
        }
        double fraction = totalWeight > 0 ? weightedSum / totalWeight : 0.5;
        return (int) Math.round(100 * fraction);
    }

    /** Deterministic skill overlap plus the evidence behind it. */
    private record SkillOverlap(double ratio, List<String> matchedSkills, int listedSkills, boolean candidateHasSkills) {}

    private SkillOverlap computeSkillOverlap(JobRecord job, UUID profileId) {
        if (job.skillsExtracted() == null || job.skillsExtracted().isEmpty()) {
            return new SkillOverlap(0.5, List.of(), 0, true); // unknown requirements — neutral, not zero
        }
        // Collect candidate skills as their original names (not lower-cased)
        // so the semantic matcher can report them meaningfully.
        Set<String> candidateSkills = profileRepository.findSkills(profileId).stream()
                .map(s -> s.name() == null ? "" : s.name().trim())
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toSet());
        if (candidateSkills.isEmpty()) {
            return new SkillOverlap(0.0, List.of(), job.skillsExtracted().size(), false);
        }

        // Delegate to the three-tier semantic matcher (exact → synonym → LLM).
        SemanticSkillMatcher.SemanticOverlap overlap =
                semanticSkillMatcher.computeOverlap(job.skillsExtracted(), candidateSkills);

        List<String> matchedSkillNames = overlap.matches().stream()
                .map(m -> m.candidateSkill())
                .toList();

        return new SkillOverlap(overlap.ratio(), matchedSkillNames,
                overlap.jobSkillCount(), overlap.candidateHasSkills());
    }

    /**
     * Workplace-type (remote/hybrid/onsite) fit. Reported in the breakdown
     * and in {@link MatchResult} for context, but deliberately NOT one of
     * the seven weighted {@code scoringWeights} categories — it has no
     * named slot in that contract, and a mismatch here is already enforced
     * upstream as a hard filter ({@code HardFilterService#checkRemoteType})
     * when the candidate has expressed a remote-type preference, so folding
     * it into the soft score too would be redundant with that hard gate.
     */
    private double computeRemoteFit(JobRecord job, List<String> allowedRemoteTypes) {
        if (allowedRemoteTypes == null || allowedRemoteTypes.isEmpty()) return 0.5;
        String jobRemote = job.remoteType();
        if (jobRemote == null || jobRemote.isBlank() || "UNKNOWN".equalsIgnoreCase(jobRemote)) return 0.5;
        boolean matched = allowedRemoteTypes.stream().anyMatch(r -> r.equalsIgnoreCase(jobRemote));
        return matched ? 1.0 : 0.0;
    }

    /**
     * The "experience" category: not applicable unless the candidate has
     * named at least one acceptable experience level (an unconstrained
     * candidate has nothing for this dimension to evaluate, exactly like an
     * unconstrained {@code HardFilterService} check passes rather than
     * penalising). When applicable, a job that does not declare its own
     * experience level is scored neutral — genuinely unknown, not a mismatch.
     */
    private CategoryScore computeExperienceCategory(JobRecord job, List<String> allowedExperienceLevels,
                                                     Map<String, Integer> weights) {
        int weight = weights.get("experience");
        if (allowedExperienceLevels == null || allowedExperienceLevels.isEmpty()) {
            return CategoryScore.notApplicable("experience", weight);
        }
        String jobLevel = job.experienceLevel();
        if (jobLevel == null || jobLevel.isBlank()) {
            return CategoryScore.applicable("experience", weight, 0.5);
        }
        boolean matched = allowedExperienceLevels.stream().anyMatch(level -> level.equalsIgnoreCase(jobLevel));
        return CategoryScore.applicable("experience", weight, matched ? 1.0 : 0.0);
    }

    /**
     * The "location" category: the same "no constraint → not applicable"
     * rule as experience, reusing {@code HardFilterService#checkLocation}'s
     * own city/country/free-text matching so this never disagrees with what
     * the hard filter already decided for the same preference.
     */
    private CategoryScore computeLocationCategory(JobRecord job, List<String> allowedLocations,
                                                   Map<String, Integer> weights) {
        int weight = weights.get("location");
        if (allowedLocations == null || allowedLocations.isEmpty()) {
            return CategoryScore.notApplicable("location", weight);
        }
        String city = job.city();
        String country = job.country();
        String locationRaw = job.locationRaw();
        if (isBlankLocation(city) && isBlankLocation(country) && isBlankLocation(locationRaw)) {
            return CategoryScore.applicable("location", weight, 0.5);
        }
        boolean matched = allowedLocations.stream().anyMatch(loc -> {
            String locLower = loc.toLowerCase(Locale.ROOT);
            return locLower.equalsIgnoreCase(city) || locLower.equalsIgnoreCase(country)
                    || (locationRaw != null && locationRaw.toLowerCase(Locale.ROOT).contains(locLower));
        });
        return CategoryScore.applicable("location", weight, matched ? 1.0 : 0.0);
    }

    private static boolean isBlankLocation(String s) {
        return s == null || s.isBlank();
    }

    /**
     * The "visa" category: applicable only when the candidate's
     * {@code sponsorshipPolicy} is REQUIRED — {@code HardFilterService}'s
     * own precedent for which policy values are restrictive at all (NONE and
     * SHOW_ALL express no constraint). When applicable, the signal is the
     * most recent per-job LLM sponsorship assessment
     * ({@code job_analyses.sponsorship_status}) — genuinely unavailable
     * (no analysis yet) scores neutral, never a penalty for Robin's own
     * analysis backlog.
     */
    private CategoryScore computeVisaCategory(JobRecord job, String sponsorshipPolicy,
                                              Map<String, Integer> weights) {
        int weight = weights.get("visa");
        if (sponsorshipPolicy == null || sponsorshipPolicy.isBlank() || !"REQUIRED".equalsIgnoreCase(sponsorshipPolicy)) {
            return CategoryScore.notApplicable("visa", weight);
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select sponsorship_status from job_analyses where job_id = ? order by created_at desc limit 1
                """, job.id());
        if (rows.isEmpty()) {
            return CategoryScore.applicable("visa", weight, 0.5);
        }
        String status = String.valueOf(rows.get(0).get("sponsorship_status"));
        double score = switch (status) {
            case "LIKELY_AVAILABLE" -> 1.0;
            case "POSSIBLE" -> 0.65;
            case "LIKELY_NOT_AVAILABLE" -> 0.15;
            case "NOT_AVAILABLE" -> 0.0;
            default -> 0.5; // UNKNOWN, or any value not in this enum — neutral, never a guess
        };
        return CategoryScore.applicable("visa", weight, score);
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

    /**
     * Persisted, human-readable decision record. Scoring itself is untouched
     * here — this adds the evidence trail the product requires: which
     * factors contributed what, which weights and thresholds produced the
     * recommendation, and an honest sentence about every neutral, missing or
     * not-applicable input (career/difficulty are always the latter — see
     * {@link CategoryScore}). Everything is derived from the same
     * deterministic inputs as the score, so a replay produces an identical
     * explanation.
     */
    private String breakdownJson(JobRecord job, int overall, String recommendation,
                                 SkillOverlap skillOverlap, double remoteFit, double salaryFit,
                                 Number desiredSalaryMin, List<String> allowedRemoteTypes,
                                 CategoryScore experience, CategoryScore visa, CategoryScore location,
                                 List<CategoryScore> categories) {
        StringBuilder why = new StringBuilder();
        if (skillOverlap.listedSkills() == 0) {
            why.append("The job lists no skills, so skill match was scored neutral (50%). ");
        } else if (!skillOverlap.candidateHasSkills()) {
            why.append("Your profile lists no skills yet, so skill match scored 0% of the ")
                    .append(skillOverlap.listedSkills()).append(" skills the job asks for. ");
        } else if (skillOverlap.matchedSkills().isEmpty()) {
            why.append("None of the ").append(skillOverlap.listedSkills())
                    .append(" skills the job asks for are on your profile. ");
        } else {
            why.append("Robin semantically matched ").append(skillOverlap.matchedSkills().size())
                    .append(" of the ").append(skillOverlap.listedSkills())
                    .append(" skills the job lists (")
                    .append(String.join(", ", skillOverlap.matchedSkills())).append("). ");
        }
        if (allowedRemoteTypes == null || allowedRemoteTypes.isEmpty()) {
            why.append("No remote preference is set on your profile — remote fit was scored neutral and not weighted. ");
        } else {
            why.append(allowedRemoteTypes.stream().anyMatch(r -> r.equalsIgnoreCase(job.remoteType()))
                    ? "The job's workplace type matches your preference (not weighted into the score; enforced as a hard filter). "
                    : "The job's workplace type (" + job.remoteType() + ") does not match your preference. ");
        }
        if (desiredSalaryMin == null) {
            why.append("No salary minimum is set — salary fit was scored neutral. ");
        } else if (job.salaryMax() == null) {
            why.append("The job declares no salary, so salary fit was scored neutral. ");
        } else if (job.salaryMax().doubleValue() >= desiredSalaryMin.doubleValue()) {
            why.append("The offered salary meets your stated minimum. ");
        } else {
            why.append("The offered salary is below your stated minimum. ");
        }
        why.append(experience.score() == null
                ? "No experience-level preference is set — the experience category did not weigh into your score. "
                : "Experience-level fit scored " + Math.round(experience.score() * 100) + "%. ");
        why.append(visa.score() == null
                ? "You have not marked sponsorship as required — the visa category did not weigh into your score. "
                : "Visa/sponsorship fit scored " + Math.round(visa.score() * 100) + "%, from Robin's own per-job assessment. ");
        why.append(location.score() == null
                ? "No location preference is set — the location category did not weigh into your score. "
                : "Location fit scored " + Math.round(location.score() * 100) + "%. ");
        why.append("Career and difficulty are part of your configured weights but have no computed signal yet in Robin "
                + "today, so they never weigh into any score — their weight is redistributed across the categories above.");

        String decision = switch (recommendation) {
            case "APPLY" -> "Score " + overall + " is at or above the APPLY threshold of "
                    + APPLY_THRESHOLD + " — it was passed to your application decision rules, which"
                    + " decide (by approval mode and auto-approval rules) whether it needs your review."
                    + " A match alone never submits an application.";
            case "REVIEW" -> "Score " + overall + " is below the APPLY threshold of " + APPLY_THRESHOLD
                    + " but at or above the REVIEW threshold of " + REVIEW_THRESHOLD
                    + " — nothing was created; you decide from here.";
            default -> "Score " + overall + " is below the REVIEW threshold of " + REVIEW_THRESHOLD
                    + " — no application was created.";
        };

        String matchedJson = skillOverlap.matchedSkills().stream()
                .map(s -> "\"" + s.replace("\"", "'") + "\"")
                .reduce((a, b) -> a + "," + b).orElse("");
        String weightsJson = categories.stream()
                .map(c -> "\"" + c.key() + "\":" + c.weight())
                .reduce((a, b) -> a + "," + b).orElse("");
        String applicableJson = categories.stream()
                .map(c -> "\"" + c.key() + "\":" + (c.score() != null))
                .reduce((a, b) -> a + "," + b).orElse("");
        return "{\"skill_overlap\":" + Math.round(skillOverlap.ratio() * 100)
                + ",\"remote_fit\":" + Math.round(remoteFit * 100)
                + ",\"salary_fit\":" + Math.round(salaryFit * 100)
                + ",\"experience_fit\":" + Math.round((experience.score() == null ? 0.5 : experience.score()) * 100)
                + ",\"visa_fit\":" + Math.round((visa.score() == null ? 0.5 : visa.score()) * 100)
                + ",\"location_fit\":" + Math.round((location.score() == null ? 0.5 : location.score()) * 100)
                + ",\"weights\":{" + weightsJson + "}"
                + ",\"applicable\":{" + applicableJson + "}"
                + ",\"thresholds\":{\"apply\":" + APPLY_THRESHOLD + ",\"review\":" + REVIEW_THRESHOLD + "}"
                + ",\"matched_skills\":[" + matchedJson + "]"
                + ",\"why\":\"" + why.toString().replace("\"", "'") + "\""
                + ",\"decision\":\"" + decision.replace("\"", "'") + "\""
                + "}";
    }
}
