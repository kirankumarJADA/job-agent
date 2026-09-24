package com.personal.jobagent.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Decides whether a *brand-new* account may be created.
 *
 * <p>This exists because creating the authentication layer alone would not
 * have been enough: this application's job, application, notification and
 * audit data is global rather than user-scoped, so on a public production URL
 * anyone who could reach the sign-up form could create an account and read
 * that data. Registration is therefore gated, and the gate fails closed.
 *
 * <p>Two properties drive it:
 *
 * <pre>
 *   app.auth.require-invite-code          (APP_REQUIRE_INVITE_CODE)
 *   app.auth.registration-invite-code     (APP_REGISTRATION_INVITE_CODE)
 * </pre>
 *
 * <p>The production profile sets {@code require-invite-code} to {@code true}
 * by default. The outcomes are then:
 *
 * <ul>
 *   <li>{@code require-invite-code=false} — allowed. Local development only;
 *       that is the base profile's default.</li>
 *   <li>{@code require-invite-code=true}, code configured, caller's code
 *       matches — allowed.</li>
 *   <li>{@code require-invite-code=true}, code configured, caller's code
 *       missing or wrong — refused.</li>
 *   <li>{@code require-invite-code=true}, code NOT configured — refused
 *       ({@link Decision#NOT_CONFIGURED}). This is the important case: a
 *       production deploy that forgot to set the invite code does NOT become
 *       open registration, it becomes no registration at all.</li>
 * </ul>
 *
 * <p>An already-linked account never consults this gate — signing in again
 * must keep working regardless of invite-code configuration. The gate is
 * applied only when a genuinely new local user row would be created.
 */
@Component
public class RegistrationGate {

    private static final Logger log = LoggerFactory.getLogger(RegistrationGate.class);

    public enum Decision {
        /** A new account may be created. */
        ALLOWED,
        /** A code was required but the caller supplied none. */
        MISSING_CODE,
        /** The caller supplied a code that did not match. */
        INVALID_CODE,
        /** A code is required but the server has none configured — refuse, never fall open. */
        NOT_CONFIGURED
    }

    private final boolean requireInviteCode;
    private final String inviteCode;

    public RegistrationGate(
            @Value("${app.auth.require-invite-code:false}") boolean requireInviteCode,
            @Value("${app.auth.registration-invite-code:}") String inviteCode) {
        this.requireInviteCode = requireInviteCode;
        this.inviteCode = inviteCode;
        if (requireInviteCode && (inviteCode == null || inviteCode.isBlank())) {
            log.warn("app.auth.require-invite-code=true but app.auth.registration-invite-code is empty: "
                    + "new sign-ups will be refused until APP_REGISTRATION_INVITE_CODE is set. "
                    + "This is intentional (fail closed) and does not affect existing accounts.");
        }
    }

    /**
     * Evaluates a registration attempt.
     *
     * @param suppliedCode the invite code from the sign-up request; may be null
     */
    public Decision evaluate(String suppliedCode) {
        if (!requireInviteCode) {
            return Decision.ALLOWED;
        }
        String expected = inviteCode == null ? "" : inviteCode.trim();
        if (expected.isEmpty()) {
            return Decision.NOT_CONFIGURED;
        }
        String supplied = suppliedCode == null ? "" : suppliedCode.trim();
        if (supplied.isEmpty()) {
            return Decision.MISSING_CODE;
        }
        return constantTimeEquals(expected, supplied) ? Decision.ALLOWED : Decision.INVALID_CODE;
    }

    /** True when the gate can currently admit registration. Reported by the sign-in endpoint. */
    public boolean registrationPossible() {
        return !requireInviteCode || (inviteCode != null && !inviteCode.isBlank());
    }

    /** Whether an invite code is demanded at all. Surfaced so the SPA can label the field. */
    public boolean isInviteCodeRequired() {
        return requireInviteCode;
    }

    /**
     * Comparison that does not short-circuit on the first differing byte.
     * {@link MessageDigest#isEqual} is the JDK's constant-time primitive.
     * Length still differs observably, which for an admin-chosen shared secret
     * is an acceptable, deliberate trade-off — the alternative (hashing both
     * sides first) adds nothing against an attacker who can already reach the
     * endpoint, since the comparison is not the expensive part of the request.
     */
    private static boolean constantTimeEquals(String expected, String supplied) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                supplied.getBytes(StandardCharsets.UTF_8));
    }
}
