package com.personal.jobagent.discovery.plan;

import com.personal.jobagent.jobs.SeniorityClassifier;
import com.personal.jobagent.jobs.SkillSynonymRegistry;
import com.personal.jobagent.jobs.TitleSynonymRegistry;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Intelligent query expansion engine for configurable search plans.
 * Expands high-level candidate intent (e.g. "Senior Backend Developer" in London)
 * into structured multi-provider discovery queries, synonym variants, and negative constraints.
 */
@Component
public class SearchPlanQueryExpander {

    public record ExpandedQueryPlan(
            UUID searchPlanId,
            String planName,
            List<String> originalTitles,
            List<String> expandedTitles,
            List<String> primaryKeywords,
            List<String> negativeKeywords,
            List<String> targetLocations,
            List<String> remoteTypes,
            List<SeniorityClassifier.Level> seniorityLevels,
            List<String> targetSources,
            int maxResults
    ) {}

    /**
     * Expands a SearchPlanRecord into concrete query facets for multi-source retrieval.
     */
    public ExpandedQueryPlan expand(SearchPlanRecord plan) {
        List<String> originalTitles = plan.targetTitles();
        List<String> expandedTitles = TitleSynonymRegistry.expandTitles(originalTitles);

        Map<String, Object> facets = plan.queryFacets();

        // Extract primary keywords / required skills
        Set<String> keywords = new LinkedHashSet<>();
        if (facets.containsKey("skills") && facets.get("skills") instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !item.toString().isBlank()) {
                    String skill = item.toString().trim();
                    keywords.add(SkillSynonymRegistry.canonicalise(skill));
                }
            }
        }
        if (facets.containsKey("keywords") && facets.get("keywords") instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !item.toString().isBlank()) {
                    keywords.add(item.toString().trim());
                }
            }
        }

        // Negative keywords to filter out noise
        List<String> negativeKeywords = new ArrayList<>();
        if (facets.containsKey("exclude") && facets.get("exclude") instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !item.toString().isBlank()) {
                    negativeKeywords.add(item.toString().trim());
                }
            }
        }

        // Locations
        List<String> locations = new ArrayList<>();
        if (facets.containsKey("locations") && facets.get("locations") instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !item.toString().isBlank()) {
                    locations.add(item.toString().trim());
                }
            }
        }

        // Remote types
        List<String> remoteTypes = new ArrayList<>();
        if (facets.containsKey("remoteTypes") && facets.get("remoteTypes") instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !item.toString().isBlank()) {
                    remoteTypes.add(item.toString().trim().toUpperCase(Locale.ROOT));
                }
            }
        }

        // Seniority levels
        List<SeniorityClassifier.Level> seniorityLevels = new ArrayList<>();
        if (facets.containsKey("seniority") && facets.get("seniority") instanceof List<?> list) {
            for (Object item : list) {
                if (item != null && !item.toString().isBlank()) {
                    SeniorityClassifier.Level lvl = SeniorityClassifier.parseLevel(item.toString().trim());
                    if (lvl != null && !seniorityLevels.contains(lvl)) {
                        seniorityLevels.add(lvl);
                    }
                }
            }
        }

        // Default sources if empty
        List<String> targetSources = plan.targetSources();
        if (targetSources.isEmpty()) {
            targetSources = List.of("ADZUNA", "ARBEITNOW", "FREEHIRE", "TSENTA", "GREENHOUSE");
        }

        return new ExpandedQueryPlan(
                plan.id(),
                plan.name(),
                originalTitles,
                expandedTitles,
                new ArrayList<>(keywords),
                negativeKeywords,
                locations,
                remoteTypes,
                seniorityLevels,
                targetSources,
                plan.maxResultsPerRun()
        );
    }
}
