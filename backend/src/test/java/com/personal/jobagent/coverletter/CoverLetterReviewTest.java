package com.personal.jobagent.coverletter;

import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.jobs.JobRecord;
import com.personal.jobagent.jobs.JobRepository;
import com.personal.jobagent.llm.*;
import com.personal.jobagent.notifications.NotificationService;
import com.personal.jobagent.profile.*;
import com.personal.jobagent.security.AppUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase 8.1 cover-letter review rules: generated letters start unapproved,
 * corrections are new versions with lineage, foreign applications are
 * refused, letters with blocking findings cannot be approved, and the PDF
 * is a readable rendering of the letter.
 */
class CoverLetterReviewTest {

    private final UUID profileId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private CoverLetterRepository repository;
    private ProfileRepository profiles;
    private ModelRouter router;
    private CoverLetterService service;

    @BeforeEach
    void setUp() {
        repository = mock(CoverLetterRepository.class);
        JobRepository jobs = mock(JobRepository.class);
        profiles = mock(ProfileRepository.class);
        router = mock(ModelRouter.class);
        service = new CoverLetterService(repository, jobs, profiles, router, mock(NotificationService.class));
        when(jobs.findById(jobId)).thenReturn(Optional.of(new JobRecord(jobId, UUID.randomUUID(), "x", null, "Monzo",
                "Backend Engineer", "London", null, null, "HYBRID", null, null, null, null, null,
                "Java role", List.of("Java"), null, null, null, "DISCOVERED")));
        when(profiles.findExperiences(profileId)).thenReturn(List.of(new WorkExperienceRecord(UUID.randomUUID(), profileId,
                "Deliveroo", "Engineer", LocalDate.of(2021, 1, 1), null, "London", List.of(), 1)));
        when(profiles.findSkills(profileId)).thenReturn(List.of(new SkillRecord(UUID.randomUUID(), profileId, "Java", null, 4, null, null)));
        when(profiles.findContact(profileId)).thenReturn(Optional.of(new ContactRecord("Zoë Brontë", "zoe@example.test")));
    }

    private static HttpServletRequest request() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/v1/cover-letters/x/approval");
        return request;
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private void llmReturns(String text) {
        when(router.execute(eq(TaskType.COVER_LETTER), any(), any(Duration.class))).thenReturn(new ModelRouter.ExecutionResult(
                new LlmCompletion(text, new LlmCompletion.TokenUsage(1, 1), 1, LlmCompletion.FinishReason.STOP),
                new RoutingTrace(TaskType.COVER_LETTER, "simulated", "PRIMARY", List.of())));
    }

    @Test
    void aGeneratedLetterIsNeverAutoApprovedEvenWhenItPassesValidation() {
        UUID id = UUID.randomUUID();
        llmReturns("At Deliveroo I build Java services.");
        when(repository.getNextVersion(jobId, profileId)).thenReturn(1);
        when(repository.insert(any(), any(), any(), anyInt(), anyString(), anyString(), anyString(), anyMap(), anyBoolean())).thenReturn(id);
        when(repository.findById(id)).thenReturn(Optional.of(new CoverLetterRecord(id, profileId, jobId, null, 1, "t",
                "At Deliveroo I build Java services.", Map.of(), false, Instant.now(), Instant.now())));

        var result = service.generateCoverLetter(profileId, jobId, null);

        assertThat(result.passedValidation()).isTrue();
        ArgumentCaptor<Boolean> approved = ArgumentCaptor.forClass(Boolean.class);
        verify(repository).insert(any(), any(), any(), anyInt(), anyString(), anyString(), anyString(), anyMap(), approved.capture());
        assertThat(approved.getValue()).isFalse();
        verify(repository).attachPdf(eq(id), eq(profileId), any(byte[].class), anyString());
    }

