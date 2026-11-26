package com.personal.jobagent.identity;

import com.personal.jobagent.security.OwnerContext;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link EmployerCredentialController}: the worker-execution consumer for
 * {@link EmployerCredentialService} (Robin x Tsenta continuation, Section 3).
 *
 * <p>Pure unit tests against mocked {@link EmployerCredentialService} and
 * {@link OwnerContext} — no Spring context, matching
 * {@code EmployerCredentialServiceTest}'s style — so the ownership and
 * never-return-plaintext-to-the-candidate rules are verified without a
 * database. The worker-authenticated {@code /resolve} boundary is the one
 * place a decrypted password ever leaves this controller, and only to a
 * request that {@link OwnerContext#isWorkerRequest()} confirms is the worker.
 */
class EmployerCredentialControllerTest {

    private final EmployerCredentialService credentials = mock(EmployerCredentialService.class);
    private final OwnerContext ownerContext = mock(OwnerContext.class);
    private final EmployerCredentialController controller =
            new EmployerCredentialController(credentials, ownerContext);
    private final MockHttpServletRequest request = new MockHttpServletRequest();

    // ── candidate-facing: list ──────────────────────────────────────

    @Test
    void listWithNoProfileIs404() {
        when(ownerContext.profileIdOrNull()).thenReturn(null);

        ResponseEntity<?> response = controller.list(request);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void listNeverIncludesThePassword() {
        UUID profileId = UUID.randomUUID();
        when(ownerContext.profileIdOrNull()).thenReturn(profileId);
        when(credentials.list(profileId)).thenReturn(List.of(
                new EmployerCredentialService.CredentialSummary(
                        UUID.randomUUID(), "greenhouse.io", "candidate@example.com", "ACTIVE",
                        Timestamp.from(java.time.Instant.now()))));

        ResponseEntity<?> response = controller.list(request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        String body = response.getBody().toString();
        assertThat(body).contains("greenhouse.io", "candidate@example.com", "ACTIVE");
        assertThat(body.toLowerCase()).doesNotContain("password");
    }

    // ── candidate-facing: save ──────────────────────────────────────

    @Test
    void saveWithNoProfileIs404() {
        when(ownerContext.profileIdOrNull()).thenReturn(null);

        ResponseEntity<?> response = controller.save(
                new EmployerCredentialController.SaveRequest("greenhouse.io", "u", "p"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void saveRejectsAnInvalidEmployerDomain() {
        when(ownerContext.profileIdOrNull()).thenReturn(UUID.randomUUID());

        ResponseEntity<?> response = controller.save(
                new EmployerCredentialController.SaveRequest("not a domain!!", "u", "p"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verify(credentials, never()).save(any(), any(), any(), any());
    }

    @Test
    void saveRejectsAMissingUsernameOrPassword() {
        when(ownerContext.profileIdOrNull()).thenReturn(UUID.randomUUID());

        assertThat(controller.save(new EmployerCredentialController.SaveRequest("greenhouse.io", "", "p"), request)
                .getStatusCode().value()).isEqualTo(400);
        assertThat(controller.save(new EmployerCredentialController.SaveRequest("greenhouse.io", "u", ""), request)
                .getStatusCode().value()).isEqualTo(400);
        verify(credentials, never()).save(any(), any(), any(), any());
    }

    @Test
    void saveNormalizesTheDomainAndNeverEchoesThePasswordBack() {
        UUID profileId = UUID.randomUUID();
        when(ownerContext.profileIdOrNull()).thenReturn(profileId);

        ResponseEntity<?> response = controller.save(
                new EmployerCredentialController.SaveRequest("WWW.Greenhouse.IO", " candidate@example.com ", "s3cret"),
                request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(credentials).save(profileId, "greenhouse.io", "candidate@example.com", "s3cret");
        assertThat(response.getBody().toString()).doesNotContain("s3cret");
    }

    // ── candidate-facing: disable ────────────────────────────────────

    @Test
    void disableRejectsAnInvalidDomainWithoutCallingTheService() {
        when(ownerContext.profileIdOrNull()).thenReturn(UUID.randomUUID());

        ResponseEntity<?> response = controller.disable("not a domain!!", request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verify(credentials, never()).disable(any(), any());
    }

    @Test
    void disableScopesToTheCallersOwnProfile() {
        UUID profileId = UUID.randomUUID();
        when(ownerContext.profileIdOrNull()).thenReturn(profileId);

        ResponseEntity<?> response = controller.disable("Greenhouse.io", request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(credentials).disable(profileId, "greenhouse.io");
    }

    @Test
    void candidateEndpointsRejectWorkerRequestsWith403() {
        when(ownerContext.isWorkerRequest()).thenReturn(true);

        assertThat(controller.list(request).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.save(new EmployerCredentialController.SaveRequest("greenhouse.io", "u", "p"), request)
                .getStatusCode().value()).isEqualTo(403);
        assertThat(controller.disable("greenhouse.io", request).getStatusCode().value()).isEqualTo(403);
        verify(credentials, never()).list(any());
        verify(credentials, never()).save(any(), any(), any(), any());
        verify(credentials, never()).disable(any(), any());
    }

    // ── worker-facing: resolve ───────────────────────────────────────

    @Test
    void resolveRefusesAnyCallerThatIsNotTheWorker() {
        when(ownerContext.isWorkerRequest()).thenReturn(false);

        ResponseEntity<?> response = controller.resolve(
                new EmployerCredentialController.ResolveRequest(UUID.randomUUID(), "greenhouse.io"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(credentials, never()).find(any(), any());
    }

    @Test
    void resolveRequiresApplicationIdAndEmployerDomain() {
        when(ownerContext.isWorkerRequest()).thenReturn(true);

        assertThat(controller.resolve(
                new EmployerCredentialController.ResolveRequest(null, "greenhouse.io"), request)
                .getStatusCode().value()).isEqualTo(400);
        assertThat(controller.resolve(
                new EmployerCredentialController.ResolveRequest(UUID.randomUUID(), ""), request)
                .getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void resolveNeverTakesOwnershipFromTheRequestBody() {
        // Ownership is derived from the application row, never trusted from
        // the request — the exact IDOR class IdentityController's own javadoc
        // documents having been fixed for the sibling endpoint.
        UUID applicationId = UUID.randomUUID();
        when(ownerContext.isWorkerRequest()).thenReturn(true);
        when(ownerContext.ownerOfApplication(applicationId)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.resolve(
                new EmployerCredentialController.ResolveRequest(applicationId, "greenhouse.io"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        verify(credentials, never()).find(any(), any());
    }

    @Test
    void resolveReturns404WhenNoActiveCredentialIsStoredForThatDomain() {
        // Also covers a DISABLED credential: find() only ever returns ACTIVE rows.
        UUID applicationId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        when(ownerContext.isWorkerRequest()).thenReturn(true);
        when(ownerContext.ownerOfApplication(applicationId)).thenReturn(Optional.of(profileId));
        bind(profileId, applicationId, true, "greenhouse.io");
        when(credentials.find(profileId, "greenhouse.io")).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.resolve(
                new EmployerCredentialController.ResolveRequest(applicationId, "Greenhouse.io"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void resolveReturnsTheDecryptedCredentialOnlyToTheOwningApplicationsWorker() {
        UUID applicationId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        when(ownerContext.isWorkerRequest()).thenReturn(true);
        when(ownerContext.ownerOfApplication(applicationId)).thenReturn(Optional.of(profileId));
        bind(profileId, applicationId, true, "greenhouse.io");
        when(credentials.find(profileId, "greenhouse.io")).thenReturn(Optional.of(
                new EmployerCredentialService.StoredCredential(UUID.randomUUID(), "greenhouse.io",
                        "candidate@example.com", "the-real-password")));

        ResponseEntity<?> response = controller.resolve(
                new EmployerCredentialController.ResolveRequest(applicationId, "Greenhouse.io"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(Map.of("username", "candidate@example.com",
                "password", "the-real-password"));
        verify(credentials).find(profileId, "greenhouse.io");
    }

    // ── Stage 3F (SEC-7): employer-domain binding ─────────────────────

    private void bind(UUID profileId, UUID applicationId, boolean running, String... domains) {
        when(credentials.bindingFor(profileId, applicationId))
                .thenReturn(new EmployerCredentialService.ApplicationBinding(running, java.util.Set.of(domains)));
    }

    private ResponseEntity<?> resolveAsWorker(UUID applicationId, UUID profileId, String domain) {
        when(ownerContext.isWorkerRequest()).thenReturn(true);
        when(ownerContext.ownerOfApplication(applicationId)).thenReturn(Optional.of(profileId));
        return controller.resolve(new EmployerCredentialController.ResolveRequest(applicationId, domain), request);
    }

    @Test
    void resolveRefusesADomainThatIsNotThisApplicationsEmployer() {
        UUID applicationId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        bind(profileId, applicationId, true, "careers.acme.com");

        ResponseEntity<?> response = resolveAsWorker(applicationId, profileId, "greenhouse.io");

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(credentials, never()).find(any(), any());
    }

    @Test
    void resolveRefusesLookalikeAndSuffixConfusionDomains() {
        UUID applicationId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        bind(profileId, applicationId, true, "greenhouse.io");

        for (String lookalike : List.of("greenhouse.io.evil.com", "evilgreenhouse.io", "greenhouse.io-login.com",
                "sub.greenhouse.io", "xn--greenhuse-q9a.io", "io")) {
            ResponseEntity<?> response = resolveAsWorker(applicationId, profileId, lookalike);
            assertThat(response.getStatusCode().value()).as(lookalike).isIn(400, 403);
        }
        verify(credentials, never()).find(any(), any());
    }

    @Test
    void resolveNormalisesCaseTrailingDotAndWwwBeforeMatching() {
        UUID applicationId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        bind(profileId, applicationId, true, "careers.acme.com");
        when(credentials.find(profileId, "careers.acme.com")).thenReturn(Optional.of(
                new EmployerCredentialService.StoredCredential(UUID.randomUUID(), "careers.acme.com", "u", "p")));

        assertThat(resolveAsWorker(applicationId, profileId, "WWW.Careers.ACME.com.").getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void resolveRefusesWhenNoAutomationRunIsActiveForTheApplication() {
        UUID applicationId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        bind(profileId, applicationId, false, "greenhouse.io");

        ResponseEntity<?> response = resolveAsWorker(applicationId, profileId, "greenhouse.io");

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        verify(credentials, never()).find(any(), any());
    }

    @Test
    void resolveRefusesAnApplicationWhoseJobHasNoUsableEmployerHost() {
        // Missing/invalid application -> job relationship: no permitted domains at all.
        UUID applicationId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        bind(profileId, applicationId, true);

        assertThat(resolveAsWorker(applicationId, profileId, "greenhouse.io").getStatusCode().value()).isEqualTo(403);
        verify(credentials, never()).find(any(), any());
    }

    @Test
    void resolveReportsAnUndecryptableCredentialWithoutLeakingAnything() {
        UUID applicationId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        bind(profileId, applicationId, true, "greenhouse.io");
        when(credentials.find(profileId, "greenhouse.io"))
                .thenThrow(new IllegalStateException("Credential decryption failed — was APP_SECRETS_KEY rotated?"));

        ResponseEntity<?> response = resolveAsWorker(applicationId, profileId, "greenhouse.io");

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(String.valueOf(response.getBody())).doesNotContain("APP_SECRETS_KEY").doesNotContain("password");
    }

    @Test
    void workerTokensCannotListSaveOrDisableCandidateCredentials() {
        when(ownerContext.isWorkerRequest()).thenReturn(true);

        assertThat(controller.list(request).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.save(new EmployerCredentialController.SaveRequest("greenhouse.io", "u", "p"), request)
                .getStatusCode().value()).isEqualTo(403);
        assertThat(controller.disable("greenhouse.io", request).getStatusCode().value()).isEqualTo(403);
        verify(credentials, never()).save(any(), any(), any(), any());
        verify(credentials, never()).list(any());
    }

    @Test
    void hostDomainAcceptsOnlyHttpsNamedHostsWithoutUserinfo() {
        assertThat(EmployerCredentialService.hostDomain("https://WWW.Careers.Acme.com/jobs/1?x=1")).contains("careers.acme.com");
        assertThat(EmployerCredentialService.hostDomain("http://careers.acme.com/jobs")).isEmpty();
        assertThat(EmployerCredentialService.hostDomain("https://user:pw@careers.acme.com/jobs")).isEmpty();
        assertThat(EmployerCredentialService.hostDomain("https://127.0.0.1/jobs")).isEmpty();
        assertThat(EmployerCredentialService.hostDomain("https://[::1]/jobs")).isEmpty();
        assertThat(EmployerCredentialService.hostDomain("not a url")).isEmpty();
        assertThat(EmployerCredentialService.hostDomain(null)).isEmpty();
    }
}
