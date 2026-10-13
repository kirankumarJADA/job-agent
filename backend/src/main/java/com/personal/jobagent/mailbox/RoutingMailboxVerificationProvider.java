package com.personal.jobagent.mailbox;

import com.personal.jobagent.mailbox.managed.ManagedMailVerificationProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Chooses the mailbox a verification is read from (Phase 8.5).
 *
 * <p>The mailbox is the one the employer account was registered with:
 * <ul>
 *   <li>no mailbox named → the Robin-managed address (the default for every
 *       new registration; no Gmail connection is needed);</li>
 *   <li>the candidate's Robin-managed address → the managed provider;</li>
 *   <li>the candidate's connected Gmail address → Gmail (kept for accounts that
 *       were registered with the personal address);</li>
 *   <li>anything else → nothing: Robin cannot read that mailbox, so the
 *       workflow pauses for the human.</li>
 * </ul>
 * A provider is never asked about a mailbox it does not own, so one mailbox's
 * messages can never answer for another.
 */
@Primary
@Component
public class RoutingMailboxVerificationProvider implements MailboxVerificationProvider {

    private final ManagedMailVerificationProvider managed;
    private final GmailMailboxVerificationProvider gmail;

    public RoutingMailboxVerificationProvider(ManagedMailVerificationProvider managed,
                                              GmailMailboxVerificationProvider gmail) {
        this.managed = managed;
        this.gmail = gmail;
    }

    /** The default provider for new registrations. */
    @Override
    public String providerId() {
        return managed.providerId();
    }

    @Override
    public boolean connected(UUID ownerProfileId) {
        return managed.connected(ownerProfileId) || gmail.connected(ownerProfileId);
    }

    @Override
    public boolean handles(UUID ownerProfileId, String mailboxAddress) {
        return select(ownerProfileId, mailboxAddress).isPresent();
    }

    @Override
    public Optional<VerificationHit> findVerification(UUID ownerProfileId, String employerDomain,
                                                      String mailboxAddress, Instant after) {
        return select(ownerProfileId, mailboxAddress)
                .flatMap(provider -> provider.findVerification(ownerProfileId, employerDomain, mailboxAddress, after));
    }

    @Override
    public void markResolved(UUID ownerProfileId, VerificationHit hit) {
        if (hit == null) return;
        if (ManagedMailVerificationProvider.PROVIDER_ID.equals(hit.providerId())) {
            managed.markResolved(ownerProfileId, hit);
        } else if (gmail.providerId().equals(hit.providerId())) {
            gmail.markResolved(ownerProfileId, hit);
        }
    }

    Optional<MailboxVerificationProvider> select(UUID ownerProfileId, String mailboxAddress) {
        if (mailboxAddress == null || mailboxAddress.isBlank()) return Optional.of(managed);
        if (managed.handles(ownerProfileId, mailboxAddress)) return Optional.of(managed);
        if (gmail.handles(ownerProfileId, mailboxAddress)) return Optional.of(gmail);
        return Optional.empty();
    }
}
