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
        List<?> skillsList = extractFacetList(facets, "skills");
        if (skillsList != null) {
            for (Object item : skillsList) {
                if (item != null && !item.toString().isBlank()) {
                    String skill = item.toString().trim();
                    keywords.add(SkillSynonymRegistry.canonicalise(skill));
                }
            }
        }
        List<?> kwList = extractFacetList(facets, "keywords", "primaryKeywords");
        if (kwList != null) {
            for (Object item : kwList) {
                if (item != null && !item.toString().isBlank()) {
                    keywords.add(item.toString().trim());
                }
            }
        }

        // Negative keywords to filter out noise
        List<String> negativeKeywords = new ArrayList<>();
        List<?> excludeList = extractFacetList(facets, "exclude", "excludeKeywords", "negativeKeywords");
        if (excludeList != null) {
            for (Object item : excludeList) {
                if (item != null && !item.toString().isBlank()) {
                    negativeKeywords.add(item.toString().trim());
                }
            }
        }

        // Locations
        List<String> locations = new ArrayList<>();
        List<?> locList = extractFacetList(facets, "locations", "targetLocations");
        if (locList != null) {
            for (Object item : locList) {
                if (item != null && !item.toString().isBlank()) {
                    locations.add(item.toString().trim());
                }
            }
        }

        // Remote types
        List<String> remoteTypes = new ArrayList<>();
        List<?> remoteList = extractFacetList(facets, "remoteTypes", "remote");
        if (remoteList != null) {
            for (Object item : remoteList) {
                if (item != null && !item.toString().isBlank()) {
                    remoteTypes.add(item.toString().trim().toUpperCase(Locale.ROOT));
                }
            }
        }

        // Seniority levels
        List<SeniorityClassifier.Level> seniorityLevels = new ArrayList<>();
        List<?> seniorityList = extractFacetList(facets, "seniority", "seniorityLevels", "experienceLevels");
        if (seniorityList != null) {
            for (Object item : seniorityList) {
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

    private static List<?> extractFacetList(Map<String, Object> facets, String... keys) {
        if (facets == null || keys == null) return null;
        for (String k : keys) {
            if (facets.containsKey(k) && facets.get(k) instanceof List<?> list) {
                return list;
            }
        }
        return null;
    }
}
