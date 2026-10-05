package com.personal.jobagent.jobs;

import com.personal.jobagent.llm.LlmCompletionRequest;
import com.personal.jobagent.llm.ModelRouter;
import com.personal.jobagent.llm.TaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Semantic skill matching: evaluates how well a candidate's skills overlap
 * with a job's required skills using a three-tier strategy:
 * <ol>
 *   <li><b>Exact match</b> — case-insensitive string equality (free, instant).</li>
 *   <li><b>Synonym match</b> — curated alias registry via {@link SkillSynonymRegistry} (free, instant).</li>
 *   <li><b>LLM match</b> — asks the model to rate similarity 0.0–1.0, cached in
 *       {@code skill_similarity_cache} (one-time cost per pair).</li>
 * </ol>
 *
 * <p>Design rules:
 * <ul>
 *   <li>Exact and synonym matches score 1.0 — they are certain.</li>
 *   <li>LLM scores ≥ 0.7 count as a match (partial credit weighted by score).</li>
 *   <li>Results are cached bidirectionally; the cache key is normalised so
 *       {@code (a, b)} and {@code (b, a)} hit the same row.</li>
 *   <li>If the LLM is unavailable, fall back to exact+synonym only — never
 *       block the pipeline on a provider outage.</li>
 * </ul>
 */
@Service
public class SemanticSkillMatcher {

    private static final Logger log = LoggerFactory.getLogger(SemanticSkillMatcher.class);

    /** LLM similarity at or above this threshold counts as a match. */
    static final double LLM_MATCH_THRESHOLD = 0.7;

    /** Maximum number of skill pairs to send to the LLM in one batch prompt. */
    private static final int LLM_BATCH_SIZE = 20;

    private final JdbcTemplate db;
    private final ModelRouter modelRouter;

    public SemanticSkillMatcher(JdbcTemplate db, ModelRouter modelRouter) {
        this.db = db;
        this.modelRouter = modelRouter;
    }

    /**
     * A single skill-pair evaluation result.
     */
    public record SkillMatch(String jobSkill, String candidateSkill, double score, String source) {}

    /**
     * Overall semantic overlap result.
     */
    public record SemanticOverlap(
            double ratio,
            List<SkillMatch> matches,
            int jobSkillCount,
            boolean candidateHasSkills
    ) {}

    /**
     * Computes semantic overlap between a job's extracted skills and the
     * candidate's profile skills.
     *
     * @param jobSkills       skills extracted from the job description
     * @param candidateSkills skills on the candidate's profile
     * @return overlap result with ratio 0.0–1.0 and individual match details
     */
    public SemanticOverlap computeOverlap(List<String> jobSkills, Set<String> candidateSkills) {
        if (jobSkills == null || jobSkills.isEmpty()) {
            return new SemanticOverlap(0.5, List.of(), 0, !candidateSkills.isEmpty());
        }
        if (candidateSkills == null || candidateSkills.isEmpty()) {
            return new SemanticOverlap(0.0, List.of(), jobSkills.size(), false);
        }

        List<SkillMatch> allMatches = new ArrayList<>();
        List<String> unmatchedJobSkills = new ArrayList<>();
        Set<String> candidateCanonical = candidateSkills.stream()
                .map(SkillSynonymRegistry::canonicalise)
                .collect(Collectors.toSet());

        // ── Tier 1 + 2: Exact and synonym matching ──
        for (String jobSkill : jobSkills) {
            if (jobSkill == null || jobSkill.isBlank()) continue;
            String jobCanonical = SkillSynonymRegistry.canonicalise(jobSkill);

            // Exact match (after canonicalisation, synonym matches collapse here too)
            if (candidateCanonical.contains(jobCanonical)) {
                // Find the original candidate skill for reporting
                String matchedCandidate = candidateSkills.stream()
                        .filter(c -> SkillSynonymRegistry.canonicalise(c).equals(jobCanonical))
                        .findFirst().orElse(jobSkill);
                String source = jobSkill.trim().equalsIgnoreCase(matchedCandidate.trim()) ? "EXACT" : "SYNONYM";
                allMatches.add(new SkillMatch(jobSkill, matchedCandidate, 1.0, source));
            } else {
                unmatchedJobSkills.add(jobSkill);
            }
        }

        // ── Tier 3: LLM matching for remaining unmatched skills ──
        if (!unmatchedJobSkills.isEmpty()) {
            List<SkillMatch> llmMatches = evaluateWithLlm(unmatchedJobSkills, candidateSkills);
            allMatches.addAll(llmMatches);
        }

        // Compute weighted ratio: each matched skill contributes its score
        double totalWeight = jobSkills.stream()
                .filter(s -> s != null && !s.isBlank())
                .count();
        if (totalWeight == 0) {
            return new SemanticOverlap(0.5, List.of(), 0, true);
        }

        double matchedWeight = allMatches.stream()
                .mapToDouble(SkillMatch::score)
                .sum();

        double ratio = Math.min(1.0, matchedWeight / totalWeight);
        return new SemanticOverlap(ratio, allMatches, jobSkills.size(), true);
    }

