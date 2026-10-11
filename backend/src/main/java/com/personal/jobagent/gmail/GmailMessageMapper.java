package com.personal.jobagent.gmail;

import com.fasterxml.jackson.databind.JsonNode;
import com.personal.jobagent.track.ReceiptVerificationPolicy;

import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps a Gmail API resource into the TRACK connector contract.
 *
 * Authentication-Results is accepted only from Gmail's expected receiver authserv-id
 * in the first such header. A sender-supplied arbitrary Authentication-Results header
 * is untrusted input, even when fetched through an authenticated Gmail API.
 */
public final class GmailMessageMapper {

    private static final String GOOGLE_AUTHSERV_ID = "mx.google.com";
    private static final Pattern DKIM_DOMAIN = Pattern.compile(
            "(?:^|\\s)header\\.d=([A-Za-z0-9.-]+)", Pattern.CASE_INSENSITIVE);
    /**
     * Gmail reports the DKIM signer as the agent/user identity ("header.i=@domain"),
     * not "header.d=". RFC 6376 requires that identity's domain to equal the signing
     * domain or be a subdomain of it, so using it for alignment is never looser.
     */
    private static final Pattern DKIM_IDENTITY_DOMAIN = Pattern.compile(
            "(?:^|\\s)header\\.i=[^@\\s;]*@([A-Za-z0-9.-]+)", Pattern.CASE_INSENSITIVE);

    private GmailMessageMapper() {}

    public record GmailMessage(String messageId, String threadId, Long historyId, String from, String to,
                               String subject, Instant receivedAt, String bodyText,
                               ReceiptVerificationPolicy.SenderAuthentication authentication,
                               String connectedAddress) {}

    /**
     * A mailbox item is eligible as delivered-email evidence only when Gmail's
     * structured message resource says it is in INBOX and not SENT/DRAFT/SPAM/TRASH.
     * Message To/Delivered-To headers are sender-controlled and are not sufficient.
     */
    public static boolean isInboxReceived(JsonNode message) {
        JsonNode labels = message.path("labelIds");
        if (!labels.isArray()) return false;
        boolean inbox = false;
        for (JsonNode label : labels) {
            String value = label.asText("");
            if ("INBOX".equals(value)) inbox = true;
            if ("SENT".equals(value) || "DRAFT".equals(value) || "SPAM".equals(value)
                    || "TRASH".equals(value)) return false;
        }
        return inbox;
    }

    public static GmailMessage fromApi(JsonNode message, String connectedAddress) {
        String messageId = message.path("id").asText(null);
        String threadId = message.path("threadId").asText(null);
        Long historyId = message.path("historyId").isNumber()
                || message.path("historyId").isTextual()
                ? message.path("historyId").asLong() : null;

        String from = null, to = null, subject = null;
        JsonNode headers = message.path("payload").path("headers");
        if (headers.isArray()) {
            for (JsonNode header : headers) {
                String name = header.path("name").asText("");
                String value = header.path("value").asText("");
                if ("From".equalsIgnoreCase(name)) from = value;
                else if ("To".equalsIgnoreCase(name)) to = value;
                else if ("Subject".equalsIgnoreCase(name)) subject = value;
            }
        }

        ReceiptVerificationPolicy.SenderAuthentication auth = senderAuthentication(headers);
        String body = bodyText(message);

        // Gmail's internalDate is provider-generated; the RFC 2822 Date header can
        // be supplied by the sender and must not be treated as the receipt time.
        long internalDate = message.path("internalDate").asLong(0);
        Instant receivedAt = internalDate > 0 ? Instant.ofEpochMilli(internalDate) : null;

        String connected = TrackAddress.addressOf(connectedAddress);
        boolean deliveredToConnectedMailbox = isInboxReceived(message);
        // EmailIntelligenceService derives its trusted delivery boolean by comparing
        // this value with mailboxAddress. Set it from Gmail's structured labels, not
        // from a potentially forged To or Delivered-To header.
        String toForMessage = deliveredToConnectedMailbox ? connectedAddress : to;
        return new GmailMessage(messageId, threadId, historyId, from, toForMessage, subject,
                receivedAt, body, auth, connected);
    }

