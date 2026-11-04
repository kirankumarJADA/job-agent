package com.personal.jobagent.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Authenticates a request from a Firebase ID token sent as
 * {@code Authorization: Bearer <idToken>}.
 *
 * <p><b>Why a filter and not just the exchange endpoint.</b> The SPA calls
 * {@code POST /api/v1/auth/firebase/session} once after a successful Firebase
 * sign-in, which establishes the normal server session (and with it the CSRF
 * pair the rest of the API expects). Every subsequent request therefore works
 * exactly as it did before. The filter exists so that a request carrying a
 * valid bearer token is also authenticated <em>directly</em>, without depending
 * on the session having survived — and so that an expired application session
 * does not force a full re-authentication round trip while a valid Firebase
 * token is right there in the header.
 *
 * <p><b>It only ever authenticates accounts that already exist.</b> A verified
 * UID with no linked local user leaves the request unauthenticated, so it is
 * rejected by the normal entry point with 401. New accounts can only be created
 * by the exchange endpoint, which applies {@link RegistrationGate}. Doing
 * "provision on first sight" here would have been a hole wide enough to bypass
 * the invite code entirely, since <em>any</em> endpoint would then mint an
 * account.
 *
 * <p>Failure is deliberately silent: an invalid or unverifiable token does not
 * abort the request, it simply does not authenticate it. That keeps this
 * filter's behaviour identical to a missing header — the request continues and
 * either succeeds as a permitted endpoint or is refused by the entry point.
 * Aborting here would turn a bad token into a different, distinguishable error
 * shape.
 */
public class FirebaseAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(FirebaseAuthenticationFilter.class);

    private static final String HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final FirebaseTokenVerifier verifier;
    private final UserRepository userRepository;
    private final SecurityContextRepository securityContextRepository;
    private final OperatorAccessPolicy operatorPolicy;

    public FirebaseAuthenticationFilter(FirebaseTokenVerifier verifier,
                                        UserRepository userRepository,
                                        SecurityContextRepository securityContextRepository,
                                        OperatorAccessPolicy operatorPolicy) {
        this.verifier = verifier;
        this.userRepository = userRepository;
        this.securityContextRepository = securityContextRepository;
        this.operatorPolicy = operatorPolicy;
    }

    public FirebaseAuthenticationFilter(FirebaseTokenVerifier verifier,
                                        UserRepository userRepository,
                                        SecurityContextRepository securityContextRepository) {
        this(verifier, userRepository, securityContextRepository, new OperatorAccessPolicy(""));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (Boolean.TRUE.equals(request.getAttribute(WorkerEventTokenFilter.WORKER_AUTH_ATTRIBUTE))) {
            filterChain.doFilter(request, response);
            return;
        }

        String idToken = bearerToken(request);
        if (idToken == null) {
            // Pure cookie session or unauthenticated request: rely on standard session authentication.
            filterChain.doFilter(request, response);
            return;
        }

        try {
            FirebaseTokenVerifier.VerifiedIdentity identity = verifier.verifyIdToken(idToken);
            Optional<UserRecord> user = userRepository.findByFirebaseUid(identity.uid());
            if (user.isEmpty()) {
                // Verified, but not yet provisioned. Only the exchange endpoint
                // (which checks the invite code) may create the account.
                log.debug("Verified Firebase identity has no linked local account yet; leaving request unauthenticated");
                filterChain.doFilter(request, response);
                return;
            }

            // Cross-account isolation check: if an existing session belongs to a different user,
            // invalidate that session so the bearer identity is not conflated with the cookie.
            if (isAlreadyAuthenticated()) {
                Authentication current = SecurityContextHolder.getContext().getAuthentication();
                if (current != null && current.getPrincipal() instanceof AppUserDetails principal) {
                    if (!user.get().id().equals(principal.getUserId())) {
                        log.info("Cross-account request: bearer user {} differs from session user {}; invalidating old session",
                                user.get().id(), principal.getUserId());
                        jakarta.servlet.http.HttpSession existingSession = request.getSession(false);
                        if (existingSession != null) {
                            existingSession.invalidate();
                        }
                    }
                }
            }

            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authenticationFor(user.get()));
            SecurityContextHolder.setContext(context);
            // Persist into the session so the CSRF pair and GET requests behave
            // exactly as they do after a normal session login.
            securityContextRepository.saveContext(context, request, response);
        } catch (FirebaseTokenVerifier.InvalidToken e) {
            log.debug("Rejected Firebase ID token: reason={}, message={}", e.reason(), e.getMessage());
            if (e.isRevokedOrDisabled()) {
                // An explicitly revoked token or disabled account must never ride on an existing session.
                SecurityContextHolder.clearContext();
                jakarta.servlet.http.HttpSession session = request.getSession(false);
                if (session != null) {
                    session.invalidate();
                }
            } else if (!isAlreadyAuthenticated()) {
                SecurityContextHolder.clearContext();
            }
        } catch (FirebaseTokenVerifier.Unavailable e) {
            // Configuration or network fault. Not authenticated via bearer.
            log.warn("Firebase token verification unavailable: {}", e.getMessage());
            if (!isAlreadyAuthenticated()) {
                SecurityContextHolder.clearContext();
            }
        } catch (RuntimeException e) {
            log.warn("Unexpected failure verifying Firebase ID token", e);
            if (!isAlreadyAuthenticated()) {
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * The principal is an {@link AppUserDetails} in every authentication path,
     * because the existing controllers cast to it. Keeping that shape is what
     * lets Firebase-authenticated requests reuse the entire existing API
     * surface unchanged.
     */
    private Authentication authenticationFor(UserRecord user) {
        AppUserDetails principal = new AppUserDetails(user, operatorPolicy.isOperator(user));
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    private static boolean isAlreadyAuthenticated() {
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        return current != null && current.isAuthenticated()
                && !(current instanceof org.springframework.security.authentication.AnonymousAuthenticationToken);
    }

    private static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (header == null || header.length() <= BEARER_PREFIX.length()
                || !header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