    /**
     * Evaluates unmatched job skills against candidate skills using cached
     * LLM results, then batches remaining unknowns to the LLM.
     */
    private List<SkillMatch> evaluateWithLlm(List<String> jobSkills, Set<String> candidateSkills) {
        List<SkillMatch> results = new ArrayList<>();
        List<String[]> uncachedPairs = new ArrayList<>();

        // Check cache first
        for (String jobSkill : jobSkills) {
            SkillMatch cached = findBestCachedMatch(jobSkill, candidateSkills);
            if (cached != null) {
                results.add(cached);
            } else {
                // Queue for LLM evaluation — pair with each candidate skill
                for (String candSkill : candidateSkills) {
                    uncachedPairs.add(new String[]{jobSkill, candSkill});
                }
            }
        }

        // Batch LLM evaluation for uncached pairs
        if (!uncachedPairs.isEmpty()) {
            Map<String, SkillMatch> llmResults = batchLlmEvaluate(uncachedPairs);
            // For each originally unmatched job skill, pick the best LLM match
            Set<String> jobSkillsNeedingLlm = uncachedPairs.stream()
                    .map(p -> p[0]).collect(Collectors.toSet());
            for (String jobSkill : jobSkillsNeedingLlm) {
                SkillMatch best = llmResults.get(jobSkill.toLowerCase(Locale.ROOT));
                if (best != null && best.score >= LLM_MATCH_THRESHOLD) {
                    results.add(best);
                }
            }
        }

        return results;
    }

    /**
     * Checks the DB cache for any known similarity between this job skill
     * and the candidate's skills. Returns the best match above threshold,
     * or null if nothing is cached.
     */
    private SkillMatch findBestCachedMatch(String jobSkill, Set<String> candidateSkills) {
        String jobNorm = jobSkill.trim().toLowerCase(Locale.ROOT);
        SkillMatch best = null;

        for (String candSkill : candidateSkills) {
            String candNorm = candSkill.trim().toLowerCase(Locale.ROOT);
            String a = jobNorm.compareTo(candNorm) <= 0 ? jobNorm : candNorm;
            String b = jobNorm.compareTo(candNorm) <= 0 ? candNorm : jobNorm;

            try {
                List<Map<String, Object>> rows = db.queryForList(
                        "SELECT score FROM skill_similarity_cache WHERE skill_a = ? AND skill_b = ?",
                        a, b);
                if (!rows.isEmpty()) {
                    double score = ((Number) rows.get(0).get("score")).doubleValue();
                    if (best == null || score > best.score()) {
                        best = new SkillMatch(jobSkill, candSkill, score, "LLM_CACHED");
                    }
                }
            } catch (Exception e) {
                log.debug("Cache lookup failed for ({}, {}): {}", a, b, e.getMessage());
            }
        }

        return (best != null && best.score() >= LLM_MATCH_THRESHOLD) ? best : null;
    }

    /**
     * Sends uncached skill pairs to the LLM in batches, caches results,
     * and returns the best match per job skill.
     */
    private Map<String, SkillMatch> batchLlmEvaluate(List<String[]> pairs) {
        Map<String, SkillMatch> bestByJobSkill = new HashMap<>();

        // Deduplicate pairs by normalised key
        Map<String, String[]> uniquePairs = new LinkedHashMap<>();
        for (String[] pair : pairs) {
            String a = pair[0].trim().toLowerCase(Locale.ROOT);
            String b = pair[1].trim().toLowerCase(Locale.ROOT);
            String key = (a.compareTo(b) <= 0 ? a : b) + "|||" + (a.compareTo(b) <= 0 ? b : a);
            uniquePairs.putIfAbsent(key, pair);
        }

        List<String[]> deduped = new ArrayList<>(uniquePairs.values());

        // Process in batches
        for (int i = 0; i < deduped.size(); i += LLM_BATCH_SIZE) {
            List<String[]> batch = deduped.subList(i, Math.min(i + LLM_BATCH_SIZE, deduped.size()));
            Map<String, Double> scores = callLlmForBatch(batch);

            for (String[] pair : batch) {
                String jobNorm = pair[0].trim().toLowerCase(Locale.ROOT);
                String candNorm = pair[1].trim().toLowerCase(Locale.ROOT);
                String key = jobNorm + "|||" + candNorm;
                String keyReversed = candNorm + "|||" + jobNorm;

                Double score = scores.getOrDefault(key, scores.get(keyReversed));
                if (score == null) score = 0.0;

                // Cache the result
                cacheScore(jobNorm, candNorm, score);

                // Track best match per job skill
                SkillMatch current = bestByJobSkill.get(jobNorm);
                if (current == null || score > current.score()) {
                    bestByJobSkill.put(jobNorm, new SkillMatch(pair[0], pair[1], score, "LLM"));
                }
            }
        }

        return bestByJobSkill;
    }

