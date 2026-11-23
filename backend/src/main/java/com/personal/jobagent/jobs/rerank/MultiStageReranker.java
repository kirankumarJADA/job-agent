package com.personal.jobagent.jobs.rerank;

import com.personal.jobagent.jobs.EffectiveMatchingCriteria;
import com.personal.jobagent.jobs.HardFilterService;
import com.personal.jobagent.jobs.JobRecord;
import com.personal.jobagent.jobs.SemanticSkillMatcher;
import com.personal.jobagent.jobs.SeniorityClassifier;
import com.personal.jobagent.jobs.SkillSynonymRegistry;
import com.personal.jobagent.jobs.TitleSynonymRegistry;
import com.personal.jobagent.preferences.PreferenceSetRecord;
import com.personal.jobagent.profile.ProfileRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Agent 1: Multi-stage intelligent job reranking engine.
 * <p>
 * Re-ranks candidate retrieval pools through a strict 5-stage pipeline:
 * 1. Hard Eligibility Gate (Title word-boundary regex, salary floor, location, remote, visa, exclusions)
 * 2. Semantic Skills Overlap (Exact, synonym-normalised, and semantic matching)
 * 3. Seniority Trajectory Alignment (Calibrated career progression curve)
 * 4. Title & Domain Affinity (Synonym families and domain proximity)
 * 5. Holistic Synthesis & Explainability Engine (Transparent score decomposition)
 */
@Service
public class MultiStageReranker {

    public static final int APPLY_THRESHOLD = 70;
    public static final int REVIEW_THRESHOLD = 50;

    private final ProfileRepository profileRepository;
    private final SemanticSkillMatcher semanticSkillMatcher;
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public MultiStageReranker(ProfileRepository profileRepository,
                              SemanticSkillMatcher semanticSkillMatcher,
                              @Autowired(required = false) JdbcTemplate jdbcTemplate) {
        this.profileRepository = profileRepository;
        this.semanticSkillMatcher = semanticSkillMatcher;
        this.jdbcTemplate = jdbcTemplate;
    }

    public MultiStageReranker(ProfileRepository profileRepository,
                              SemanticSkillMatcher semanticSkillMatcher) {
        this(profileRepository, semanticSkillMatcher, null);
    }

    public RerankResult rerank(JobRecord job, UUID profileId, PreferenceSetRecord preferences) {
        return rerank(job, profileId, EffectiveMatchingCriteria.fromPreferences(profileId, preferences));
    }

