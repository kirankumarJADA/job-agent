package com.personal.jobagent.jobs;

import com.personal.jobagent.discovery.plan.SearchPlanQueryExpander;
import com.personal.jobagent.preferences.PreferenceSetRecord;

import java.util.*;

/**
 * Encapsulates the effective criteria used for matching candidate profiles against job postings.
 * <p>
 * Implements strict precedence:
 * - Plan-specific targeting criteria (titles, locations, remote types, seniority, primary & excluded keywords)
 *   override global preferences when explicitly provided by the search plan.
 * - Candidate-level constraints (visa sponsorship, salary floor, excluded companies, scoring weights)
 *   are strictly preserved from global preferences and can NEVER be bypassed by search plan parameters.
 * - When candidate has no active global preference set (null), documented defaults apply while
 *   honoring plan-specific targeting criteria.
 */
public record EffectiveMatchingCriteria(
        UUID profileId,
        List<String> targetTitles,
        List<String> primaryKeywords,
        List<String> excludedKeywords,
        List<String> locations,
        List<String> remoteTypes,
        List<String> experienceLevels,
        Long salaryMinGbp,
        String sponsorshipPolicy,
        List<String> targetCompanies,
        List<String> excludedCompanies,
        Map<String, Object> scoringWeights
) {
    public EffectiveMatchingCriteria {
        targetTitles = targetTitles == null ? List.of() : List.copyOf(targetTitles);
        primaryKeywords = primaryKeywords == null ? List.of() : List.copyOf(primaryKeywords);
        excludedKeywords = excludedKeywords == null ? List.of() : List.copyOf(excludedKeywords);
        locations = locations == null ? List.of() : List.copyOf(locations);
        remoteTypes = remoteTypes == null ? List.of() : List.copyOf(remoteTypes);
        experienceLevels = experienceLevels == null ? List.of() : List.copyOf(experienceLevels);
        targetCompanies = targetCompanies == null ? List.of() : List.copyOf(targetCompanies);
        excludedCompanies = excludedCompanies == null ? List.of() : List.copyOf(excludedCompanies);
        scoringWeights = scoringWeights == null ? Map.of() : Map.copyOf(scoringWeights);
    }

    public List<String> titles() {
        return targetTitles;
    }

    public List<String> locationsAllowed() {
        return locations;
    }

    public static EffectiveMatchingCriteria fromPreferences(UUID profileId, PreferenceSetRecord preferences) {
        if (preferences == null) {
            return new EffectiveMatchingCriteria(
                    profileId,
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                    null, "NONE", List.of(), List.of(), Map.of()
            );
        }
        return new EffectiveMatchingCriteria(
                profileId,
                preferences.titles(),
                preferences.keywordsInclude(),
                preferences.keywordsExclude(),
                preferences.locationsAllowed(),
                preferences.remoteTypes(),
                preferences.experienceLevels(),
                preferences.salaryMinGbp(),
                preferences.sponsorshipPolicy(),
                extractCompanies(preferences.extraFilters(), "targetCompanies"),
                extractCompanies(preferences.extraFilters(), "excludedCompanies"),
                preferences.scoringWeights()
        );
    }

    public static EffectiveMatchingCriteria create(UUID profileId, PreferenceSetRecord preferences, SearchPlanQueryExpander.ExpandedQueryPlan plan) {
        if (plan == null) {
            return fromPreferences(profileId, preferences);
        }

        // Plan targeting overrides global targeting when present
        List<String> titles;
        if (plan.expandedTitles() != null && !plan.expandedTitles().isEmpty()) {
            titles = plan.expandedTitles();
        } else if (plan.originalTitles() != null && !plan.originalTitles().isEmpty()) {
            titles = plan.originalTitles();
        } else if (preferences != null && preferences.titles() != null) {
            titles = preferences.titles();
        } else {
            titles = List.of();
        }

        List<String> locations;
        if (plan.targetLocations() != null && !plan.targetLocations().isEmpty()) {
            locations = plan.targetLocations();
        } else if (preferences != null && preferences.locationsAllowed() != null) {
            locations = preferences.locationsAllowed();
        } else {
            locations = List.of();
        }

        List<String> remoteTypes;
        if (plan.remoteTypes() != null && !plan.remoteTypes().isEmpty()) {
            remoteTypes = plan.remoteTypes();
        } else if (preferences != null && preferences.remoteTypes() != null) {
            remoteTypes = preferences.remoteTypes();
        } else {
            remoteTypes = List.of();
        }

        List<String> experienceLevels;
        if (plan.seniorityLevels() != null && !plan.seniorityLevels().isEmpty()) {
            experienceLevels = plan.seniorityLevels().stream().map(SeniorityClassifier.Level::name).toList();
        } else if (preferences != null && preferences.experienceLevels() != null && !preferences.experienceLevels().isEmpty()) {
            experienceLevels = preferences.experienceLevels();
        } else {
            experienceLevels = List.of();
        }

        // Combine plan exclusions with global exclusions
        Set<String> combinedExclusions = new LinkedHashSet<>();
        if (plan.negativeKeywords() != null) {
            combinedExclusions.addAll(plan.negativeKeywords());
        }
        if (preferences != null && preferences.keywordsExclude() != null) {
            combinedExclusions.addAll(preferences.keywordsExclude());
        }

        // Combine primary keywords
        Set<String> combinedKeywords = new LinkedHashSet<>();
        if (plan.primaryKeywords() != null) {
            combinedKeywords.addAll(plan.primaryKeywords());
        }
        if (preferences != null && preferences.keywordsInclude() != null) {
            combinedKeywords.addAll(preferences.keywordsInclude());
        }

        // Candidate-level constraints strictly preserved from profile preferences
        Long salaryMin = preferences != null ? preferences.salaryMinGbp() : null;
        String sponsorship = preferences != null ? preferences.sponsorshipPolicy() : "NONE";
        List<String> targetCos = preferences != null ? extractCompanies(preferences.extraFilters(), "targetCompanies") : List.of();
        List<String> excludedCos = preferences != null ? extractCompanies(preferences.extraFilters(), "excludedCompanies") : List.of();
        Map<String, Object> weights = preferences != null ? preferences.scoringWeights() : Map.of();

        return new EffectiveMatchingCriteria(
                profileId,
                titles,
                new ArrayList<>(combinedKeywords),
                new ArrayList<>(combinedExclusions),
                locations,
                remoteTypes,
                experienceLevels,
                salaryMin,
                sponsorship,
                targetCos,
                excludedCos,
                weights
        );
    }

    private static List<String> extractCompanies(Map<String, Object> extra, String key) {
        if (extra == null || !extra.containsKey(key)) return List.of();
        Object val = extra.get(key);
        if (val instanceof List<?> list) {
            return list.stream().filter(Objects::nonNull).map(Object::toString).toList();
        }
        return List.of();
    }
}
