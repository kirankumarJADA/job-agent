package com.personal.jobagent.apply;

import com.personal.jobagent.application.ApplicationDecisionService;
import com.personal.jobagent.automation.ApplyDocumentSelector;
import com.personal.jobagent.ats.JobFormQuestionService;
import com.personal.jobagent.ats.JobFormQuestionService.CoverLetterRequirement;
import com.personal.jobagent.ats.AtsAdapter.RequiredState;
import com.personal.jobagent.common.QuotaService;
import com.personal.jobagent.qa.ApplicationAnswerRecord;
import com.personal.jobagent.qa.ApplicationAnswerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Phase 8.2 pre-approval readiness: derived only from real records, unknown
 * information stays unknown, blockers are actionable, and a dependency
 * failure is an explicit unavailable state — never a false success.
 */
class ApplyReadinessServiceTest {

    private final UUID profile = UUID.randomUUID();
    private final UUID job = UUID.randomUUID();
    private final UUID application = UUID.randomUUID();

    private JdbcTemplate db;
    private ApplyDocumentSelector selector;
    private JobFormQuestionService formQuestions;
    private ApplicationAnswerRepository answers;
    private ApplicationIdentityService identity;
    private ApplicationDecisionService decisions;
    private QuotaService quotas;
    private ApplyReadinessService service;

