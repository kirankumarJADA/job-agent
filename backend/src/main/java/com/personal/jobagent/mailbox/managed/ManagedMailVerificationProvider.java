package com.personal.jobagent.mailbox.managed;

import com.personal.jobagent.mailbox.MailboxVerificationProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Verification codes and links from the candidate's Robin-managed address
 * (Phase 8.5). Needs no Gmail connection and no candidate OAuth.
 *
 * <p>A message is evidence only when every one of these holds; otherwise the
 * answer is empty and the worker pauses for the human:
 * <ul>
 *   <li><b>recipient</b> — delivered (by SMTP envelope) to this owner's own,
 *       active alias, and to the mailbox the account was registered with;</li>
 *   <li><b>employer</b> — the From mailbox is on the employer's domain (or a
 *       subdomain), and the provider reported no failed sender check or spam
 *       verdict;</li>
 *   <li><b>age</b> — received inside the window the caller gives (never before
 *       the account session started, never older than the verification window);</li>
 *   <li><b>unused</b> — not already resolved for any application;</li>
 *   <li><b>content</b> — exactly one code (or, with no code, exactly one safe
 *       employer verification link), and exactly one such message overall.</li>
 * </ul>
 */
@Component
public class ManagedMailVerificationProvider implements MailboxVerificationProvider {

    public static final String PROVIDER_ID = "managed";
    private static final int MAX_CANDIDATES = 25;

    private final JdbcTemplate db;
    private final ManagedMailboxService mailboxes;
    private final ManagedMessageContent content;

    public ManagedMailVerificationProvider(JdbcTemplate db, ManagedMailboxService mailboxes,
                                           ManagedMessageContent content) {
        this.db = db;
        this.mailboxes = mailboxes;
        this.content = content;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public boolean connected(UUID ownerProfileId) {
        return mailboxes.usableAddress(ownerProfileId).isPresent();
    }

    @Override
    public boolean handles(UUID ownerProfileId, String mailboxAddress) {
        Optional<String> requested = MailAddresses.bareAddress(mailboxAddress);
        return requested.isPresent() && mailboxes.findAddress(ownerProfileId)
                .map(address -> address.address().equals(requested.get()))
                .orElse(false);
    }

    @Override
    public Optional<VerificationHit> findVerification(UUID ownerProfileId, String employerDomain,
                                                      String mailboxAddress, Instant after) {
        Optional<ManagedMailboxService.ManagedAddress> usable = mailboxes.usableAddress(ownerProfileId);
        if (usable.isEmpty()) return Optional.empty();
        ManagedMailboxService.ManagedAddress address = usable.get();
        if (mailboxAddress != null && !mailboxAddress.isBlank()
                && !address.address().equals(mailboxAddress.trim().toLowerCase(Locale.ROOT))) {
            return Optional.empty();
        }
        Optional<String> domain = MailAddresses.employerDomain(employerDomain);
        if (domain.isEmpty()) return Optional.empty();
        Instant now = Instant.now();
        Instant from = after == null ? now.minusSeconds(60) : after;

        List<Map<String, Object>> rows = db.queryForList("""
                select id, envelope_recipient, sender_domain, content_encrypted, received_at
                from managed_inbound_messages
                where profile_id = ?
                  and address_id = ?
                  and consumed_at is null
                  and content_encrypted is not null
                  and authentication_failed = false
                  and spam_flagged = false
                  and sender_domain is not null
                  and received_at >= ?
                  and received_at <= ?
                order by received_at desc
                limit ?
                """, ownerProfileId, address.id(), Timestamp.from(from), Timestamp.from(now), MAX_CANDIDATES);

        List<VerificationHit> hits = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (!address.address().equals(row.get("envelope_recipient"))) continue;
            if (!MailAddresses.sameOrSubdomain(String.valueOf(row.get("sender_domain")), domain.get())) continue;
            ManagedMessageContent.Content stored = content.decrypt(String.valueOf(row.get("content_encrypted")));
            VerificationContentExtractor.Extraction extraction = VerificationContentExtractor.extract(
                    stored.subject(), stored.text(), stored.links(), domain.get());
            if (extraction.ambiguous()) return Optional.empty();
            if (extraction.empty()) continue;
            UUID id = (UUID) row.get("id");
            hits.add(new VerificationHit(PROVIDER_ID, id.toString(), null, extraction.code(), extraction.link(),
                    ((Timestamp) row.get("received_at")).toInstant(), id));
            if (hits.size() > 1) return Optional.empty();
        }
        return hits.stream().findFirst();
    }

    /** One-time use: the message can never be resolved again and its content is erased now. */
    @Override
    public void markResolved(UUID ownerProfileId, VerificationHit hit) {
        if (hit == null || hit.sourceManagedMessageId() == null) return;
        db.update("""
                update managed_inbound_messages
                set consumed_at = now(), content_encrypted = null, content_purged_at = coalesce(content_purged_at, now())
                where id = ? and profile_id = ? and consumed_at is null
                """, hit.sourceManagedMessageId(), ownerProfileId);
    }
}
