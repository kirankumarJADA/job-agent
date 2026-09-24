package com.personal.jobagent.security;

/**
 * Verifies a Firebase ID token and returns the identity the *server* derived
 * from it.
 *
 * <p>The whole point of this seam is that the backend never accepts a
 * client-supplied user id. Callers hand in the raw token; the implementation
 * hands back a {@link VerifiedIdentity} whose fields come from a
 * cryptographically verified token —
 * {@code uid}, {@code email} and {@code displayName} are never read from a
 * request body anywhere in this codebase.
 */
public interface FirebaseTokenVerifier {

    /**
     * @param idToken the raw Firebase ID token from the request
     * @return the verified identity carried by that token
     * @throws InvalidToken      the token is malformed, expired, has a bad
     *                           signature/audience/issuer, or is not a valid
     *                           Firebase ID token
     * @throws Unavailable       the server has no usable Firebase credentials,
     *                           so verification cannot be attempted at all
     */
    VerifiedIdentity verifyIdToken(String idToken);

    /**
     * Deletes a Firebase account that this registration attempt just created,
     * so a refused sign-up does not leave a half-made account behind.
     *
     * <p>Only ever reports true for an account whose Firebase creation timestamp
     * falls inside {@code maxAge}. That guard is the whole safety property: a
     * user who signs in with an already-existing Firebase account and is refused
     * by the registration gate must keep that account, whereas one created
     * seconds ago by the sign-up form being processed is definitively the orphan
     * being cleaned up.
     *
     * <p>Never throws. Cleanup is best-effort: the caller is already returning a
     * refusal, and turning a cleanup failure into a different error shape would
     * both mislead the user and make the endpoint's behaviour depend on Firebase
     * availability. Implementations must return false rather than throw when
     * credentials are absent, so a deployment without Firebase Admin configured
     * behaves exactly as it did before.
     *
     * @return true only when the account was created moments ago and has now
     *         been deleted
     */
    boolean deleteJustCreatedAccount(String uid, java.time.Duration maxAge);

    /**
     * Identity extracted from a verified Firebase ID token.
     *
     * @param uid         the Firebase user id — the ONLY trustworthy
     *                    client-side identifier, because it comes from the
     *                    signed token rather than the request payload
     * @param email       the account email; blank for tokens that carry none
     *                    (we reject those rather than inventing an identity)
     * @param displayName the Firebase profile display name, if set
     * @param emailVerified whether Firebase considers the email verified
     */
    record VerifiedIdentity(String uid, String email, String displayName, boolean emailVerified) {
    }

    /**
     * Thrown when a token is present but not acceptable. Mapped to 401.
     * Deliberately carries no detail about *why* beyond a coarse category, so
     * nothing useful leaks to a caller probing the endpoint.
     */
    class InvalidToken extends RuntimeException {
        public InvalidToken(String message) {
            super(message);
        }

        public InvalidToken(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Thrown when the server itself cannot verify tokens because Firebase
     * credentials are absent or unusable. Mapped to 503 — this is a
     * deployment/configuration fault, not a bad credential from the client,
     * and it must never silently degrade into "allow".
     */
    class Unavailable extends RuntimeException {
        public Unavailable(String message) {
            super(message);
        }

        public Unavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
