package com.personal.jobagent.adversarial;

import com.personal.jobagent.jobs.HardFilterResult;
import com.personal.jobagent.jobs.HardFilterService;
import com.personal.jobagent.jobs.JobRecord;
import com.personal.jobagent.jobs.SeniorityClassifier;
import com.personal.jobagent.jobs.SeniorityClassifier.Level;
import com.personal.jobagent.preferences.PreferenceSetRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Adversarial QA Suite: Matching Integrity, Seniority Distancing & Hard-Filter Invariants")
class MatchingIntegrityAdversarialTest {

    private final HardFilterService hardFilterService = new HardFilterService(null);

    private JobRecord jobWithTitle(String title) {
        return new JobRecord(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "ext-123",
                UUID.randomUUID(),
                "Acme Corp",
                title,
                "London, UK",
                "London",
                "United Kingdom",
                "HYBRID",
                "FULL_TIME",
                "SENIOR",
                BigDecimal.valueOf(80000),
                BigDecimal.valueOf(100000),
                "GBP",
                "Great senior engineering role description",
                List.of("Java", "Spring", "PostgreSQL"),
                "https://example.com/jobs/1",
                "https://example.com/jobs/1",
                Instant.now(),
                "ACTIVE"
        );
    }

    private PreferenceSetRecord prefsWithTitles(List<String> titles) {
        return new PreferenceSetRecord(
                UUID.randomUUID(),
                UUID.randomUUID(),
                titles,
                List.of(),
                List.of(),
                List.of(),
                List.of("London"),
                List.of("HYBRID"),
                List.of("FULL_TIME"),
                List.of("SENIOR"),
                70000L,
                "VISA_SPONSORED",
                List.of(),
                List.of(),
                "MANUAL",
                Map.of("skills", 30, "title", 30, "location", 20, "salary", 20),
                Map.of(),
                true
        );
    }

    // ── Seniority Classification & Alignment ─────────────────────────────

    @Test
    @DisplayName("Adversarial: Seniority ordinal distance strictly penalizes mismatched experience tiers")
    void testSeniorityOrdinalDistanceStrictPenalties() {
        Level intern = SeniorityClassifier.classifyJob(null, "Software Engineering Intern");
        Level entry = SeniorityClassifier.classifyJob(null, "Junior Software Engineer");
        Level mid = SeniorityClassifier.classifyJob(null, "Software Engineer");
        Level senior = SeniorityClassifier.classifyJob(null, "Senior Software Engineer");
        Level lead = SeniorityClassifier.classifyJob(null, "Staff Software Engineer");
        Level principal = SeniorityClassifier.classifyJob(null, "Principal Engineer");
        Level exec = SeniorityClassifier.classifyJob(null, "Director of Engineering");

        assertThat(intern).isEqualTo(Level.INTERN);
        assertThat(entry).isEqualTo(Level.ENTRY);
        assertThat(mid).isEqualTo(Level.MID);
        assertThat(senior).isEqualTo(Level.SENIOR);
        assertThat(lead).isEqualTo(Level.LEAD);
        assertThat(principal).isEqualTo(Level.PRINCIPAL);
        assertThat(exec).isEqualTo(Level.EXECUTIVE);

        // Target: Candidate wants SENIOR
        List<String> allowedSenior = List.of("SENIOR");
        double exactMatch = SeniorityClassifier.computeAlignmentScore(senior, allowedSenior);
        double adjacentLead = SeniorityClassifier.computeAlignmentScore(lead, allowedSenior);
        double adjacentMid = SeniorityClassifier.computeAlignmentScore(mid, allowedSenior);
        double distantEntry = SeniorityClassifier.computeAlignmentScore(entry, allowedSenior);
        double extremeExec = SeniorityClassifier.computeAlignmentScore(exec, allowedSenior);
        double extremeIntern = SeniorityClassifier.computeAlignmentScore(intern, allowedSenior);

        // Monotonic descent guarantees
        assertThat(exactMatch).isEqualTo(1.0);
        assertThat(adjacentLead).isEqualTo(0.75);
        assertThat(adjacentMid).isEqualTo(0.75);
        assertThat(distantEntry).isEqualTo(0.35);
        assertThat(extremeExec).isEqualTo(0.05);
        assertThat(extremeIntern).isEqualTo(0.05);

        assertThat(exactMatch).isGreaterThan(adjacentLead);
        assertThat(adjacentLead).isGreaterThan(distantEntry);
        assertThat(distantEntry).isGreaterThan(extremeIntern);
    }

    @Test
    @DisplayName("Adversarial: Explicit level overrides misleading title keywords")
    void testExplicitLevelOverridesMisleadingTitle() {
        // e.g. "Lead Support for Junior Engineers" explicitly tagged as JUNIOR
        Level level = SeniorityClassifier.classifyJob("ENTRY", "Lead Assistant to Junior Cohort");
        assertThat(level).isEqualTo(Level.ENTRY);
    }

    // ── Hard Filter Proximity & Title Matching ────────────────────────────

    @Test
    @DisplayName("Adversarial: Forward ordered token proximity accepts valid variations but rejects inverted tokens")
    void testForwardOrderedProximityRejectsInversion() {
        List<String> target = List.of("Software Engineer");

        // Forward match: exact or 1 word in between
        assertThat(HardFilterService.matchesAnyTitle("Software Engineer", target)).isTrue();
        assertThat(HardFilterService.matchesAnyTitle("Senior Software Engineer", target)).isTrue();
        assertThat(HardFilterService.matchesAnyTitle("Software Cloud Engineer", target)).isTrue();

        // Adversarial: inverted order must NOT match
        assertThat(HardFilterService.matchesAnyTitle("Engineer Software", target)).isFalse();

        // Adversarial: excessive gap (>1 token between) must NOT match 2-token proximity
        assertThat(HardFilterService.matchesAnyTitle("Software Systems Architecture Platform Engineer", target)).isFalse();
    }

    @Test
    @DisplayName("Adversarial: Title synonyms match intended semantic roles without substring bleed")
    void testTitleSynonymsSemanticsAndNoBleed() {
        // Frontend synonyms
        assertThat(HardFilterService.matchesAnyTitle("Frontend Developer", List.of("Front End Developer"))).isTrue();
        assertThat(HardFilterService.matchesAnyTitle("Front End Developer", List.of("Frontend Developer"))).isTrue();

        // Backend synonyms
        assertThat(HardFilterService.matchesAnyTitle("Backend Engineer", List.of("Back End Engineer"))).isTrue();

        // Fullstack synonyms
        assertThat(HardFilterService.matchesAnyTitle("Full Stack Developer", List.of("Fullstack Developer"))).isTrue();

        // Adversarial: partial word overlap should not bleed falsely
        assertThat(HardFilterService.matchesAnyTitle("International Trade Specialist", List.of("Intern"))).isFalse();
        assertThat(HardFilterService.matchesAnyTitle("Director of Operations", List.of("CTO"))).isFalse();
    }

    @Test
    @DisplayName("Adversarial: Hard filter rejects incompatible candidate constraints deterministically")
    void testHardFilterStrictRejection() {
        JobRecord job = jobWithTitle("Junior Graphic Designer");
        PreferenceSetRecord prefs = prefsWithTitles(List.of("Senior Software Engineer"));

        HardFilterResult result = hardFilterService.evaluate(job, prefs);
        assertThat(result.passed()).isFalse();
        assertThat(result.reasons()).anyMatch(r -> r.criterion().equals("TITLE"));
    }
}
