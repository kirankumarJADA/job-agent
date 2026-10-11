package com.personal.jobagent.mailbox;

import com.personal.jobagent.gmail.GmailConnectionStore;
import com.personal.jobagent.gmail.GmailOAuthClient;
import com.personal.jobagent.gmail.GmailSyncService;
import com.personal.jobagent.track.TrackText;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gmail-backed mailbox-verification provider (Phase 8.5, read-only).
 *
 * <p>Uses the Phase 8.4 OAuth connection: triggers a bounded incremental sync
 * so the newest mail is present, then reads the candidate's OWN already-ingested
 * MAILBOX_CONNECTOR messages (owner-scoped, never another user's), filtering on
 * employer domain + delivery to the connected mailbox + time window, and
 * extracts a fresh OTP or verification link. Ambiguous or missing messages
 * return empty — the workflow then pauses for the human instead of guessing.
 */
@Component
public class GmailMailboxVerificationProvider implements MailboxVerificationProvider {

    private static final Pattern OTP = Pattern.compile("\\b(\\d{4,8})\\b");
    private static final Pattern LINK = Pattern.compile("https?://[^\\s\"'<>]+");

    private final GmailConnectionStore connections;
    private final GmailSyncService syncService;
    private final JdbcTemplate db;

    public GmailMailboxVerificationProvider(GmailConnectionStore connections,
                                            GmailSyncService syncService,
                                            JdbcTemplate db) {
        this.connections = connections;
        this.syncService = syncService;
        this.db = db;
    }

    @Override
    public String providerId() {
        return "gmail";
    }

    @Override
    public boolean connected(UUID ownerProfileId) {
        return connections.find(ownerProfileId)
                .map(GmailConnectionStore.Connection::active)
                .orElse(false);
    }

    @Override
    public Optional<VerificationHit> findVerification(UUID ownerProfileId, String employerDomain,
                                                      String mailboxAddress, Instant after) {
        if (!connected(ownerProfileId)) return Optional.empty();
        // Bounded incremental sync first (idempotent) so the newest mail is read.
        try {
            syncService.sync(ownerProfileId);
        } catch (Exception ignored) {
            // A failed sync must not produce a guess: fall through to the query
            // over already-ingested mail and report only what is verifiable.
        }
        String domain = employerDomain == null ? "" : employerDomain.toLowerCase();
        List<Map<String, Object>> rows = db.queryForList("""
                select id, message_id, to_address, subject, body_text, received_at
                from emails
                where profile_id = ?
                  and channel = 'MAILBOX_CONNECTOR'
                  and lower(from_address) like ?
                  and received_at >= ?
                  and body_retained = true
                order by received_at desc
                limit 10
                """, ownerProfileId, "%@" + domain, Instant.now().minusSeconds(60));
        for (Map<String, Object> row : rows) {
            if (!deliveredToConnectedMailbox(row, mailboxAddress)) continue;
            String text = row.get("subject") + " " + row.get("body_text");
            Matcher otp = OTP.matcher(text);
            String otpValue = otp.find() ? otp.group(1) : null;
            Matcher link = LINK.matcher(text);
            String linkValue = link.find() ? link.group() : null;
            if (otpValue == null && linkValue == null) continue;
            Instant receivedAt = row.get("received_at") == null
                    ? null : ((java.sql.Timestamp) row.get("received_at")).toInstant();
            return Optional.of(new VerificationHit("gmail",
                    String.valueOf(row.get("message_id")), otpValue, linkValue, receivedAt));
        }
        return Optional.empty();
    }

    private boolean deliveredToConnectedMailbox(Map<String, Object> row, String mailboxAddress) {
        String to = String.valueOf(row.getOrDefault("to_address", ""));
        String address = mailboxAddress == null ? "" : mailboxAddress.trim();
        if (address.isEmpty()) return false;
        String recipient = TrackText.addressOf(to);
        return recipient != null && recipient.equalsIgnoreCase(address);
    }
}