    public RerankResult rerank(JobRecord job, UUID profileId, EffectiveMatchingCriteria criteria) {
        if (criteria == null) {
            criteria = EffectiveMatchingCriteria.fromPreferences(profileId, null);
        }

        List<String> allowedTitles = criteria.targetTitles();
        List<String> allowedLocations = criteria.locations();
        List<String> allowedRemoteTypes = criteria.remoteTypes();
        List<String> allowedExperienceLevels = criteria.experienceLevels();
        Long salaryMinGbp = criteria.salaryMinGbp();
        String sponsorshipPolicy = criteria.sponsorshipPolicy();

        // ─────────────────────────────────────────────────────────────
        // STAGE 1: Hard Eligibility Gate
        // ─────────────────────────────────────────────────────────────
        boolean titleEligible = allowedTitles.isEmpty() || HardFilterService.matchesAnyTitle(job.title(), allowedTitles);
        boolean salaryEligible = salaryMinGbp == null || salaryMinGbp <= 0 || job.salaryMax() == null
                || job.salaryMax().doubleValue() >= (salaryMinGbp * 0.80);
        boolean remoteEligible = allowedRemoteTypes.isEmpty() || job.remoteType() == null
                || "UNKNOWN".equalsIgnoreCase(job.remoteType())
                || allowedRemoteTypes.stream().anyMatch(r -> r.equalsIgnoreCase(job.remoteType()));
        boolean locationEligible = allowedLocations.isEmpty() || HardFilterService.matchesLocation(job, allowedLocations);

        // Visa Sponsorship Check: If candidate requires sponsorship, reject employers confirmed NO_LICENCE
        boolean sponsorshipEligible = true;
        String sponsorshipReason = null;
        double visaScore = 1.0;
        if (sponsorshipPolicy != null && "REQUIRED".equalsIgnoreCase(sponsorshipPolicy.trim())) {
            UUID companyId = job.companyId();
            if (companyId != null && jdbcTemplate != null) {
                List<Map<String, Object>> sponsorRows = jdbcTemplate.queryForList("""
                        SELECT licence_indicator, confidence
                        FROM sponsor_records
                        WHERE company_id = ?
                        ORDER BY fetched_at DESC
                        LIMIT 1
                        """, companyId);
                if (!sponsorRows.isEmpty()) {
                    String indicator = (String) sponsorRows.get(0).get("licence_indicator");
                    if (indicator != null && "NO_LICENCE".equalsIgnoreCase(indicator.trim())) {
                        sponsorshipEligible = false;
                        sponsorshipReason = "Employer is recorded as not holding a sponsor licence (NO_LICENCE)";
                        visaScore = 0.0;
                    } else if (indicator != null && "CONFIRMED".equalsIgnoreCase(indicator.trim())) {
                        visaScore = 1.0;
                    } else {
                        visaScore = 0.5; // UNKNOWN or unconfirmed
                    }
                } else {
                    visaScore = 0.5; // Unknown sponsor data passes with neutral score
                }
            } else {
                visaScore = 0.5;
            }
        }

        // Excluded Keywords Check
        boolean exclusionsEligible = true;
        String matchedExcludedKeyword = null;
        if (criteria.excludedKeywords() != null && !criteria.excludedKeywords().isEmpty()) {
            String titleLower = job.title() != null ? job.title().toLowerCase(Locale.ROOT) : "";
            String descLower = job.descriptionText() != null ? job.descriptionText().toLowerCase(Locale.ROOT) : "";
            for (String kw : criteria.excludedKeywords()) {
                if (kw != null && !kw.isBlank()) {
                    String kwLower = kw.trim().toLowerCase(Locale.ROOT);
                    if (titleLower.contains(kwLower) || descLower.contains(kwLower)) {
                        exclusionsEligible = false;
                        matchedExcludedKeyword = kw.trim();
                        break;
                    }
                }
            }
        }

        // Excluded Companies Check
        boolean companyEligible = true;
        if (criteria.excludedCompanies() != null && !criteria.excludedCompanies().isEmpty() && job.companyNameRaw() != null) {
            String compLower = job.companyNameRaw().toLowerCase(Locale.ROOT);
            for (String exComp : criteria.excludedCompanies()) {
                if (exComp != null && !exComp.isBlank() && compLower.contains(exComp.trim().toLowerCase(Locale.ROOT))) {
                    companyEligible = false;
                    break;
                }
            }
        }

        if (!titleEligible || !salaryEligible || !remoteEligible || !locationEligible || !sponsorshipEligible || !exclusionsEligible || !companyEligible) {
            List<String> reasons = new ArrayList<>();
            if (!titleEligible) reasons.add("Title '" + job.title() + "' does not match target roles");
            if (!salaryEligible) reasons.add("Salary is below 80% of minimum threshold (£" + salaryMinGbp + ")");
            if (!remoteEligible) reasons.add("Remote type '" + job.remoteType() + "' does not match preferences");
            if (!locationEligible) reasons.add("Location does not match target areas");
            if (!sponsorshipEligible) reasons.add(sponsorshipReason);
            if (!exclusionsEligible) reasons.add("Role matches excluded keyword '" + matchedExcludedKeyword + "'");
            if (!companyEligible) reasons.add("Company is in candidate's excluded companies list");

            return new RerankResult(
                    job.id(),
                    job.title(),
                    false,
                    0,
                    "SKIP",
                    Map.of(
                            "hard_eligibility", 0.0,
                            "skills_overlap", 0.0,
                            "seniority_alignment", 0.0,
                            "title_fit", 0.0,
                            "salary_fit", 0.0
                    ),
                    List.of(),
                    job.skillsExtracted() != null ? job.skillsExtracted() : List.of(),
                    List.of(),
                    reasons,
                    "Disqualified by Stage 1 Hard Eligibility Gate: " + String.join("; ", reasons),
                    "Role failed hard eligibility criteria and was filtered out."
            );
        }

        // ─────────────────────────────────────────────────────────────
        // STAGE 2: Semantic Skills Overlap
        // ─────────────────────────────────────────────────────────────
        Set<String> candidateSkills = profileId != null
                ? profileRepository.findSkills(profileId).stream()
                .map(s -> s.name() == null ? "" : s.name().trim())
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toSet())
                : Set.of();

        if (criteria.primaryKeywords() != null && !criteria.primaryKeywords().isEmpty()) {
            Set<String> augmentedSkills = new HashSet<>(candidateSkills);
            for (String pk : criteria.primaryKeywords()) {
                if (pk != null && !pk.isBlank()) {
                    augmentedSkills.add(pk.trim());
                }
            }
            candidateSkills = augmentedSkills;
        }

        List<String> jobSkills = job.skillsExtracted() != null ? job.skillsExtracted() : List.of();
        SemanticSkillMatcher.SemanticOverlap overlap = semanticSkillMatcher.computeOverlap(jobSkills, candidateSkills);

