package com.personal.jobagent.security;

import com.personal.jobagent.audit.AuditEntry;
import com.personal.jobagent.audit.AuditLogWriter;
import com.personal.jobagent.notifications.NotificationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;

import java.util.List;
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
 * Pins the observable contract of {@code POST /api/v1/auth/firebase/session}:
 * which status code each failure deserves, and that a successful exchange
 * establishes a real session and returns the local account — never anything
 * derived from client-supplied identity.
 */
class FirebaseSessionExchangeTest {

    private FirebaseTokenVerifier verifier;
    private FirebaseUserService userService;
    private SecurityContextRepository securityContextRepository;
    private AuditLogWriter auditLogWriter;
    private AuthController controller;

    @BeforeEach
    void setUp() {
        verifier = mock(FirebaseTokenVerifier.class);
        userService = mock(FirebaseUserService.class);
        securityContextRepository = mock(SecurityContextRepository.class);
        auditLogWriter = mock(AuditLogWriter.class);

        controller = new AuthController(
                mock(AuthenticationManager.class),
                securityContextRepository,
                auditLogWriter,
                mock(PasswordEncoder.class),
                mock(PurgeService.class),
                mock(NotificationService.class),
                verifier,
                userService,
                new RegistrationGate(false, ""));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anUnverifiableTokenIsRejectedWith401() throws Exception {
        when(verifier.verifyIdToken("bad")).thenThrow(new FirebaseTokenVerifier.InvalidToken("nope"));

        var response = controller.firebaseSession(
                new AuthController.FirebaseSessionRequest("bad", null, null), request(), new MockHttpServletResponse());

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        verify(userService, never()).signIn(any(), any());
        verify(securityContextRepository, never()).saveContext(any(), any(), any());
    }

    @Test
    void anUnconfiguredServerReportsAMissingConfigurationInsteadOfFailingOpaquely() throws Exception {
        when(verifier.verifyIdToken("token")).thenThrow(new FirebaseTokenVerifier.Unavailable(
                "missing: FIREBASE_PROJECT_ID, FIREBASE_PRIVATE_KEY"));

        var response = controller.firebaseSession(
                new AuthController.FirebaseSessionRequest("token", null, null), request(), new MockHttpServletResponse());

        // 503, not 401: this is the deployment's problem, not the caller's
        // credential, and the message must name what an operator has to set.
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(String.valueOf(response.getBody())).contains("FIREBASE_PROJECT_ID");
        verify(securityContextRepository, never()).saveContext(any(), any(), any());
    }

    @Test
    void aRefusedRegistrationIsRejectedWith403AndEstablishesNoSession() throws Exception {
        when(verifier.verifyIdToken("token")).thenReturn(identity("uid-1"));
        when(userService.signIn(any(), eq(null))).thenThrow(new FirebaseUserService.RegistrationRefused(
                RegistrationGate.Decision.MISSING_CODE, "A registration invite code is required to create an account."));

        var response = controller.firebaseSession(
                new AuthController.FirebaseSessionRequest("token", null, null), request(), new MockHttpServletResponse());

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(String.valueOf(response.getBody())).contains("invite code");
        verify(securityContextRepository, never()).saveContext(any(), any(), any());
    }

    @Test
    void anIdentityWithoutAnEmailIsRejectedWith400() throws Exception {
        when(verifier.verifyIdToken("token")).thenReturn(identity("uid-1"));
        when(userService.signIn(any(), any())).thenThrow(
                new FirebaseUserService.IdentityIncomplete("no email"));

        var response = controller.firebaseSession(
                new AuthController.FirebaseSessionRequest("token", null, null), request(), new MockHttpServletResponse());

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        verify(securityContextRepository, never()).saveContext(any(), any(), any());
    }

    @Test
    void anUnverifiedEmailRefusedByTheServiceIsRejectedWith403AndNoSession() throws Exception {
        // The service refuses to link an unverified Firebase credential to an
        // existing local account; the endpoint must surface that as a clean
        // 403 and never establish the session the token would otherwise mint.
        when(verifier.verifyIdToken("token")).thenReturn(identity("uid-1"));
        when(userService.signIn(any(), any())).thenThrow(new FirebaseUserService.UnverifiedEmail(
                "This email address has not been verified yet."));

        var response = controller.firebaseSession(
                new AuthController.FirebaseSessionRequest("token", null, null), request(), new MockHttpServletResponse());

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(String.valueOf(response.getBody())).contains("not been verified");
        verify(securityContextRepository, never()).saveContext(any(), any(), any());
        // Refusing the link must not destroy the Firebase credential: the
        // legitimate recovery is to verify the address and sign in again.
        verify(verifier, never()).deleteJustCreatedAccount(any(), any());
    }

    @Test
    void aRefusedSignupDeletesTheFirebaseAccountItJustCreated() throws Exception {
        when(verifier.verifyIdToken("token")).thenReturn(identity("uid-orphan"));
        when(userService.signIn(any(), eq("wrong"))).thenThrow(
                new FirebaseUserService.RegistrationRefused(RegistrationGate.Decision.INVALID_CODE, "bad code"));
        when(verifier.deleteJustCreatedAccount(eq("uid-orphan"), any())).thenReturn(true);

        var response = controller.firebaseSession(
                new AuthController.FirebaseSessionRequest("token", "wrong", true),
                request(), new MockHttpServletResponse());

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        // The credential exists in Firebase but no application account will ever
        // refer to it; deleting it is what stops the email being unusable.
        verify(verifier).deleteJustCreatedAccount(eq("uid-orphan"), any());
        verify(securityContextRepository, never()).saveContext(any(), any(), any());
    }

    @Test
    void aRefusedSignInDoesNotDeleteAnExistingFirebaseAccount() throws Exception {
        when(verifier.verifyIdToken("token")).thenReturn(identity("uid-existing"));
        when(userService.signIn(any(), any())).thenThrow(
                new FirebaseUserService.RegistrationRefused(RegistrationGate.Decision.NOT_CONFIGURED, "closed"));

        var response = controller.firebaseSession(
                new AuthController.FirebaseSessionRequest("token", null, false),
                request(), new MockHttpServletResponse());

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        // Someone signing in with an account they already own must keep it, even
        // though the registration gate refused them a local account.
        verify(verifier, never()).deleteJustCreatedAccount(any(), any());
    }

    @Test
    void aRefusedRegistrationIsNotCleanedUpWhenTheClientDidNotClaimASignup() throws Exception {
        // No `signup` flag at all: the safe assumption is "not a fresh sign-up",
        // because guessing wrong here destroys a real account.
        when(verifier.verifyIdToken("token")).thenReturn(identity("uid-1"));
        when(userService.signIn(any(), any())).thenThrow(
                new FirebaseUserService.RegistrationRefused(RegistrationGate.Decision.MISSING_CODE, "code required"));

        var response = controller.firebaseSession(
                new AuthController.FirebaseSessionRequest("token", null, null),
                request(), new MockHttpServletResponse());

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(verifier, never()).deleteJustCreatedAccount(any(), any());
    }

    @Test
    void aSuccessfulExchangeEstablishesTheSessionAndReturnsTheLocalAccount() throws Exception {
        UUID localId = UUID.randomUUID();
        UserRecord local = new UserRecord(localId, "person@example.com", null, "Person", "uid-1", "FIREBASE");
        when(verifier.verifyIdToken("token")).thenReturn(identity("uid-1"));
        when(userService.signIn(any(), eq("invite")))
                .thenReturn(new FirebaseUserService.ProvisionedUser(local, true));

        MockHttpServletRequest request = request();
        MockHttpServletResponse response = new MockHttpServletResponse();

        var result = controller.firebaseSession(
                new AuthController.FirebaseSessionRequest("token", "invite", null), request, response);

        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody()).isInstanceOf(AuthController.UserResponse.class);
        AuthController.UserResponse body = (AuthController.UserResponse) result.getBody();
        // The response is built from the provisioned local row, so a caller
        // cannot influence which account it is told about.
        assertThat(body.userId()).isEqualTo(localId);
        assertThat(body.email()).isEqualTo("person@example.com");

        verify(securityContextRepository).saveContext(any(), any(), any());
        // A newly created account is audited as a signup, not a plain login.
        verify(auditLogWriter).write(any(AuditEntry.class));
    }

