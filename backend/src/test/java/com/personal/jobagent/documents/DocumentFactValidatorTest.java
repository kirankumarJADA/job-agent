package com.personal.jobagent.documents;

import com.personal.jobagent.documents.DocumentFactValidator.CandidateFacts;
import com.personal.jobagent.documents.DocumentFactValidator.Finding;
import com.personal.jobagent.documents.DocumentFactValidator.JobContext;
import com.personal.jobagent.documents.DocumentFactValidator.Kind;
import com.personal.jobagent.documents.DocumentFactValidator.Severity;
import com.personal.jobagent.profile.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentFactValidatorTest {

    private static final UUID P = UUID.randomUUID();
    private final DocumentFactValidator validator = new DocumentFactValidator();

    private static CandidateFacts facts(Map<String, Object> eligibility) {
        return new CandidateFacts(
                List.of(new SkillRecord(UUID.randomUUID(), P, "Java", "Languages", 4, BigDecimal.valueOf(3), null)),
                List.of(new WorkExperienceRecord(UUID.randomUUID(), P, "Deliveroo", "Software Engineer",
                        LocalDate.of(2021, 1, 1), LocalDate.of(2023, 6, 1), "London",
                        List.of(Map.of("text", "Reduced checkout latency by 30% using Java")), 1)),
                List.of(new EducationRecord(UUID.randomUUID(), P, "Imperial College", "BSc Computer Science", "Computing", 2017, 2020, "First")),
                List.of(),
                List.of(),
                eligibility,
                "Backend engineer.");
    }

    private List<String> codes(String text, CandidateFacts facts) {
        return validator.validate(text, facts, new JobContext("Monzo", List.of("Java", "Kubernetes")))
                .findings().stream().map(Finding::code).toList();
    }

    @Test
    void groundedTextPasses() {
        var report = validator.validate("At Deliveroo (2021–2023) I reduced checkout latency by 30% using Java. "
                        + "I hold a BSc Computer Science from Imperial College (2020) and want to join Monzo.",
                facts(Map.of()), new JobContext("Monzo", List.of("Java")));
        assertThat(report.passed()).isTrue();
        assertThat(report.findings()).isEmpty();
    }

    @Test
    void yearsInsideADatedRangeAreSupportedButOthersAreBlockers() {
        assertThat(codes("In 2022 I shipped a payments service.", facts(Map.of()))).doesNotContain("UNSUPPORTED_DATE");
        var report = validator.validate("Since 2015 I have led teams.", facts(Map.of()), null);
        assertThat(report.passed()).isFalse();
        assertThat(report.findings()).anySatisfy(f -> {
            assertThat(f.code()).isEqualTo("UNSUPPORTED_DATE");
            assertThat(f.severity()).isEqualTo(Severity.BLOCKER);
            assertThat(f.kind()).isEqualTo(Kind.DETERMINISTIC);
        });
    }

    @Test
    void inventedYearsOfExperienceFiguresDegreesAndCertificationsAreBlockers() {
        List<String> codes = codes("I bring 12 years of experience, grew revenue by £2m and 45%, hold a PhD and am AWS Certified.",
                facts(Map.of()));
        assertThat(codes).contains("UNSUPPORTED_YEARS_OF_EXPERIENCE", "UNSUPPORTED_FIGURE",
                "UNSUPPORTED_QUALIFICATION", "UNSUPPORTED_CERTIFICATION");
    }

    @Test
    void workAuthorisationIsABlockerWithoutRecordedEligibilityAndAWarningWithIt() {
        var none = validator.validate("I have the right to work in the UK.", facts(Map.of()), null);
        assertThat(none.findings()).extracting(Finding::code).contains("UNSUPPORTED_WORK_AUTHORISATION");
        assertThat(none.passed()).isFalse();

        var recorded = validator.validate("I have the right to work in the UK.", facts(Map.of("uk", "settled")), null);
        assertThat(recorded.passed()).isTrue();
        assertThat(recorded.findings()).extracting(Finding::code).containsExactly("WORK_AUTHORISATION_STATEMENT");
    }

    @Test
    void clearanceClaimsAreBlockers() {
        assertThat(codes("I hold active SC clearance and top secret access.", facts(Map.of())))
                .contains("UNSUPPORTED_CLEARANCE");
    }

    @Test
    void heuristicFindingsAreWarningsNotBlockers() {
        var report = validator.validate("I worked at Google on Kubernetes clusters.", facts(Map.of()),
                new JobContext("Monzo", List.of("Kubernetes")));
        assertThat(report.passed()).isTrue();
        assertThat(report.findings()).allSatisfy(f -> {
            assertThat(f.severity()).isEqualTo(Severity.WARNING);
            assertThat(f.kind()).isEqualTo(Kind.HEURISTIC);
        });
        assertThat(report.findings()).extracting(Finding::code)
                .contains("UNRECOGNISED_ORGANISATION", "REQUIREMENT_WITHOUT_EVIDENCE");
    }

    @Test
    void theHiringCompanyIsNotTreatedAsAClaimedEmployer() {
        assertThat(codes("I would love a role at Monzo.", facts(Map.of()))).doesNotContain("UNRECOGNISED_ORGANISATION");
    }

    @Test
    void reportExposesScopeAndCountsWithoutClaimingFullVerification() {
        Map<String, Object> map = validator.validate("Since 2015.", facts(Map.of()), null).toMap();
        assertThat(map).containsEntry("passed", false).containsEntry("blocker_count", 1L)
                .containsEntry("validator_version", DocumentFactValidator.VERSION);
        assertThat(String.valueOf(map.get("scope"))).contains("does not prove every sentence is true");
    }
}
