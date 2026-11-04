package com.personal.jobagent.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class FirebaseAuthenticationFilterTest {

    private FirebaseTokenVerifier verifier;
    private UserRepository userRepository;
    private SecurityContextRepository contextRepository;
    private FirebaseAuthenticationFilter filter;

    private static final UUID USER_A_ID = UUID.fromString("018e0000-0000-7000-8000-000000000001");
    private static final UUID USER_B_ID = UUID.fromString("018e0000-0000-7000-8000-000000000002");
    private static final String UID_A = "firebase-uid-a";
    private static final String UID_B = "firebase-uid-b";

    private final UserRecord userA = new UserRecord(USER_A_ID, "user.a@example.com", null, "User A", UID_A, "FIREBASE", "CANDIDATE");
    private final UserRecord userB = new UserRecord(USER_B_ID, "user.b@example.com", null, "User B", UID_B, "FIREBASE", "CANDIDATE");

    @BeforeEach
    void setUp() {
        verifier = mock(FirebaseTokenVerifier.class);
        userRepository = mock(UserRepository.class);
        contextRepository = mock(SecurityContextRepository.class);
        filter = new FirebaseAuthenticationFilter(verifier, userRepository, contextRepository, new OperatorAccessPolicy(""));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // 1. Valid non-revoked Bearer token
    @Test
    void validNonRevokedBearerTokenAuthenticatesAndSavesContext() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        when(verifier.verifyIdToken("valid-token"))
                .thenReturn(new FirebaseTokenVerifier.VerifiedIdentity(UID_A, "user.a@example.com", "User A", true));
        when(userRepository.findByFirebaseUid(UID_A)).thenReturn(Optional.of(userA));

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(contextRepository).saveContext(any(), eq(request), eq(response));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        AppUserDetails principal = (AppUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertThat(principal.getUserId()).isEqualTo(USER_A_ID);
    }

    // 2. Revoked ID token with existing session
    @Test
    void revokedBearerTokenClearsContextAndInvalidatesSession() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer revoked-token");
        request.getSession(true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        SecurityContext existingContext = SecurityContextHolder.createEmptyContext();
        existingContext.setAuthentication(new UsernamePasswordAuthenticationToken(new AppUserDetails(userA, false), null, java.util.List.of()));
        SecurityContextHolder.setContext(existingContext);

        when(verifier.verifyIdToken("revoked-token"))
                .thenThrow(new FirebaseTokenVerifier.InvalidToken("Token revoked", FirebaseTokenVerifier.InvalidationReason.REVOKED, null));

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(request.getSession(false)).isNull();
        verify(contextRepository, never()).saveContext(any(), any(), any());
    }

    // 3. Disabled Firebase user
    @Test
    void disabledUserBearerTokenClearsContextAndInvalidatesSession() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer disabled-token");
        request.getSession(true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        SecurityContext existingContext = SecurityContextHolder.createEmptyContext();
        existingContext.setAuthentication(new UsernamePasswordAuthenticationToken(new AppUserDetails(userA, false), null, java.util.List.of()));
        SecurityContextHolder.setContext(existingContext);

        when(verifier.verifyIdToken("disabled-token"))
                .thenThrow(new FirebaseTokenVerifier.InvalidToken("User disabled", FirebaseTokenVerifier.InvalidationReason.USER_DISABLED, null));

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(request.getSession(false)).isNull();
    }

    // 3b. Deleted Firebase account with an existing session
    @Test
    void deletedAccountBearerTokenClearsContextAndInvalidatesSession() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer deleted-account-token");
        request.getSession(true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        SecurityContext existingContext = SecurityContextHolder.createEmptyContext();
        existingContext.setAuthentication(new UsernamePasswordAuthenticationToken(new AppUserDetails(userA, false), null, java.util.List.of()));
        SecurityContextHolder.setContext(existingContext);

        when(verifier.verifyIdToken("deleted-account-token"))
                .thenThrow(new FirebaseTokenVerifier.InvalidToken("Account deleted", FirebaseTokenVerifier.InvalidationReason.ACCOUNT_DELETED, null));

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(request.getSession(false)).isNull();
        verify(contextRepository, never()).saveContext(any(), any(), any());
    }

    // 4. Expired ID token without session
    @Test
    void expiredBearerTokenWithoutSessionLeavesUnauthenticated() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer expired-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        when(verifier.verifyIdToken("expired-token"))
                .thenThrow(new FirebaseTokenVerifier.InvalidToken("Token expired", FirebaseTokenVerifier.InvalidationReason.EXPIRED, null));

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(contextRepository, never()).saveContext(any(), any(), any());
    }

    // 5. Expired ID token with existing valid session preserves session without logout
    @Test
    void expiredBearerTokenWithExistingValidSessionPreservesSession() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer expired-token");
        request.getSession(true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        SecurityContext existingContext = SecurityContextHolder.createEmptyContext();
        existingContext.setAuthentication(new UsernamePasswordAuthenticationToken(new AppUserDetails(userA, false), null, java.util.List.of()));
        SecurityContextHolder.setContext(existingContext);

        when(verifier.verifyIdToken("expired-token"))
                .thenThrow(new FirebaseTokenVerifier.InvalidToken("Token expired", FirebaseTokenVerifier.InvalidationReason.EXPIRED, null));

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(request.getSession(false)).isNotNull();
    }

    // 6. Firebase service failure leaves unauthenticated when no session
    @Test
    void serviceUnavailableBearerTokenLeavesUnauthenticatedWhenNoSession() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        when(verifier.verifyIdToken("token"))
                .thenThrow(new FirebaseTokenVerifier.Unavailable("Firebase unreachable"));

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    // 7. Pure session cookie request without Bearer header proceeds without calling verifier
    @Test
    void pureSessionCookieRequestDoesNotCallVerifier() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        SecurityContext existingContext = SecurityContextHolder.createEmptyContext();
        existingContext.setAuthentication(new UsernamePasswordAuthenticationToken(new AppUserDetails(userA, false), null, java.util.List.of()));
        SecurityContextHolder.setContext(existingContext);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(verifier);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
    }

    // 8. Isolation between two different Firebase UIDs (cross-account)
    @Test
    void crossAccountBearerTokenForUserBInvalidatesUserASessionAndAuthenticatesUserB() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer token-b");
        request.getSession(true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        // Pre-existing session was authenticated as User A
        SecurityContext existingContext = SecurityContextHolder.createEmptyContext();
        existingContext.setAuthentication(new UsernamePasswordAuthenticationToken(new AppUserDetails(userA, false), null, java.util.List.of()));
        SecurityContextHolder.setContext(existingContext);

        // Bearer token resolves to User B
        when(verifier.verifyIdToken("token-b"))
                .thenReturn(new FirebaseTokenVerifier.VerifiedIdentity(UID_B, "user.b@example.com", "User B", true));
        when(userRepository.findByFirebaseUid(UID_B)).thenReturn(Optional.of(userB));

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(contextRepository).saveContext(any(), eq(request), eq(response));
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        AppUserDetails principal = (AppUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertThat(principal.getUserId()).isEqualTo(USER_B_ID);
    }
}