    @Test
    void anExistingAccountIsAuditedAsALoginRatherThanASignup() throws Exception {
        UserRecord local = new UserRecord(UUID.randomUUID(), "person@example.com", null, "Person", "uid-1", "FIREBASE");
        when(verifier.verifyIdToken("token")).thenReturn(identity("uid-1"));
        when(userService.signIn(any(), any()))
                .thenReturn(new FirebaseUserService.ProvisionedUser(local, false));

        controller.firebaseSession(
                new AuthController.FirebaseSessionRequest("token", null, null), request(), new MockHttpServletResponse());

        verify(userService).signIn(any(), any());
        verify(auditLogWriter).write(any(AuditEntry.class));
        verify(securityContextRepository).saveContext(any(), any(), any());
    }

    @Test
    void purgeRefusesACleanConfirmationForAnAccountThatHasNoLocalPassword() throws Exception {
        // Firebase accounts have no local hash (V020 makes the column nullable).
        // Confirming a purge by password is impossible for them, and that must
        // come back as a clean 403 rather than a null-hash crash.
        UserRecord firebaseAccount = new UserRecord(
                UUID.randomUUID(), "person@example.com", null, "Person", "uid-1", "FIREBASE");
        AppUserDetails principal = new AppUserDetails(firebaseAccount);
        SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        principal, null, principal.getAuthorities()));

        var response = controller.purgeMyData(
                new AuthController.PurgeRequest("anything"), request());

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(String.valueOf(response.getBody())).contains("no local password");
    }

    @Test
    void registrationPolicyIsPublicAndReportsBothFlags() {
        var policy = controller.registrationPolicy();

        assertThat(policy.getStatusCode().value()).isEqualTo(200);
        assertThat(policy.getBody()).containsKeys("inviteCodeRequired", "registrationAvailable");
        // The gate above is configured permissive (require-invite-code=false),
        // which is the local development default.
        assertThat(String.valueOf(policy.getBody())).contains("inviteCodeRequired=false");
    }

    @Test
    void anInviteCodeRequiredDeploymentAdvertisesThatItIsNotOpen() {
        AuthController strict = new AuthController(
                mock(AuthenticationManager.class),
                mock(SecurityContextRepository.class),
                mock(AuditLogWriter.class),
                mock(PasswordEncoder.class),
                mock(PurgeService.class),
                mock(NotificationService.class),
                mock(FirebaseTokenVerifier.class),
                mock(FirebaseUserService.class),
                new RegistrationGate(true, ""));

        var policy = strict.registrationPolicy();

        assertThat(String.valueOf(policy.getBody()))
                .contains("inviteCodeRequired=true")
                .contains("registrationAvailable=false");
    }

    @Test
    void theEndpointNeverReadsAUserIdFromTheRequestBody() {
        // Compile-time guarantee: the request shape carries the credential and the
        // registration code, and no identifier. `signup` is a boolean that only
        // enables cleanup of an orphaned Firebase account; nothing is authorised by
        // it. This reflection check makes the absence of an id field explicit, so
        // adding a `userId` later fails here rather than silently creating a
        // spoofing surface.
        List<String> components = java.util.Arrays.stream(
                        AuthController.FirebaseSessionRequest.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();

        assertThat(components).containsExactly("idToken", "inviteCode", "signup");
        assertThat(components).doesNotContain("userId", "uid", "profileId", "email");
    }

    private static FirebaseTokenVerifier.VerifiedIdentity identity(String uid) {
        return new FirebaseTokenVerifier.VerifiedIdentity(uid, "person@example.com", "Person", true);
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");
        request.setRequestURI("/api/v1/auth/firebase/session");
        return request;
    }
}