        List<String> matchedSkills = overlap.matches().stream()
                .map(SemanticSkillMatcher.SkillMatch::candidateSkill)
                .toList();

        Set<String> matchedJobSkillsLower = overlap.matches().stream()
                .map(m -> m.jobSkill().toLowerCase(Locale.ROOT).trim())
                .collect(java.util.stream.Collectors.toSet());

        List<String> missingSkills = jobSkills.stream()
                .filter(js -> js != null && !matchedJobSkillsLower.contains(js.toLowerCase(Locale.ROOT).trim()))
                .toList();

        double skillScore = jobSkills.isEmpty() ? 0.5 : overlap.ratio();

        // ─────────────────────────────────────────────────────────────
        // STAGE 3: Seniority Trajectory Alignment
        // ─────────────────────────────────────────────────────────────
        SeniorityClassifier.Level jobSeniority = SeniorityClassifier.classifyJob(job.experienceLevel(), job.title());
        double seniorityScore = computeCalibratedSeniority(jobSeniority, allowedExperienceLevels);

        // ─────────────────────────────────────────────────────────────
        // STAGE 4: Title & Domain Affinity
        // ─────────────────────────────────────────────────────────────
        double titleScore = computeCalibratedTitleScore(job.title(), allowedTitles);

        // ─────────────────────────────────────────────────────────────
        // STAGE 5: Salary Fit & Holistic Synthesis
        // ─────────────────────────────────────────────────────────────
        double salaryScore = computeSalaryFit(job.salaryMax(), salaryMinGbp);

        Map<String, Object> configuredWeights = criteria.scoringWeights();
        double wSkill = 35.0;
        double wTitle = 25.0;
        double wSeniority = 20.0;
        double wSalary = 10.0;
        double wLocation = 10.0;

        if (configuredWeights != null && !configuredWeights.isEmpty()) {
            if (configuredWeights.get("skill") instanceof Number n) wSkill = n.doubleValue();
            if (configuredWeights.get("experience") instanceof Number n) wSeniority = n.doubleValue();
            if (configuredWeights.get("salary") instanceof Number n) wSalary = n.doubleValue();
            if (configuredWeights.get("location") instanceof Number n) wLocation = n.doubleValue();
            if (configuredWeights.get("title") instanceof Number n) wTitle = n.doubleValue();
        }

        boolean titleApplicable = !allowedTitles.isEmpty();
        boolean seniorityApplicable = !allowedExperienceLevels.isEmpty();
        boolean locationApplicable = !allowedLocations.isEmpty() || !allowedRemoteTypes.isEmpty();
        boolean salaryApplicable = salaryMinGbp != null && salaryMinGbp > 0;
        boolean skillApplicable = true;

        double totalWeight = 0.0;
        double weightedSum = 0.0;

        if (skillApplicable) {
            totalWeight += wSkill;
            weightedSum += wSkill * skillScore;
        }
        if (titleApplicable) {
            totalWeight += wTitle;
            weightedSum += wTitle * titleScore;
        }
        if (seniorityApplicable) {
            totalWeight += wSeniority;
            weightedSum += wSeniority * seniorityScore;
        }
        if (salaryApplicable) {
            totalWeight += wSalary;
            weightedSum += wSalary * salaryScore;
        }
        if (locationApplicable) {
            totalWeight += wLocation;
            weightedSum += wLocation * 1.0; // Passed hard filter
        }

        double compositeScore = totalWeight > 0.0 ? (weightedSum / totalWeight) : 0.5;

        int overall = (int) Math.round(compositeScore * 100);
        String recommendation = overall >= APPLY_THRESHOLD ? "APPLY"
                : overall >= REVIEW_THRESHOLD ? "REVIEW" : "SKIP";

        List<String> highlights = new ArrayList<>();
        List<String> concerns = new ArrayList<>();

        if (!matchedSkills.isEmpty()) {
            highlights.add("Matched " + matchedSkills.size() + " of " + jobSkills.size()
                    + " required skills (" + String.join(", ", matchedSkills) + ")");
        }
        if (!missingSkills.isEmpty()) {
            concerns.add("Unmatched requirements: " + String.join(", ", missingSkills.stream().limit(4).toList()));
        }
        if (titleScore >= 0.85) {
            highlights.add("Target role alignment: " + job.title());
        }
        if (seniorityScore >= 0.90) {
            highlights.add("Seniority level (" + jobSeniority.name() + ") matches career trajectory");
        } else if (seniorityScore < 0.60) {
            concerns.add("Seniority level (" + jobSeniority.name() + ") deviates from desired profile");
        }
        if (salaryScore >= 0.90 && salaryMinGbp != null && salaryMinGbp > 0) {
            highlights.add("Compensation meets target minimum threshold");
        }