    @BeforeEach
    void setUp() {
        db = Mockito.mock(JdbcTemplate.class);
        selector = Mockito.mock(ApplyDocumentSelector.class);
        formQuestions = Mockito.mock(JobFormQuestionService.class);
        answers = Mockito.mock(ApplicationAnswerRepository.class);
        identity = Mockito.mock(ApplicationIdentityService.class);
        decisions = Mockito.mock(ApplicationDecisionService.class);
        quotas = Mockito.mock(QuotaService.class);
        service = new ApplyReadinessService(db, selector, formQuestions, answers, identity, decisions, quotas);

        when(db.queryForMap(anyString(), eq(application), eq(profile))).thenReturn(new java.util.LinkedHashMap<>(Map.of(
                "id", application, "job_id", job, "status", "READY_TO_APPLY", "cv_version_id", UUID.randomUUID(),
                "title", "Platform Engineer", "company_name_raw", "Fixture Co",
                "application_url", "https://boards.greenhouse.io/fixture/jobs/1", "last_seen_at", Instant.now())));
        when(selector.select(profile, job, application)).thenReturn(new ApplyDocumentSelector.DocumentSelection(
                new ApplyDocumentSelector.SelectedCv(UUID.randomUUID(), "sha", 10, Instant.now().toString()),
                null, CoverLetterRequirement.UNKNOWN, List.of(), List.of()));
        when(formQuestions.questionsForJob(job)).thenReturn(List.of());
        when(answers.findByJobIdForProfile(job, profile)).thenReturn(List.of());
        when(identity.findDuplicates(profile, job)).thenReturn(List.of());
        when(decisions.effectiveApplicationMode(profile)).thenReturn("ASSISTED");
        when(decisions.ruleState(profile)).thenReturn(new ApplicationDecisionService.RuleState(
                ApplicationDecisionService.RuleAvailability.ABSENT, null, null));
        when(quotas.check(eq(profile), anyString())).thenReturn(new QuotaService.QuotaCheckResult(true, 1, 5, "day"));
        when(db.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), eq(profile), eq(job)))
                .thenReturn(List.of(
                Map.of("decision", "NEEDS_REVIEW", "reason", "score", "recommendation", "APPLY", "matchScore", 80)));
    }

    @Test
    void aRequiredQuestionWithoutAnAnswerIsABlocker() {
        when(formQuestions.questionsForJob(job)).thenReturn(List.of(question("question_9", "Why us?", RequiredState.REQUIRED)));

        Map<String, Object> state = service.evaluate(profile, application);

        assertThat(state.get("packageReady")).isEqualTo(false);
        assertThat(blockerCodes(state)).contains("REQUIRED_QUESTION_UNANSWERED");
    }

    @Test
    void anUnconfirmedDraftForARequiredQuestionIsABlocker() {
        when(formQuestions.questionsForJob(job)).thenReturn(List.of(question("question_9", "Why us?", RequiredState.REQUIRED)));
        when(answers.findByJobIdForProfile(job, profile)).thenReturn(List.of(
                answer("Why us?", "Because.", "ANSWERED", false)));

        Map<String, Object> state = service.evaluate(profile, application);

        assertThat(state.get("packageReady")).isEqualTo(false);
        assertThat(blockerCodes(state)).contains("REQUIRED_ANSWER_NOT_CONFIRMED");
    }

    @Test
    void aConfirmedAnswerForARequiredQuestionClearsTheBlocker() {
        when(formQuestions.questionsForJob(job)).thenReturn(List.of(question("question_9", "Why us?", RequiredState.REQUIRED)));
        when(answers.findByJobIdForProfile(job, profile)).thenReturn(List.of(
                answer("Why us?", "Because.", "ANSWERED", true)));

        Map<String, Object> state = service.evaluate(profile, application);

        assertThat(blockerCodes(state)).doesNotContain("REQUIRED_ANSWER_NOT_CONFIRMED", "REQUIRED_QUESTION_UNANSWERED");
    }

    @Test
    void aHardStoppedAnswerIsABlocker() {
        when(answers.findByJobIdForProfile(job, profile)).thenReturn(List.of(
                answer("Salary expectation?", "one million", "HARD_STOP", false)));

        Map<String, Object> state = service.evaluate(profile, application);

        assertThat(state.get("packageReady")).isEqualTo(false);
        assertThat(blockerCodes(state)).contains("ANSWER_HARD_STOP");
    }

    @Test
    void anUncapturedFormIsReportedAsUnknownNeverAsSatisfied() {
        Map<String, Object> state = service.evaluate(profile, application);

        assertThat((List<?>) state.get("unknowns")).isNotEmpty();
        assertThat(String.valueOf(state.get("unknowns"))).contains("not been captured");
    }

    @Test
    void aDuplicateApplicationIsABlocker() {
        when(identity.findDuplicates(profile, job)).thenReturn(List.of(
                new ApplicationIdentityService.ExistingApplication(UUID.randomUUID(), UUID.randomUUID(),
                        "READY_TO_APPLY", "greenhouse:fixture:1", "same Greenhouse requisition identifier")));

        Map<String, Object> state = service.evaluate(profile, application);

        assertThat(state.get("packageReady")).isEqualTo(false);
        assertThat(blockerCodes(state)).contains("DUPLICATE_APPLICATION");
    }

    @Test
    void documentSelectionBlockersAreReadinessBlockers() {
        when(selector.select(profile, job, application)).thenReturn(new ApplyDocumentSelector.DocumentSelection(
                null, null, CoverLetterRequirement.UNKNOWN, List.of(),
                List.of(Map.of("area", "CV", "code", "CV_REVIEW_REQUIRED", "message", "review it"))));

        Map<String, Object> state = service.evaluate(profile, application);

        assertThat(state.get("packageReady")).isEqualTo(false);
        assertThat(blockerCodes(state)).contains("CV_REVIEW_REQUIRED");
    }

    @Test
    void anUnreadableRuleIsAWarningNotAFakeSuccess() {
        when(decisions.ruleState(profile)).thenReturn(new ApplicationDecisionService.RuleState(
                ApplicationDecisionService.RuleAvailability.UNREADABLE, null,
                ApplicationDecisionService.RuleUnavailableCause.QUERY_FAILED));

        Map<String, Object> state = service.evaluate(profile, application);

        assertThat(String.valueOf(state.get("warnings"))).contains("RULE_UNREADABLE");
    }

    @Test
    void aForeignApplicationReadsExactlyLikeAMissingOne() {
        when(db.queryForMap(anyString(), eq(application), eq(profile)))
                .thenThrow(new org.springframework.dao.EmptyResultDataAccessException(1));

        assertThatThrownBy(() -> service.evaluate(profile, application))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void aDependencyFailureIsAnExplicitUnavailableState() {
        when(db.queryForMap(anyString(), eq(application), eq(profile)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> service.evaluate(profile, application))
                .isInstanceOf(ApplyReadinessService.ReadinessUnavailableException.class);
    }

    @Test
    void readinessIsRecomputedFromCurrentRecordsOnEveryCall() {
        // First call: ready. Then the only CV review is withdrawn.
        assertThat(service.evaluate(profile, application).get("packageReady")).isEqualTo(true);
        when(selector.select(profile, job, application)).thenReturn(new ApplyDocumentSelector.DocumentSelection(
                null, null, CoverLetterRequirement.UNKNOWN, List.of(),
                List.of(Map.of("area", "CV", "code", "CV_REVIEW_REQUIRED", "message", "review it"))));

        assertThat(service.evaluate(profile, application).get("packageReady")).isEqualTo(false);
    }

    // ── fixtures ─────────────────────────────────────────────────────

    private static JobFormQuestionService.CapturedQuestion question(String key, String text, RequiredState required) {
        return new JobFormQuestionService.CapturedQuestion(UUID.randomUUID(), key, text, required,
                "textarea", List.of(), JobFormQuestionService.SOURCE_GREENHOUSE,
                "https://boards.greenhouse.io/fixture/jobs/1", "INSPECTED", Instant.now());
    }

    private ApplicationAnswerRecord answer(String questionText, String text, String status, boolean confirmed) {
        return new ApplicationAnswerRecord(UUID.randomUUID(), profile, job, application,
                questionText, "GENERAL_OPEN_ENDED", text, new BigDecimal("0.9"), status, Map.of(),
                confirmed, Instant.now(), Instant.now(), null,
                confirmed ? "CANDIDATE_CONFIRMED" : "MODEL_DRAFT");
    }

    @SuppressWarnings("unchecked")
    private static List<String> blockerCodes(Map<String, Object> state) {
        return ((List<Map<String, Object>>) state.get("blockers")).stream()
                .map(b -> String.valueOf(b.get("code"))).toList();
    }
}
