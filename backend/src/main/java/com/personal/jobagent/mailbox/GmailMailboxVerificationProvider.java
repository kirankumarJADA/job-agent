package com.personal.jobagent.mailbox;

import com.personal.jobagent.gmail.GmailConnectionStore;
import com.personal.jobagent.gmail.GmailOAuthClient;
import com.personal.jobagent.gmail.GmailSyncService;
import com.personal.jobagent.track.TrackText;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
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

    private static final Pattern OTP = Pattern.compile("(?i)(?:verification[ -]?code|one[- ]time (?:passcode|code)|security code|otp|code is|use)\\D{0,40}(\\d{4,8})");
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
        GmailConnectionStore.Connection connection = connections.find(ownerProfileId)
                .filter(GmailConnectionStore.Connection::active).orElse(null);
        if (connection == null) return Optional.empty();
        String connectedMailbox = connection.googleEmail();
        if (mailboxAddress != null && !mailboxAddress.isBlank()
                && !connectedMailbox.equalsIgnoreCase(mailboxAddress.trim())) return Optional.empty();
        try { syncService.sync(ownerProfileId); } catch (Exception ignored) {
            // Continue only with existing owner-scoped evidence if sync failed.
        }
        String domain = employerDomain == null ? "" : employerDomain.trim().toLowerCase(java.util.Locale.ROOT);
        if (!domain.matches("(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}")) return Optional.empty();
        Instant effectiveAfter = after == null ? Instant.now().minusSeconds(60) : after;
        List<Map<String, Object>> rows = db.queryForList("""
                select id, message_id, from_address, to_address, subject, body_text, received_at
                from emails
                where profile_id = ?
                  and channel = 'MAILBOX_CONNECTOR'
                  and lower(from_address) like ?
                  and received_at >= ?
                  and body_retained = true
                order by received_at desc
                limit 10
                """, ownerProfileId, "%@" + domain + "%", effectiveAfter);
        List<VerificationHit> hits = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String sender = TrackText.addressOf(String.valueOf(row.getOrDefault("from_address", "")));
            if (sender == null || !sender.toLowerCase(java.util.Locale.ROOT).endsWith("@" + domain)) continue;
            if (!deliveredToConnectedMailbox(row, connectedMailbox)) continue;
            String text = row.get("subject") + " " + row.get("body_text");
            Matcher otp = OTP.matcher(text);
            List<String> codes = new ArrayList<>();
            while (otp.find()) if (!codes.contains(otp.group(1))) codes.add(otp.group(1));
            if (codes.size() > 1) return Optional.empty();
            String code = codes.isEmpty() ? null : codes.get(0);
            Matcher link = LINK.matcher(text);
            List<String> links = new ArrayList<>();
            while (link.find()) if (!links.contains(link.group())) links.add(link.group());
            String url = links.size() == 1 ? links.get(0) : null;
            if (code == null && url == null) continue;
            Instant receivedAt = row.get("received_at") == null ? null : ((java.sql.Timestamp) row.get("received_at")).toInstant();
            hits.add(new VerificationHit("gmail", String.valueOf(row.get("message_id")),
                    UUID.fromString(String.valueOf(row.get("id"))), code, url, receivedAt));
            if (hits.size() > 1) return Optional.empty();
        }
        return hits.stream().findFirst();
    }

    private boolean deliveredToConnectedMailbox(Map<String, Object> row, String mailboxAddress) {
        String to = String.valueOf(row.getOrDefault("to_address", ""));
        String address = mailboxAddress == null ? "" : mailboxAddress.trim();
        if (address.isEmpty()) return false;
        String recipient = TrackText.addressOf(to);
        return recipient != null && recipient.equalsIgnoreCase(address);
    }
}
