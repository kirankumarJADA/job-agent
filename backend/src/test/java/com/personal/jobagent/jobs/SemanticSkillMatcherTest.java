package com.personal.jobagent.jobs;

import com.personal.jobagent.llm.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SemanticSkillMatcherTest {

    private JdbcTemplate db;
    private ModelRouter modelRouter;
    private SemanticSkillMatcher matcher;

    @BeforeEach
    void setUp() {
        db = mock(JdbcTemplate.class);
        modelRouter = mock(ModelRouter.class);
        matcher = new SemanticSkillMatcher(db, modelRouter);
        // Default: cache misses (empty lists)
        when(db.queryForList(contains("skill_similarity_cache"), anyString(), anyString()))
                .thenReturn(List.of());
    }

    // ── Null / empty edge cases ──

    @Test
    void nullJobSkillsReturnNeutral() {
        var result = matcher.computeOverlap(null, Set.of("Java"));
        assertThat(result.ratio()).isEqualTo(0.5);
        assertThat(result.jobSkillCount()).isZero();
    }

    @Test
    void emptyJobSkillsReturnNeutral() {
        var result = matcher.computeOverlap(List.of(), Set.of("Java"));
        assertThat(result.ratio()).isEqualTo(0.5);
    }

    @Test
    void emptyCandidateSkillsReturnZero() {
        var result = matcher.computeOverlap(List.of("Java"), Set.of());
        assertThat(result.ratio()).isEqualTo(0.0);
        assertThat(result.candidateHasSkills()).isFalse();
    }

    @Test
    void nullCandidateSkillsReturnZero() {
        var result = matcher.computeOverlap(List.of("Java"), null);
        assertThat(result.ratio()).isEqualTo(0.0);
    }

    // ── Tier 1: Exact matching ──

    @Test
    void exactMatchCaseInsensitive() {
        var result = matcher.computeOverlap(List.of("Java", "Spring"), Set.of("java", "spring"));
        assertThat(result.ratio()).isEqualTo(1.0);
        assertThat(result.matches()).hasSize(2);
        assertThat(result.matches()).allMatch(m -> m.source().equals("EXACT"));
    }

    @Test
    void partialExactMatch() {
        var result = matcher.computeOverlap(List.of("Java", "Go", "Rust"), Set.of("Java"));
        assertThat(result.ratio()).isCloseTo(1.0 / 3.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(result.matches()).hasSize(1);
    }

    // ── Tier 2: Synonym matching ──

    @Test
    void synonymMatchReactAndReactJs() {
        var result = matcher.computeOverlap(List.of("ReactJS"), Set.of("React"));
        assertThat(result.ratio()).isEqualTo(1.0);
        assertThat(result.matches()).hasSize(1);
        assertThat(result.matches().get(0).source()).isEqualTo("SYNONYM");
    }

    @Test
    void synonymMatchAwsAndAmazonWebServices() {
        var result = matcher.computeOverlap(List.of("Amazon Web Services"), Set.of("AWS"));
        assertThat(result.ratio()).isEqualTo(1.0);
        assertThat(result.matches().get(0).source()).isEqualTo("SYNONYM");
    }

    @Test
    void synonymMatchK8sAndKubernetes() {
        var result = matcher.computeOverlap(List.of("k8s"), Set.of("Kubernetes"));
        assertThat(result.ratio()).isEqualTo(1.0);
    }

    @Test
    void synonymMatchPostgresVariants() {
        var result = matcher.computeOverlap(List.of("PostgreSQL"), Set.of("Postgres"));
        assertThat(result.ratio()).isEqualTo(1.0);
    }

    @Test
    void mixedExactAndSynonymMatches() {
        var result = matcher.computeOverlap(
                List.of("Java", "ReactJS", "k8s"),
                Set.of("Java", "React", "Kubernetes"));
        assertThat(result.ratio()).isEqualTo(1.0);
        assertThat(result.matches()).hasSize(3);
    }

    // ── Tier 3: LLM matching ──

    @Test
    void llmMatchUsedForUnknownSkills() {
        // Set up LLM to return high similarity for "TypeScript" vs "JavaScript"
        var completion = new LlmCompletion("[0.85]", new LlmCompletion.TokenUsage(50, 10), 100, LlmCompletion.FinishReason.STOP);
        var trace = new RoutingTrace(TaskType.SKILL_MATCHING, "test", "PRIMARY_SUCCESS", List.of());
        when(modelRouter.execute(eq(TaskType.SKILL_MATCHING), any(), any()))
                .thenReturn(new ModelRouter.ExecutionResult(completion, trace));

        var result = matcher.computeOverlap(List.of("TypeScript"), Set.of("JavaScript"));
        assertThat(result.ratio()).isCloseTo(0.85, org.assertj.core.data.Offset.offset(0.01));
        assertThat(result.matches()).hasSize(1);
        assertThat(result.matches().get(0).source()).isEqualTo("LLM");
    }

    @Test
    void llmScoreBelowThresholdIsNotAMatch() {
        var completion = new LlmCompletion("[0.3]", new LlmCompletion.TokenUsage(50, 10), 100, LlmCompletion.FinishReason.STOP);
        var trace = new RoutingTrace(TaskType.SKILL_MATCHING, "test", "PRIMARY_SUCCESS", List.of());
        when(modelRouter.execute(eq(TaskType.SKILL_MATCHING), any(), any()))
                .thenReturn(new ModelRouter.ExecutionResult(completion, trace));

        var result = matcher.computeOverlap(List.of("Python"), Set.of("Carpentry"));
        assertThat(result.ratio()).isEqualTo(0.0);
        assertThat(result.matches()).isEmpty();
    }

    @Test
    void llmFailureGracefullyFallsBackToExactAndSynonym() {
        when(modelRouter.execute(eq(TaskType.SKILL_MATCHING), any(), any()))
                .thenThrow(new RuntimeException("All providers exhausted"));

        // "Java" matches exactly, "TypeScript" has no synonym for "JavaScript" → LLM fails → no match
        var result = matcher.computeOverlap(List.of("Java", "TypeScript"), Set.of("Java", "JavaScript"));
        // Java matches exactly (1.0), TypeScript LLM fails (0.0) → 1.0/2 = 0.5
        assertThat(result.ratio()).isCloseTo(0.5, org.assertj.core.data.Offset.offset(0.01));
        assertThat(result.matches()).hasSize(1); // only Java
    }

    // ── Cache behaviour ──

    @Test
    void cachedScoreIsUsedInsteadOfLlmCall() {
        // Pre-populate cache with a high score
        when(db.queryForList(contains("skill_similarity_cache"),
                eq("javascript"), eq("typescript")))
                .thenReturn(List.of(Map.of("score", 0.9)));

        var result = matcher.computeOverlap(List.of("TypeScript"), Set.of("JavaScript"));
        assertThat(result.ratio()).isCloseTo(0.9, org.assertj.core.data.Offset.offset(0.01));
        assertThat(result.matches().get(0).source()).isEqualTo("LLM_CACHED");
        // LLM should NOT be called
        verify(modelRouter, never()).execute(any(), any(), any());
    }

    @Test
    void llmResultIsCachedAfterCall() {
        var completion = new LlmCompletion("[0.8]", new LlmCompletion.TokenUsage(50, 10), 100, LlmCompletion.FinishReason.STOP);
        var trace = new RoutingTrace(TaskType.SKILL_MATCHING, "test", "PRIMARY_SUCCESS", List.of());
        when(modelRouter.execute(eq(TaskType.SKILL_MATCHING), any(), any()))
                .thenReturn(new ModelRouter.ExecutionResult(completion, trace));

        matcher.computeOverlap(List.of("FastAPI"), Set.of("Django"));

        // Verify cache insert was called
        verify(db).update(contains("skill_similarity_cache"), anyString(), anyString(), eq(0.8));
    }

    // ── Score parsing ──

    @Test
    void parseScoreArrayHandlesCleanJson() {
        var scores = SemanticSkillMatcher.parseScoreArray("[0.9, 0.5, 0.1]", 3);
        assertThat(scores).containsExactly(0.9, 0.5, 0.1);
    }

    @Test
    void parseScoreArrayHandlesCodeFences() {
        var scores = SemanticSkillMatcher.parseScoreArray("```json\n[0.8, 0.3]\n```", 2);
        assertThat(scores).containsExactly(0.8, 0.3);
    }

    @Test
    void parseScoreArrayPadsShortResponse() {
        var scores = SemanticSkillMatcher.parseScoreArray("[0.9]", 3);
        assertThat(scores).containsExactly(0.9, 0.0, 0.0);
    }

    @Test
    void parseScoreArrayClampsToBounds() {
        var scores = SemanticSkillMatcher.parseScoreArray("[1.5, -0.3]", 2);
        assertThat(scores).containsExactly(1.0, 0.0);
    }

    @Test
    void parseScoreArrayHandlesGarbage() {
        var scores = SemanticSkillMatcher.parseScoreArray("not a valid response", 2);
        assertThat(scores).hasSize(2);
    }

    // ── Multiple job skills with mixed tiers ──

    @Test
    void complexScenarioMixesAllThreeTiers() {
        // Set up LLM for skills that don't match exactly or by synonym.
        // After exact (Java) and synonym (ReactJS→React), unmatched are TensorFlow & FastAPI.
        // They get paired with ALL candidate skills, producing up to 8 pairs (before dedup).
        // Return enough 0.75 scores so every pair is covered.
        var completion = new LlmCompletion("[0.75, 0.75, 0.75, 0.75, 0.75, 0.75, 0.75, 0.75]",
                new LlmCompletion.TokenUsage(50, 10), 100, LlmCompletion.FinishReason.STOP);
        var trace = new RoutingTrace(TaskType.SKILL_MATCHING, "test", "PRIMARY_SUCCESS", List.of());
        when(modelRouter.execute(eq(TaskType.SKILL_MATCHING), any(), any()))
                .thenReturn(new ModelRouter.ExecutionResult(completion, trace));

        var result = matcher.computeOverlap(
                List.of("Java", "ReactJS", "TensorFlow", "FastAPI"),
                Set.of("Java", "React", "PyTorch", "Django"));

        // Java: exact (1.0)
        // ReactJS: synonym to React (1.0)
        // TensorFlow: best LLM match → 0.75
        // FastAPI: best LLM match → 0.75
        // Total: (1.0 + 1.0 + 0.75 + 0.75) / 4 = 0.875
        assertThat(result.ratio()).isCloseTo(0.875, org.assertj.core.data.Offset.offset(0.05));
        assertThat(result.matches().size()).isGreaterThanOrEqualTo(2); // at least exact + synonym
    }
}
