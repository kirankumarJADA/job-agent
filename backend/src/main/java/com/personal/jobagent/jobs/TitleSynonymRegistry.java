package com.personal.jobagent.jobs;

import java.util.*;

/**
 * Curated, deterministic registry of job title synonyms and role families.
 * <p>
 * Used exclusively for:
 * 1. Positive candidate title matching in {@link HardFilterService}
 * 2. Feed query expansion in {@link JobRepository#findFeed}
 * <p>
 * Invariant: Synonyms must NEVER be used to widen hard exclusions or relax candidate constraints.
 */
public final class TitleSynonymRegistry {

    private static final Map<String, String> CANONICAL_MAP;
    private static final Map<String, Set<String>> FAMILY_MAP;

    private TitleSynonymRegistry() {}

    static {
        List<List<String>> groups = List.of(
                // ── Software Engineer / Developer ──
                List.of(
                        "software engineer", "software developer", "programmer",
                        "swe", "software development engineer", "sde"
                ),
                // ── Backend ──
                List.of(
                        "backend engineer", "backend developer", "back end engineer",
                        "back end developer", "server side engineer", "server side developer",
                        "java developer", "java engineer", "golang developer", "golang engineer",
                        "python developer", "python engineer", ".net developer", "c# developer"
                ),
                // ── Frontend ──
                List.of(
                        "frontend engineer", "frontend developer", "front end engineer",
                        "front end developer", "ui engineer", "ui developer",
                        "react developer", "angular developer", "web developer"
                ),
                // ── Full Stack ──
                List.of(
                        "full stack engineer", "full stack developer", "fullstack engineer",
                        "fullstack developer", "full stack software engineer"
                ),
                // ── Platform / Cloud / DevOps / SRE ──
                List.of(
                        "platform engineer", "cloud engineer", "devops engineer",
                        "site reliability engineer", "sre", "infrastructure engineer",
                        "systems engineer", "cloud architect"
                ),
                // ── Data Engineering ──
                List.of(
                        "data engineer", "big data developer", "big data engineer",
                        "data platform engineer", "etl developer"
                ),
                // ── AI / Machine Learning ──
                List.of(
                        "machine learning engineer", "ml engineer", "ai engineer",
                        "artificial intelligence engineer", "deep learning engineer"
                ),
                // ── Quality / Test ──
                List.of(
                        "qa engineer", "test automation engineer", "sdet",
                        "software development engineer in test", "quality assurance engineer",
                        "test engineer", "automation engineer"
                ),
                // ── Mobile ──
                List.of(
                        "mobile engineer", "mobile developer", "ios developer",
                        "ios engineer", "android developer", "android engineer"
                ),
                // ── Leadership ──
                List.of(
                        "lead software engineer", "lead developer", "tech lead",
                        "technical lead", "engineering manager", "staff software engineer",
                        "principal software engineer"
                )
        );

        Map<String, String> canonical = new HashMap<>();
        Map<String, Set<String>> family = new HashMap<>();

        for (List<String> group : groups) {
            String primary = group.get(0).toLowerCase(Locale.ROOT);
            Set<String> members = new LinkedHashSet<>();
            for (String title : group) {
                String norm = normalize(title);
                members.add(norm);
                canonical.put(norm, primary);
            }
            family.put(primary, Collections.unmodifiableSet(members));
        }

        CANONICAL_MAP = Map.copyOf(canonical);
        FAMILY_MAP = Map.copyOf(family);
    }

    public static String normalize(String title) {
        if (title == null) return "";
        return title.toLowerCase(Locale.ROOT)
                .replace("-", " ")
                .replace("/", " ")
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    public static String canonicalise(String title) {
        if (title == null) return "";
        String norm = normalize(title);
        return CANONICAL_MAP.getOrDefault(norm, norm);
    }

    public static boolean areSynonyms(String a, String b) {
        if (a == null || b == null) return false;
        String ca = canonicalise(a);
        String cb = canonicalise(b);
        return !ca.isBlank() && ca.equals(cb);
    }

    public static Set<String> getSynonyms(String title) {
        if (title == null) return Set.of();
        String primary = CANONICAL_MAP.get(normalize(title));
        if (primary == null) {
            return Set.of();
        }
        return FAMILY_MAP.getOrDefault(primary, Set.of());
    }

    public static List<String> expand(String title) {
        if (title == null || title.isBlank()) return List.of();
        Set<String> syns = getSynonyms(title);
        if (syns.isEmpty()) {
            return List.of(title.trim());
        }
        List<String> result = new ArrayList<>();
        result.add(title.trim());
        for (String s : syns) {
            if (!s.equalsIgnoreCase(title.trim())) {
                result.add(s);
            }
        }
        return result;
    }

    public static List<String> expandTitles(List<String> titles) {
        if (titles == null || titles.isEmpty()) return List.of();
        Set<String> seen = new LinkedHashSet<>();
        List<String> result = new ArrayList<>();
        for (String t : titles) {
            if (t == null || t.isBlank()) continue;
            for (String expanded : expand(t)) {
                if (seen.add(expanded.toLowerCase(Locale.ROOT))) {
                    result.add(expanded);
                }
            }
        }
        return result;
    }

    public static List<String> expandQueryVariants(String query) {
        if (query == null || query.isBlank()) return List.of();
        String norm = normalize(query);
        String primary = CANONICAL_MAP.get(norm);
        if (primary == null) {
            return List.of(query.trim());
        }
        Set<String> group = FAMILY_MAP.get(primary);
        if (group == null || group.isEmpty()) {
            return List.of(query.trim());
        }
        List<String> variants = new ArrayList<>();
        variants.add(query.trim());
        for (String s : group) {
            if (!s.equalsIgnoreCase(query.trim()) && variants.size() < 4) {
                variants.add(s);
            }
        }
        return variants;
    }

    static int groupCount() {
        return FAMILY_MAP.size();
    }
}