    @Test
    void aForeignOrMismatchedApplicationIsRefusedBeforeAnyGeneration() {
        UUID foreignApplication = UUID.randomUUID();
        when(repository.applicationBelongs(profileId, jobId, foreignApplication)).thenReturn(false);

        assertThatThrownBy(() -> service.generateCoverLetter(profileId, jobId, foreignApplication))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(router);
        verify(repository, never()).insert(any(), any(), any(), anyInt(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void aCorrectionIsANewRevalidatedVersionThatPointsAtTheVersionItCorrects() {
        UUID original = UUID.randomUUID();
        UUID application = UUID.randomUUID();
        UUID corrected = UUID.randomUUID();
        when(repository.findByIdForProfile(original, profileId)).thenReturn(Optional.of(new CoverLetterRecord(original, profileId,
                jobId, application, 1, "v1", "I have 15 years of Java.", Map.of("passed", false), false, Instant.now(), Instant.now())));
        when(repository.getNextVersion(jobId, profileId)).thenReturn(2);
        when(repository.insertCorrection(any(), any(), any(), anyInt(), anyString(), anyString(), anyString(), anyMap(), any()))
                .thenReturn(corrected);
        when(repository.findByIdForProfile(corrected, profileId)).thenReturn(Optional.of(new CoverLetterRecord(corrected, profileId,
                jobId, application, 2, "v2", "At Deliveroo I build Java services.", Map.of(), false, Instant.now(), Instant.now())));

        var result = service.correct(profileId, original, "At Deliveroo I build Java services.");

        assertThat(result.passedValidation()).isTrue();
        verify(repository).insertCorrection(eq(profileId), eq(jobId), eq(application), eq(2), anyString(),
                eq("At Deliveroo I build Java services."), anyString(), anyMap(), eq(original));
        // The original version is never modified.
        verify(repository, never()).updateValidationForProfile(eq(original), any(), any());
        verify(repository, never()).setApprovedForProfile(any(), anyBoolean(), any());
    }

    @Test
    void aCorrectionThatStillInventsFactsKeepsItsBlockers() {
        UUID original = UUID.randomUUID();
        when(repository.findByIdForProfile(original, profileId)).thenReturn(Optional.of(new CoverLetterRecord(original, profileId,
                jobId, null, 1, "v1", "x", Map.of(), false, Instant.now(), Instant.now())));
        when(repository.getNextVersion(jobId, profileId)).thenReturn(2);
        UUID corrected = UUID.randomUUID();
        when(repository.insertCorrection(any(), any(), any(), anyInt(), anyString(), anyString(), anyString(), anyMap(), any()))
                .thenReturn(corrected);
        when(repository.findByIdForProfile(corrected, profileId)).thenReturn(Optional.of(new CoverLetterRecord(corrected, profileId,
                jobId, null, 2, "v2", "I am PMP certified.", Map.of(), false, Instant.now(), Instant.now())));

        var result = service.correct(profileId, original, "I am PMP certified.");

        assertThat(result.passedValidation()).isFalse();
        assertThat(result.issues()).anyMatch(i -> i.contains("pmp"));
    }

    @Test
    void theLetterPdfIsReadableAndCarriesTheCandidateHeader() throws Exception {
        byte[] pdf = service.renderLetterPdf(profileId, "Cover Letter v1", "Dear team,\n\nI build Java services — “reliably”.\n\nZoë");
        try (var doc = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("Zoë Brontë").contains("zoe@example.test").contains("“reliably”");
        }
    }

    @Test
    void approvalIsRefusedForALetterWithBlockingFindingsButWithdrawalIsAllowed() {
        UUID userId = UUID.randomUUID();
        AppUserDetails principal = mock(AppUserDetails.class);
        when(principal.getUserId()).thenReturn(userId);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        when(profiles.findByUserId(userId)).thenReturn(Optional.of(new ProfileRecord(profileId, userId, null, null, null,
                Map.of(), Map.of(), null, Map.of(), 1, "READY")));
        UUID letterId = UUID.randomUUID();
        String body = "I hold a PhD in physics.";
        CoverLetterRecord letter = new CoverLetterRecord(letterId, profileId, jobId, null, 1, "v1", body, Map.of(), false,
                Instant.now(), Instant.now(), CoverLetterService.sha256Hex(body), null, "GENERATED", true);
        when(repository.findByIdForProfile(letterId, profileId)).thenReturn(Optional.of(letter));
        CoverLetterController controller = new CoverLetterController(service, repository, profiles, mock(AuditLogWriter.class));
        HttpServletRequest request = request();

        var refused = controller.setApproval(letterId, new CoverLetterController.ApprovalRequest(true), request);
        assertThat(refused.getStatusCode().value()).isEqualTo(409);
        verify(repository, never()).setApprovedForProfile(letterId, true, profileId);

        var withdrawn = controller.setApproval(letterId, new CoverLetterController.ApprovalRequest(false), request);
        assertThat(withdrawn.getStatusCode().value()).isEqualTo(200);
        verify(repository).setApprovedForProfile(letterId, false, profileId);
    }

    @Test
    void approvalIsRefusedWhenTheStoredBodyNoLongerMatchesItsDigest() {
        UUID userId = UUID.randomUUID();
        AppUserDetails principal = mock(AppUserDetails.class);
        when(principal.getUserId()).thenReturn(userId);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of()));
        when(profiles.findByUserId(userId)).thenReturn(Optional.of(new ProfileRecord(profileId, userId, null, null, null,
                Map.of(), Map.of(), null, Map.of(), 1, "READY")));
        UUID letterId = UUID.randomUUID();
        CoverLetterRecord tampered = new CoverLetterRecord(letterId, profileId, jobId, null, 1, "v1", "edited after generation",
                Map.of(), false, Instant.now(), Instant.now(), CoverLetterService.sha256Hex("original text"), null, "GENERATED", true);
        when(repository.findByIdForProfile(letterId, profileId)).thenReturn(Optional.of(tampered));
        CoverLetterController controller = new CoverLetterController(service, repository, profiles, mock(AuditLogWriter.class));

        var response = controller.setApproval(letterId, new CoverLetterController.ApprovalRequest(true), request());

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        verify(repository, never()).setApprovedForProfile(any(), eq(true), any());
    }
}
