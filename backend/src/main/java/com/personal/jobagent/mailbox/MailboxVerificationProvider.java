package com.personal.jobagent.mailbox;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Dedicated mailbox-verification provider boundary (Phase 8.5) — deliberately
 * separate from the TRACK email classifier: this answers one question only —
 * "is there a fresh, well-correlated verification code or link in the
 * candidate's connected mailbox?" — and never classifies or verifies receipts.
 *
 * <p>Implementations must be owner-scoped and least-privilege, correlate on
 * employer domain + time window, and refuse ambiguous messages (the controller
 * then pauses the workflow for the human).
 */
public interface MailboxVerificationProvider {

    String providerId();

    /** True when this provider can currently read the candidate's mailbox. */
    boolean connected(UUID ownerProfileId);

    /**
     * Finds the freshest verification message for the given employer domain,
     * received after {@code after}, addressed to the connected mailbox.
     * Returns empty when nothing unambiguous is found — never a guess.
     */
    Optional<VerificationHit> findVerification(UUID ownerProfileId, String employerDomain,
                                               String mailboxAddress, Instant after);

    record VerificationHit(String providerId, String messageId, UUID sourceEmailId, String otp, String link,
                           Instant receivedAt) {

        public boolean hasOtp() {
            return otp != null && !otp.isBlank();
        }

        public boolean hasLink() {
            return link != null && !link.isBlank();
        }
    }
}
