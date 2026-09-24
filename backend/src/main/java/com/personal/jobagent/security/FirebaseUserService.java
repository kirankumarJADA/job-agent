package com.personal.jobagent.security;

import com.personal.jobagent.common.UuidV7;
import com.personal.jobagent.profile.ProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.UUID;

/**
 * Turns a <em>verified</em> Firebase identity into a local application user.
 *
 * <p>Three cases, in order:
 *
 * <ol>
 *   <li><b>Already linked</b> — a row with this {@code firebase_uid} exists.
 *       Sign in as that user. No invite code is consulted: the account already
 *       exists, and re-authenticating must never be blocked by registration
 *       configuration.</li>
 *   <li><b>Email matches an existing local account</b> — link the Firebase
 *       identity to it, but only when the token reports the email as
 *       verified (otherwise anyone could claim an existing account by
 *       creating an unverified Firebase credential for its address). This is
 *       the standard Firebase account-linking behaviour and it is what
 *       preserves existing data (profile, preferences, CVs) when the seeded
 *       local account is later used with Firebase. Also not gated: no new
 *       account is being created.</li>
 *   <li><b>Nothing matches</b> — a genuinely new account, so
 *       {@link RegistrationGate} decides.</li>
 * </ol>
 *
 * <p>The caller supplies {@code identity.uid()} only ever from
 * {@link FirebaseTokenVerifier} output. This class never reads an id from a
 * request body.
 */
@Service
public class FirebaseUserService {

    private static final Logger log = LoggerFactory.getLogger(FirebaseUserService.class);

    private final UserRepository userRepository;
    private final ProfileRepository profileRepository;
    private final RegistrationGate registrationGate;

    public FirebaseUserService(UserRepository userRepository,
                               ProfileRepository profileRepository,
                               RegistrationGate registrationGate) {
        this.userRepository = userRepository;
        this.profileRepository = profileRepository;
        this.registrationGate = registrationGate;
    }

    /**
     * Guarantees the account has a profile row.
     *
     * <p>{@code profiles} is the ownership root every user-owned table hangs off,
     * and it is also what the whole API resolves the caller's identity through —
     * {@code ProfileController.currentProfileId()} throws without it. Creating it
     * here means a Firebase account is usable the moment it is created, instead of
     * every subsequent request failing until the user happens to visit the profile
     * setup screen. The row starts INCOMPLETE (V013's setup_status), so onboarding
     * still reports itself honestly as not finished.
     *
     * <p>Idempotent: {@code createProfile} upserts on {@code user_id}, so this is
     * safe on every sign-in and safe for the pre-existing accounts that predate
     * this change.
     */
    private void ensureProfile(UUID userId) {
        if (profileRepository.findByUserId(userId).isPresent()) {
            return;
        }
        profileRepository.createProfile(userId, null, null, null, null, null, null, null);
        log.info("Provisioned a profile for account {}", userId);
    }

    /**
     * @param user    the local account now bound to the verified identity
     * @param created true when this call created a new account
     */
    public record ProvisionedUser(UserRecord user, boolean created) {
    }

    /**
     * Raised when a new account was requested but the registration gate refused
     * it. Carries the gate's decision so the controller can pick a status code
     * and message without re-deriving them.
     */
    public static class RegistrationRefused extends RuntimeException {
        private final transient RegistrationGate.Decision decision;

        public RegistrationRefused(RegistrationGate.Decision decision, String message) {
            super(message);
            this.decision = decision;
        }

        public RegistrationGate.Decision decision() {
            return decision;
        }
    }

    /** Raised when a verified token carries no email and therefore no usable account identity. */
    public static class IdentityIncomplete extends RuntimeException {
        public IdentityIncomplete(String message) {
            super(message);
        }
    }

    /**
     * Raised when an <em>unverified</em> Firebase email matches an existing
     * local account. Anyone can create a Firebase account naming someone
     * else's address without ever proving they control it; linking on the
     * strength of that name alone would hand over the existing account. The
     * exchange is refused until the address is verified (Firebase's
     * verification email link).
     */
    public static class UnverifiedEmail extends RuntimeException {
        public UnverifiedEmail(String message) {
            super(message);
        }
    }

