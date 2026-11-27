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
    static final int PAGE_SIZE = 25;
    static final int MAX_SCAN = 200;

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
                .map(address -> addressMatchesStatic(address, requested.get()))
                .orElse(false);
    }

    @Override
    public Optional<VerificationHit> findVerification(UUID ownerProfileId, String employerDomain,
                                                      String mailboxAddress, Instant after) {
        Optional<ManagedMailboxService.ManagedAddress> usable = mailboxes.usableAddress(ownerProfileId);
        if (usable.isEmpty()) return Optional.empty();
        ManagedMailboxService.ManagedAddress address = usable.get();
        if (mailboxAddress != null && !mailboxAddress.isBlank()
                && !addressMatchesStatic(address, mailboxAddress)) {
            return Optional.empty();
        }
        Optional<String> domain = MailAddresses.employerDomain(employerDomain);
        if (domain.isEmpty()) return Optional.empty();
        Instant now = Instant.now();
        Instant from = after == null ? now.minusSeconds(60) : after;

        List<VerificationHit> hits = new ArrayList<>();
        Timestamp cursorTime = null;
        UUID cursorId = null;
        int scanned = 0;

        while (scanned < MAX_SCAN) {
            List<Map<String, Object>> rows;
            if (cursorTime == null) {
                rows = db.queryForList("""
                        select id, provider_id, envelope_recipient, sender_domain,
                               spf_result, dkim_domain, dmarc_result, auth_trusted,
                               content_encrypted, received_at
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
                          and (reservation_expires_at is null or reservation_expires_at <= now())
                        order by received_at desc, id desc
                        limit ?
                        """, ownerProfileId, address.id(), Timestamp.from(from), Timestamp.from(now), PAGE_SIZE);
            } else {
                rows = db.queryForList("""
                        select id, provider_id, envelope_recipient, sender_domain,
                               spf_result, dkim_domain, dmarc_result, auth_trusted,
                               content_encrypted, received_at
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
                          and (reservation_expires_at is null or reservation_expires_at <= now())
                          and (received_at < ? or (received_at = ? and id < ?))
                        order by received_at desc, id desc
                        limit ?
                        """, ownerProfileId, address.id(), Timestamp.from(from), Timestamp.from(now),
                        cursorTime, cursorTime, cursorId, PAGE_SIZE);
            }
            if (rows.isEmpty()) break;
            scanned += rows.size();

            for (Map<String, Object> row : rows) {
                ExtractionOutcome outcome = evaluateRow(row, address, domain.get());
                if (outcome.ambiguous()) return Optional.empty();
                if (outcome.hit() != null) {
                    hits.add(outcome.hit());
                    if (hits.size() > 1) return Optional.empty();
                }
            }

            if (rows.size() < PAGE_SIZE) break;
            if (scanned >= MAX_SCAN) {
                // Too many unconsumed messages in one verification window to guarantee uniqueness safely.
                return Optional.empty();
            }
            Map<String, Object> last = rows.get(rows.size() - 1);
            cursorTime = (Timestamp) last.get("received_at");
            cursorId = (UUID) last.get("id");
            if (cursorTime == null || cursorId == null) break;
        }
        return hits.stream().findFirst();
    }

    /** Reads an already-leased message during same-application crash recovery without releasing it early. */
    public Optional<VerificationHit> readReservedVerification(UUID ownerProfileId, String employerDomain,
                                                            String mailboxAddress, UUID messageId) {
        if (ownerProfileId == null || messageId == null) return Optional.empty();
        Optional<ManagedMailboxService.ManagedAddress> usable = mailboxes.usableAddress(ownerProfileId);
        if (usable.isEmpty()) return Optional.empty();
        ManagedMailboxService.ManagedAddress address = usable.get();
        if (mailboxAddress != null && !mailboxAddress.isBlank()
                && !addressMatchesStatic(address, mailboxAddress)) {
            return Optional.empty();
        }
        Optional<String> domain = MailAddresses.employerDomain(employerDomain);
        if (domain.isEmpty()) return Optional.empty();

        List<Map<String, Object>> rows = db.queryForList("""
                select id, provider_id, envelope_recipient, sender_domain,
                       spf_result, dkim_domain, dmarc_result, auth_trusted,
                       content_encrypted, received_at
                from managed_inbound_messages
                where id = ?
                  and profile_id = ?
                  and address_id = ?
                  and consumed_at is null
                  and content_encrypted is not null
                  and authentication_failed = false
                  and spam_flagged = false
                  and sender_domain is not null
                """, messageId, ownerProfileId, address.id());
        if (rows.isEmpty()) return Optional.empty();
        ExtractionOutcome outcome = evaluateRow(rows.get(0), address, domain.get());
        return outcome.ambiguous() ? Optional.empty() : Optional.ofNullable(outcome.hit());
    }

    /** Atomically reserves a managed message for a short lease window; returns false if already leased or consumed. */
    public boolean tryReserveManagedMessage(UUID ownerProfileId, UUID messageId, UUID applicationId, UUID sessionId,
                                            Instant expiresAt, String tokenSha256) {
        if (ownerProfileId == null || messageId == null || applicationId == null) return false;
        int updated = db.update("""
                update managed_inbound_messages
                set reserved_for_application_id = ?,
                    reserved_for_session_id = ?,
                    reserved_at = now(),
                    reservation_expires_at = ?,
                    reservation_token_sha256 = ?
                where id = ?
                  and profile_id = ?
                  and consumed_at is null
                  and content_encrypted is not null
                  and (reserved_at is null or reservation_expires_at <= now())
                """, applicationId, sessionId, Timestamp.from(expiresAt), tokenSha256, messageId, ownerProfileId);
        return updated == 1;
    }

    /** Refreshes the lease token/expiry on an active reservation owned by the same application during recovery. */
    public boolean refreshManagedLease(UUID ownerProfileId, UUID messageId, UUID applicationId,
                                       Instant expiresAt, String tokenSha256) {
        if (ownerProfileId == null || messageId == null || applicationId == null) return false;
        int updated = db.update("""
                update managed_inbound_messages
                set reservation_expires_at = ?,
                    reservation_token_sha256 = ?
                where id = ?
                  and profile_id = ?
                  and reserved_for_application_id = ?
                  and consumed_at is null
                  and content_encrypted is not null
                """, Timestamp.from(expiresAt), tokenSha256, messageId, ownerProfileId, applicationId);
        return updated == 1;
    }

    /** Releases an unused lease while preserving the encrypted message content until consumed or retention expires. */
    public void releaseManagedMessage(UUID ownerProfileId, UUID messageId, UUID applicationId) {
        if (ownerProfileId == null || messageId == null || applicationId == null) return;
        db.update("""
                update managed_inbound_messages
                set reserved_for_application_id = null,
                    reserved_for_session_id = null,
                    reserved_at = null,
                    reservation_expires_at = null,
                    reservation_token_sha256 = null
                where id = ?
                  and profile_id = ?
                  and reserved_for_application_id = ?
                  and consumed_at is null
                """, messageId, ownerProfileId, applicationId);
    }

    /** One-time use: permanently marks consumed and erases encrypted content after actual confirmation. */
    @Override
    public void markResolved(UUID ownerProfileId, VerificationHit hit) {
        if (hit == null || hit.sourceManagedMessageId() == null) return;
        db.update("""
                update managed_inbound_messages
                set consumed_at = now(),
                    content_encrypted = null,
                    content_purged_at = coalesce(content_purged_at, now()),
                    reserved_for_application_id = null,
                    reserved_for_session_id = null,
                    reserved_at = null,
                    reservation_expires_at = null,
                    reservation_token_sha256 = null
                where id = ? and profile_id = ? and consumed_at is null
                """, hit.sourceManagedMessageId(), ownerProfileId);
    }

    private record ExtractionOutcome(VerificationHit hit, boolean ambiguous) {
        static ExtractionOutcome empty() { return new ExtractionOutcome(null, false); }
        static ExtractionOutcome ambiguousOutcome() { return new ExtractionOutcome(null, true); }
        static ExtractionOutcome found(VerificationHit hit) { return new ExtractionOutcome(hit, false); }
    }

    private ExtractionOutcome evaluateRow(Map<String, Object> row, ManagedMailboxService.ManagedAddress address,
                                          String targetEmployerDomain) {
        if (!addressMatchesStatic(address, String.valueOf(row.get("envelope_recipient")))) {
            return ExtractionOutcome.empty();
        }
        String senderDomain = row.get("sender_domain") == null ? null : String.valueOf(row.get("sender_domain"));
        if (senderDomain == null || !MailAddresses.sameOrSubdomain(senderDomain, targetEmployerDomain)) {
            return ExtractionOutcome.empty();
        }
        if (ManagedMailSettings.PROVIDER_CLOUDFLARE.equals(row.get("provider_id"))) {
            if (!Boolean.TRUE.equals(row.get("auth_trusted"))) {
                return ExtractionOutcome.empty();
            }
            String spfResult = row.get("spf_result") == null ? null : String.valueOf(row.get("spf_result"));
            String dmarcResult = row.get("dmarc_result") == null ? null : String.valueOf(row.get("dmarc_result"));
            String dkimDomain = row.get("dkim_domain") == null ? null : String.valueOf(row.get("dkim_domain"));
            if ("fail".equalsIgnoreCase(spfResult) || "fail".equalsIgnoreCase(dmarcResult)) {
                return ExtractionOutcome.empty();
            }
            if (dkimDomain == null
                    || (!MailAddresses.sameOrSubdomain(senderDomain, dkimDomain)
                            && !MailAddresses.sameOrSubdomain(dkimDomain, senderDomain))) {
                return ExtractionOutcome.empty();
            }
        }
        ManagedMessageContent.Content stored = content.decrypt(String.valueOf(row.get("content_encrypted")));
        VerificationContentExtractor.Extraction extraction = VerificationContentExtractor.extract(
                stored.subject(), stored.text(), stored.links(), targetEmployerDomain);
        if (extraction.ambiguous()) return ExtractionOutcome.ambiguousOutcome();
        if (extraction.empty()) return ExtractionOutcome.empty();
        UUID id = (UUID) row.get("id");
        return ExtractionOutcome.found(new VerificationHit(PROVIDER_ID, id.toString(), null,
                extraction.code(), extraction.link(), ((Timestamp) row.get("received_at")).toInstant(), id));
    }

    private static boolean addressMatchesStatic(ManagedMailboxService.ManagedAddress address, String rawRecipient) {
        if (address == null || rawRecipient == null || rawRecipient.isBlank()) return false;
        Optional<String> parsed = MailAddresses.bareAddress(rawRecipient);
        if (parsed.isEmpty()) parsed = MailAddresses.singleMailbox(rawRecipient);
        if (parsed.isEmpty()) return false;
        String recipientDomain = MailAddresses.domainOf(parsed.get());
        if (!address.domain().equals(recipientDomain)) return false;
        String recipLocal = MailAddresses.localPartOf(parsed.get());
        if (recipLocal == null) return false;
        if (address.localPart().equals(recipLocal)) return true;
        int plus = recipLocal.indexOf('+');
        return plus > 0 && plus < recipLocal.length() - 1
                && address.localPart().equals(recipLocal.substring(plus + 1));
    }
}