    /**
     * Calls the LLM with a batch prompt asking it to rate skill similarity.
     * Returns a map of "skillA|||skillB" → score.
     */
    private Map<String, Double> callLlmForBatch(List<String[]> batch) {
        Map<String, Double> results = new HashMap<>();

        StringBuilder prompt = new StringBuilder();
        prompt.append("Rate the similarity between each pair of technical skills on a scale of 0.0 to 1.0.\n");
        prompt.append("1.0 = identical or equivalent skills (e.g. 'React' and 'ReactJS')\n");
        prompt.append("0.7-0.9 = closely related skills (e.g. 'Java' and 'Kotlin')\n");
        prompt.append("0.3-0.6 = somewhat related (e.g. 'Python' and 'R')\n");
        prompt.append("0.0-0.2 = unrelated skills (e.g. 'Python' and 'Carpentry')\n\n");
        prompt.append("Return ONLY a JSON array of numbers in the same order, nothing else.\n\n");
        prompt.append("Pairs:\n");

        for (int i = 0; i < batch.size(); i++) {
            prompt.append(i + 1).append(". \"").append(batch.get(i)[0]).append("\" vs \"").append(batch.get(i)[1]).append("\"\n");
        }

        try {
            var request = LlmCompletionRequest.simple(null, prompt.toString(), UUID.randomUUID());
            var execution = modelRouter.execute(TaskType.SKILL_MATCHING, request, Duration.ofSeconds(30));
            String response = execution.completion().text().trim();

            // Parse JSON array of scores
            List<Double> scores = parseScoreArray(response, batch.size());
            for (int i = 0; i < Math.min(scores.size(), batch.size()); i++) {
                String key = batch.get(i)[0].trim().toLowerCase(Locale.ROOT) + "|||"
                        + batch.get(i)[1].trim().toLowerCase(Locale.ROOT);
                results.put(key, scores.get(i));
            }
        } catch (Exception e) {
            log.warn("LLM skill matching failed, falling back to exact+synonym only: {}", e.getMessage());
        }

        return results;
    }

    /**
     * Parses a JSON array of doubles from the LLM response.
     * Lenient: handles extra whitespace, brackets, trailing commas.
     */
    static List<Double> parseScoreArray(String response, int expectedCount) {
        List<Double> scores = new ArrayList<>();
        // Strip markdown code fences if present
        String cleaned = response.replaceAll("```[a-z]*\\n?", "").replaceAll("```", "").trim();
        // Remove outer brackets
        if (cleaned.startsWith("[")) cleaned = cleaned.substring(1);
        if (cleaned.endsWith("]")) cleaned = cleaned.substring(0, cleaned.length() - 1);

        for (String token : cleaned.split(",")) {
            String t = token.trim();
            if (t.isEmpty()) continue;
            try {
                double val = Double.parseDouble(t);
                scores.add(Math.max(0.0, Math.min(1.0, val)));
            } catch (NumberFormatException e) {
                scores.add(0.0); // Unparseable → no match
            }
        }

        // Pad with 0.0 if the LLM returned fewer scores than expected
        while (scores.size() < expectedCount) {
            scores.add(0.0);
        }

        return scores;
    }

    /**
     * Caches a similarity score, normalising the key so skill_a < skill_b.
     */
    private void cacheScore(String skillA, String skillB, double score) {
        String a = skillA.compareTo(skillB) <= 0 ? skillA : skillB;
        String b = skillA.compareTo(skillB) <= 0 ? skillB : skillA;
        try {
            db.update("""
                    INSERT INTO skill_similarity_cache (skill_a, skill_b, score, source)
                    VALUES (?, ?, ?, 'LLM')
                    ON CONFLICT (skill_a, skill_b) DO UPDATE SET score = EXCLUDED.score
                    """, a, b, score);
        } catch (Exception e) {
            log.debug("Failed to cache skill similarity ({}, {}): {}", a, b, e.getMessage());
        }
    }
}