        String narrative = String.format(Locale.ROOT,
                "Multi-stage reranking scored %d/100 (%s). Skills: %d%%, Title Fit: %d%%, Seniority: %d%%, Salary: %d%%.",
                overall, recommendation,
                Math.round(skillScore * 100), Math.round(titleScore * 100),
                Math.round(seniorityScore * 100), Math.round(salaryScore * 100));

        String decision = switch (recommendation) {
            case "APPLY" -> "Score " + overall + " meets the APPLY threshold (" + APPLY_THRESHOLD + "). Passed to application review.";
            case "REVIEW" -> "Score " + overall + " qualifies for manual candidate review (" + REVIEW_THRESHOLD + "-" + (APPLY_THRESHOLD - 1) + ").";
            default -> "Score " + overall + " is below the review threshold (" + REVIEW_THRESHOLD + ").";
        };

        Map<String, Double> stageScores = Map.of(
                "hard_eligibility", 1.0,
                "skills_overlap", skillScore,
                "seniority_alignment", seniorityScore,
                "title_fit", titleScore,
                "salary_fit", salaryScore
        );

        return new RerankResult(
                job.id(),
                job.title(),
                true,
                overall,
                recommendation,
                stageScores,
                matchedSkills,
                missingSkills,
                highlights,
                concerns,
                narrative,
                decision
        );
    }

    /**
     * Calibrated Seniority Progression:
     * Aligns target levels smoothly without allowing higher-salary mid roles to leapfrog entry-level roles
     * when the candidate explicitly targets entry/junior.
     */
    public static double computeCalibratedSeniority(SeniorityClassifier.Level jobLevel, List<String> candidateLevels) {
        if (candidateLevels == null || candidateLevels.isEmpty()) {
            return 0.80; // Unconstrained
        }

        Set<SeniorityClassifier.Level> preferred = new HashSet<>();
        for (String lvl : candidateLevels) {
            SeniorityClassifier.Level parsed = SeniorityClassifier.parseLevel(lvl);
            if (parsed != null) preferred.add(parsed);
        }

        if (preferred.isEmpty()) {
            return 0.80;
        }

        // Exact match in preferred set
        if (preferred.contains(jobLevel)) {
            // If candidate targets ENTRY and job is ENTRY, reward exact foundation match
            if (preferred.contains(SeniorityClassifier.Level.ENTRY) && jobLevel == SeniorityClassifier.Level.ENTRY) {
                return 1.0;
            }
            if (jobLevel == SeniorityClassifier.Level.MID) {
                return 0.92;
            }
            return 0.95;
        }

        // Distance penalty
        int minDistance = Integer.MAX_VALUE;
        for (SeniorityClassifier.Level p : preferred) {
            int dist = Math.abs(p.ordinal() - jobLevel.ordinal());
            if (dist < minDistance) {
                minDistance = dist;
            }
        }

        return switch (minDistance) {
            case 1 -> 0.70;
            case 2 -> 0.40;
            default -> 0.15;
        };
    }

    public static double computeCalibratedTitleScore(String jobTitle, List<String> allowedTitles) {
        if (allowedTitles == null || allowedTitles.isEmpty()) return 1.0;
        if (jobTitle == null || jobTitle.isBlank()) return 0.5;

        // Exact phrase match
        for (String target : allowedTitles) {
            if (jobTitle.equalsIgnoreCase(target.trim())) {
                return 1.0;
            }
        }

        // Word-boundary match
        if (HardFilterService.matchesAnyTitle(jobTitle, allowedTitles)) {
            return 0.95;
        }

        // Synonym match
        for (String target : allowedTitles) {
            if (TitleSynonymRegistry.areSynonyms(target, jobTitle)) {
                return 0.88;
            }
        }

        // Domain token overlap
        String normJob = jobTitle.toLowerCase(Locale.ROOT);
        for (String target : allowedTitles) {
            String normTarget = target.toLowerCase(Locale.ROOT);
            for (String word : normTarget.split("\\s+")) {
                if (word.length() > 3 && normJob.contains(word)) {
                    return 0.65;
                }
            }
        }

        return 0.30;
    }

    private static double computeSalaryFit(Number offering, Long minimum) {
        if (minimum == null || minimum <= 0) return 1.0;
        if (offering == null) return 0.5;

        double offer = offering.doubleValue();
        double want = minimum.doubleValue();
        if (offer >= want) return 1.0;
        if (offer < want * 0.80) return 0.0;

        return (offer - (want * 0.80)) / (want * 0.20);
    }
}
