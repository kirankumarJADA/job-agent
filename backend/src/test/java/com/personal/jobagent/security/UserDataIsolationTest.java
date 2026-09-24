package com.personal.jobagent.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.coverletter.CoverLetterController;
import com.personal.jobagent.coverletter.CoverLetterRepository;
import com.personal.jobagent.coverletter.CoverLetterService;
import com.personal.jobagent.identity.CredentialVault;
import com.personal.jobagent.identity.IdentityController;
import com.personal.jobagent.identity.IdentityService;
import com.personal.jobagent.profile.ProfileRecord;
import com.personal.jobagent.profile.ProfileRepository;
import com.personal.jobagent.qa.ApplicationAnswerController;
import com.personal.jobagent.qa.ApplicationAnswerRepository;
import com.personal.jobagent.qa.ApplicationAnswerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cross-account isolation for profile-owned data.
 *
 * <p>Authentication alone is not isolation. These tests cover the two ways an
 * authenticated user could previously reach another account's data through the
 * cover-letter, application-answer and identity endpoints:
 *
 * <ul>
 *   <li>an endpoint taking a bare resource id and never checking who owns it —
 *       asserted here as "a foreign id is a plain 404 and nothing is written";</li>
 *   <li>a repository offering an unscoped query that the controller then used —
 *       asserted here by capturing the SQL actually executed and requiring the
 *       ownership predicate to be present.</li>
 * </ul>
 */
class UserDataIsolationTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID profileId = UUID.randomUUID();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── cover letters ────────────────────────────────────────────────────

    @Test
    void readingAnotherAccountsCoverLetterReturns404() throws Exception {
        CoverLetterRepository repository = mock(CoverLetterRepository.class);
        when(repository.findByIdForProfile(any(), eq(profileId))).thenReturn(Optional.empty());

        CoverLetterController controller = coverLetterController(repository);
        authenticate();

        var response = controller.getCoverLetter(UUID.randomUUID(), request());

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void approvingAnotherAccountsCoverLetterReturns404AndWritesNothing() throws Exception {
        CoverLetterRepository repository = mock(CoverLetterRepository.class);
        when(repository.findByIdForProfile(any(), eq(profileId))).thenReturn(Optional.empty());

        CoverLetterController controller = coverLetterController(repository);
        authenticate();

        var response = controller.setApproval(UUID.randomUUID(),
                new CoverLetterController.ApprovalRequest(true), request());

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        // The write must not happen at all — not merely be filtered afterwards.
        verify(repository, never()).setApprovedForProfile(any(), any(Boolean.class), any());
        verify(repository, never()).setApproved(any(), any(Boolean.class));
    }

    @Test
    void listingCoverLettersForAJobUsesTheProfileScopedQuery() {
        CoverLetterRepository repository = mock(CoverLetterRepository.class);
        when(repository.findByJobIdForProfile(any(), eq(profileId))).thenReturn(List.of());

        CoverLetterController controller = coverLetterController(repository);
        authenticate();

        controller.getCoverLettersByJob(UUID.randomUUID());

        verify(repository).findByJobIdForProfile(any(), eq(profileId));
        // The unscoped variant must never be reached from a request path.
        verify(repository, never()).findByJobId(any());
    }

    @Test
    void coverLetterOwnershipProbeIsCarriedInTheSqlNotJustTheJavaArgument() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        stubQuery(jdbc);
        CoverLetterRepository repository = new CoverLetterRepository(jdbc, new ObjectMapper());
        UUID id = UUID.randomUUID();

        repository.findByIdForProfile(id, profileId);

        String sql = capturedSql(jdbc, id, profileId);
        assertThat(sql).contains("profile_id = ?");
    }

    // ── application answers ──────────────────────────────────────────────

    @Test
    void readingAnotherAccountsAnswerReturns404() throws Exception {
        ApplicationAnswerRepository repository = mock(ApplicationAnswerRepository.class);
        when(repository.findByIdForProfile(any(), eq(profileId))).thenReturn(Optional.empty());

        ApplicationAnswerController controller = answerController(repository);
        authenticate();

        var response = controller.getAnswer(UUID.randomUUID(), request());

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void updatingAnotherAccountsAnswerReturns404AndWritesNothing() throws Exception {
        ApplicationAnswerRepository repository = mock(ApplicationAnswerRepository.class);
        when(repository.findByIdForProfile(any(), eq(profileId))).thenReturn(Optional.empty());

        ApplicationAnswerController controller = answerController(repository);
        authenticate();

        var response = controller.updateAnswer(UUID.randomUUID(),
                new ApplicationAnswerController.UpdateAnswerRequest("ANSWERED", "changed"), request());

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        verify(repository, never()).updateStatusForProfile(any(), anyString(), any(), any());
        verify(repository, never()).updateStatus(any(), anyString(), any());
    }

    @Test
    void listingAnswersForAJobUsesTheProfileScopedQuery() {
        ApplicationAnswerRepository repository = mock(ApplicationAnswerRepository.class);
        when(repository.findByJobIdForProfile(any(), eq(profileId))).thenReturn(List.of());

        ApplicationAnswerController controller = answerController(repository);
        authenticate();

        controller.listByJob(UUID.randomUUID());

        verify(repository).findByJobIdForProfile(any(), eq(profileId));
        verify(repository, never()).findByJobId(any());
    }

    @Test
    void answerOwnershipProbeIsCarriedInTheSqlNotJustTheJavaArgument() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        stubQuery(jdbc);
        ApplicationAnswerRepository repository = new ApplicationAnswerRepository(jdbc, new ObjectMapper());
        UUID id = UUID.randomUUID();

        repository.findByIdForProfile(id, profileId);

        String sql = capturedSql(jdbc, id, profileId);
        assertThat(sql).contains("profile_id = ?");
    }

    // ── identities and automation sessions ───────────────────────────────

    @Test
    void identityCreationUsesTheAuthenticatedProfileAndIgnoresTheRequestBody() {
        IdentityService service = mock(IdentityService.class);
        when(service.create(any(), anyString(), anyString(), any())).thenReturn(UUID.randomUUID());
        IdentityController controller = new IdentityController(service, new CredentialVault(), profileRepository());
        authenticate();
        UUID attackerSuppliedProfile = UUID.randomUUID();

        controller.create(new IdentityController.IdentityRequest(
                attackerSuppliedProfile, "me@example.com", "acme.example", null), request());

        // The profile id from the body is ignored outright — using it was the
        // original cross-account vulnerability.
        verify(service).create(eq(profileId), eq("me@example.com"), eq("acme.example"), any());
        verify(service, never()).create(eq(attackerSuppliedProfile), anyString(), anyString(), any());
    }

    @Test
    void readingAnotherAccountsAutomationSessionReturns404() throws Exception {
        IdentityService service = mock(IdentityService.class);
        when(service.session(any(), eq(profileId))).thenReturn(Optional.empty());
        IdentityController controller = new IdentityController(service, new CredentialVault(), profileRepository());
        authenticate();

        var response = controller.get(UUID.randomUUID(), request());

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void hardStoppingAnotherAccountsSessionIsNotReportedAsChanged() throws Exception {
        IdentityService service = mock(IdentityService.class);
        when(service.hardStop(any(), anyString(), eq(profileId))).thenReturn(false);
        IdentityController controller = new IdentityController(service, new CredentialVault(), profileRepository());
        authenticate();

        var response = controller.stop(UUID.randomUUID(), Map.of("reason", "stop"), request());

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(String.valueOf(response.getBody())).doesNotContain("\"changed\":true");
    }

    @Test
    void startingASessionRecordsTheCallerAsOwner() {
        IdentityService service = mock(IdentityService.class);
        UUID applicationId = UUID.randomUUID();
        when(service.startSession(any(), anyString(), eq(profileId))).thenReturn(Optional.of(UUID.randomUUID()));

        IdentityController controller = new IdentityController(service, new CredentialVault(), profileRepository());
        authenticate();

        controller.session(new IdentityController.SessionRequest(applicationId, "acme.example"), request());

        verify(service).startSession(eq(applicationId), eq("acme.example"), eq(profileId));
    }

    @Test
    void startingASessionOnAnotherAccountsApplicationReturns404() throws Exception {
        // The application id comes from the request body; the service refuses
        // to attach a session to an application the caller does not own, and
        // the endpoint reports that as an ordinary 404.
        IdentityService service = mock(IdentityService.class);
        when(service.startSession(any(), anyString(), eq(profileId))).thenReturn(Optional.empty());
        IdentityController controller = new IdentityController(service, new CredentialVault(), profileRepository());
        authenticate();

        var response = controller.session(
                new IdentityController.SessionRequest(UUID.randomUUID(), "acme.example"), request());

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private CoverLetterController coverLetterController(CoverLetterRepository repository) {
        return new CoverLetterController(mock(CoverLetterService.class), repository,
                profileRepository(), mock(AuditLogWriter.class));
    }

    private ApplicationAnswerController answerController(ApplicationAnswerRepository repository) {
        return new ApplicationAnswerController(mock(ApplicationAnswerService.class), repository,
                profileRepository(), mock(AuditLogWriter.class));
    }

    private ProfileRepository profileRepository() {
        ProfileRepository repository = mock(ProfileRepository.class);
        when(repository.findByUserId(userId)).thenReturn(Optional.of(profile()));
        return repository;
    }

    private ProfileRecord profile() {
        return new ProfileRecord(profileId, userId, null, null, null,
                Map.of(), Map.of(), null, Map.of(), 1L, "READY");
    }

    private void authenticate() {
        UserRecord user = new UserRecord(userId, "person@example.com", null, "Person", "uid-1", "FIREBASE");
        AppUserDetails principal = new AppUserDetails(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");
        request.setRequestURI("/api/v1/test");
        return request;
    }

    /**
     * The row mapper is irrelevant here — these tests are about the SQL and the
     * bind parameters, both of which are asserted below.
     */
    private static void stubQuery(JdbcTemplate jdbc) {
        when(jdbc.query(anyString(), Mockito.<RowMapper<Object>>any(), any(), any()))
                .thenReturn(List.of());
    }

    /**
     * Runs one {@code findByIdForProfile} and returns the SQL it issued, so the
     * assertion is about the statement that reached the database rather than
     * about the Java call signature.
     */
    private static String capturedSql(JdbcTemplate jdbc, UUID id, UUID profileId) {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), Mockito.<RowMapper<Object>>any(), eq(id), eq(profileId));
        return sql.getValue();
    }
}