    public ProvisionedUser signIn(FirebaseTokenVerifier.VerifiedIdentity identity, String inviteCode) {
        String email = normaliseEmail(identity.email());
        if (email == null) {
            // Email/password Firebase tokens always carry one. Anything else
            // (e.g. an anonymous or provider-less token) is refused rather
            // than mapped onto an invented account.
            throw new IdentityIncomplete(
                    "The Firebase account has no email address, so it cannot be linked to an application account.");
        }

        // 1. Primary path: the UID from the signed token.
        var byUid = userRepository.findByFirebaseUid(identity.uid());
        if (byUid.isPresent()) {
            userRepository.touchLastLogin(byUid.get().id());
            ensureProfile(byUid.get().id());
            return new ProvisionedUser(byUid.get(), false);
        }

        // 2. Link to an existing local account with the same email — but only
        // when Firebase has verified that the caller controls the address.
        // Email/password tokens are only issued for a verified address once
        // the verification email's link has been followed; Google tokens are
        // verified at the provider. An unverified token whose email happens
        // to name someone else's account must never claim it.
        var byEmail = userRepository.findByEmail(email);
        if (byEmail.isPresent()) {
            if (!identity.emailVerified()) {
                throw new UnverifiedEmail(
                        "This email address has not been verified yet. Follow the verification link Firebase emailed you, then sign in again.");
            }
            UserRecord existing = byEmail.get();
            boolean linked = userRepository.linkFirebaseUid(
                    existing.id(), identity.uid(), displayNameOrDefault(identity, email));
            if (linked) {
                log.info("Linked Firebase identity to existing local account {}", existing.id());
            } else {
                // Someone else linked this row in a concurrent request; re-read
                // and continue with whatever is now authoritative.
                log.info("Firebase link for account {} was already applied", existing.id());
            }
            ensureProfile(existing.id());
            return userRepository.findById(existing.id())
                    .map(reloaded -> new ProvisionedUser(reloaded, false))
                    .orElseThrow(() -> new IdentityIncomplete("The linked account could not be read back."));
        }

        // 3. New account: registration must be permitted.
        RegistrationGate.Decision decision = registrationGate.evaluate(inviteCode);
        switch (decision) {
            case ALLOWED -> {
                // fall through to creation below
            }
            case MISSING_CODE -> throw new RegistrationRefused(decision,
                    "A registration invite code is required to create an account.");
            case INVALID_CODE -> throw new RegistrationRefused(decision,
                    "The registration invite code is not valid.");
            case NOT_CONFIGURED -> throw new RegistrationRefused(decision,
                    "Registration is not available on this deployment.");
        }

        UserRecord created = userRepository.findById(createUser(email, identity))
                .orElseThrow(() -> new IdentityIncomplete("The new account could not be read back."));
        log.info("Created Firebase-backed local account {}", created.id());
        return new ProvisionedUser(created, true);
    }

    private java.util.UUID createUser(String email, FirebaseTokenVerifier.VerifiedIdentity identity) {
        UUID userId = userRepository.insertFirebaseUser(
                UuidV7.generate(), email, displayNameOrDefault(identity, email), identity.uid());
        // The ownership root for everything this account will own, created in the
        // same request that creates the account.
        ensureProfile(userId);
        return userId;
    }

    /**
     * Firebase's display name when set, otherwise the local-part of the email.
     * Only ever cosmetic — nothing authorises on this value.
     */
    private static String displayNameOrDefault(FirebaseTokenVerifier.VerifiedIdentity identity, String email) {
        String name = identity.displayName();
        if (name != null && !name.isBlank()) {
            return name.trim();
        }
        int at = email.indexOf('@');
        String localPart = at > 0 ? email.substring(0, at) : email;
        return localPart.isBlank() ? "Robin User" : localPart;
    }

    /**
     * Lower-cases and trims for a stable comparison. The column is {@code citext}
     * so the database agrees, but normalising here keeps the value we echo back
     * and store consistent.
     */
    private static String normaliseEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
