package com.personal.jobagent.mailbox;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Dedicated mailbox-verification provider boundary (Phase 8.5) — deliberately
 * separate from the TRACK email classifier: this answers one question only —
 * "is there a fresh, well-correlated verification code or link in the
 * candidate's mailbox?" — and never classifies or verifies receipts.
 *
 * <p>Implementations must be owner-scoped and least-privilege, correlate on
 * employer domain + time window, and refuse ambiguous messages (the controller
 * then pauses the workflow for the human). Two implementations exist: the
 * Robin-managed mailbox (the default, see {@code ManagedMailVerificationProvider})
 * and the candidate's read-only Gmail connection; {@link RoutingMailboxVerificationProvider}
 * chooses between them by the mailbox the employer account was registered with.
 */
public interface MailboxVerificationProvider {

    String providerId();

    /** True when this provider can currently read the candidate's mailbox. */
    boolean connected(UUID ownerProfileId);

    /**
     * Finds the freshest verification message for the given employer domain,
     * received after {@code after}, addressed to the given mailbox.
     * Returns empty when nothing unambiguous is found — never a guess.
     */
    Optional<VerificationHit> findVerification(UUID ownerProfileId, String employerDomain,
                                               String mailboxAddress, Instant after);

    /** True when {@code mailboxAddress} is a mailbox this provider reads for the owner. */
    default boolean handles(UUID ownerProfileId, String mailboxAddress) {
        return false;
    }

    /**
     * Called in the same transaction that records the one-time resolution of
     * {@code hit}, so the provider can retire the source message (for example
     * erase its stored content). The default keeps nothing to retire.
     */
    default void markResolved(UUID ownerProfileId, VerificationHit hit) {
    }

    /**
     * @param sourceEmailId          the TRACK {@code emails} row the hit came from (Gmail), or null
     * @param sourceManagedMessageId the {@code managed_inbound_messages} row it came from, or null
     */
    record VerificationHit(String providerId, String messageId, UUID sourceEmailId, String otp, String link,
                           Instant receivedAt, UUID sourceManagedMessageId) {

        public VerificationHit(String providerId, String messageId, UUID sourceEmailId, String otp, String link,
                               Instant receivedAt) {
            this(providerId, messageId, sourceEmailId, otp, link, receivedAt, null);
        }

        public boolean hasOtp() {
            return otp != null && !otp.isBlank();
        }

        public boolean hasLink() {
            return link != null && !link.isBlank();
        }
    }
}