    /**
     * Trust only the first Authentication-Results header and only when its authserv-id
     * is Gmail's receiver identity. If an untrusted one comes first, fail closed rather
     * than searching later headers for a convenient pass result.
     */
    private static ReceiptVerificationPolicy.SenderAuthentication senderAuthentication(JsonNode headers) {
        if (!headers.isArray()) return ReceiptVerificationPolicy.SenderAuthentication.none();
        for (JsonNode header : headers) {
            if (!"Authentication-Results".equalsIgnoreCase(header.path("name").asText(""))) continue;
            String value = header.path("value").asText("");
            int separator = value.indexOf(';');
            if (separator < 0 || !GOOGLE_AUTHSERV_ID.equalsIgnoreCase(value.substring(0, separator).trim())) {
                return ReceiptVerificationPolicy.SenderAuthentication.none();
            }
            String results = value.substring(separator + 1);
            String dkim = outcome(results, "dkim");
            String dkimDomain = dkimDomain(results);
            String spf = outcome(results, "spf");
            String dmarc = outcome(results, "dmarc");
            return new ReceiptVerificationPolicy.SenderAuthentication(dkim, dkimDomain, spf, dmarc);
        }
        return ReceiptVerificationPolicy.SenderAuthentication.none();
    }

    private static String outcome(String results, String mechanism) {
        for (String part : results.split(";")) {
            String trimmed = part.trim().toLowerCase(Locale.ROOT);
            if (trimmed.startsWith(mechanism.toLowerCase(Locale.ROOT) + "=")) {
                int space = trimmed.indexOf(' ');
                return space == -1 ? trimmed.substring(mechanism.length() + 1)
                        : trimmed.substring(mechanism.length() + 1, space);
            }
        }
        return null;
    }

    private static String dkimDomain(String results) {
        for (String part : results.split(";")) {
            String trimmed = part.trim();
            if (!trimmed.toLowerCase(Locale.ROOT).startsWith("dkim=")) continue;
            Matcher signingDomain = DKIM_DOMAIN.matcher(trimmed);
            if (signingDomain.find()) return signingDomain.group(1);
            Matcher identityDomain = DKIM_IDENTITY_DOMAIN.matcher(trimmed);
            return identityDomain.find() ? identityDomain.group(1) : null;
        }
        return null;
    }

    /** Decodes the first text/plain part, falling back to Gmail's safe snippet. */
    private static String bodyText(JsonNode message) {
        JsonNode parts = message.path("payload").path("parts");
        String text = findTextPlain(parts);
        if (text == null) {
            String single = message.path("payload").path("body").path("data").asText("");
            text = single.isEmpty() ? null : decode(single);
        }
        if (text == null || text.isBlank()) {
            String snippet = message.path("snippet").asText("");
            return snippet.isBlank() ? "" : snippet;
        }
        return text;
    }

    private static String findTextPlain(JsonNode parts) {
        if (!parts.isArray()) return null;
        for (JsonNode part : parts) {
            String mime = part.path("mimeType").asText("");
            if ("text/plain".equals(mime)) {
                String data = part.path("body").path("data").asText("");
                return data.isEmpty() ? null : decode(data);
            }
            String nested = findTextPlain(part.path("parts"));
            if (nested != null) return nested;
        }
        return null;
    }

    private static String decode(String base64Url) {
        try {
            return new String(Base64.getUrlDecoder().decode(base64Url), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static final class TrackAddress {
        private static String addressOf(String header) {
            if (header == null || header.isBlank()) return null;
            String value = header.trim();
            int open = value.lastIndexOf('<');
            int close = value.lastIndexOf('>');
            if (open != -1 && close > open) return value.substring(open + 1, close).trim();
            return value;
        }
    }
}
