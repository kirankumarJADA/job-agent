package com.personal.jobagent.gmail;

import com.fasterxml.jackson.databind.JsonNode;
import com.personal.jobagent.track.ReceiptVerificationPolicy;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Locale;

/**
 * Maps a Gmail API message resource to the connector contract: headers, the
 * minimal body needed for classification, and the sender-authentication
 * evidence parsed from the Authentication-Results headers that Gmail itself
 * evaluated at delivery time (retrieved through the authenticated API —
 * never taken from a request body or message content).
 */
public final class GmailMessageMapper {

    private GmailMessageMapper() {
    }

    public record GmailMessage(String messageId, String threadId, Long historyId, String from, String to,
                               String subject, Instant receivedAt, String bodyText,
                               ReceiptVerificationPolicy.SenderAuthentication authentication,
                               String connectedAddress) {}

    private static final String[] HEADERS = {"From", "To", "Subject", "Date", "Message-Id", "Delivered-To"};

    public static GmailMessage fromApi(JsonNode message, String connectedAddress) {
        String messageId = message.path("id").asText(null);
        String threadId = message.path("threadId").asText(null);
        long historyId = message.path("historyId").asLong(0);

        String from = null, to = null, subject = null, date = null, deliveredTo = null;
        JsonNode headers = message.path("payload").path("headers");
        if (headers.isArray()) {
            for (JsonNode header : headers) {
                String name = header.path("name").asText("");
                String value = header.path("value").asText("");
                switch (name) {
                    case "From" -> from = value;
                    case "To" -> to = value;
                    case "Subject" -> subject = value;
                    case "Date" -> date = value;
                    case "Delivered-To" -> deliveredTo = value;
                    default -> { }
                }
            }
        }

        ReceiptVerificationPolicy.SenderAuthentication auth = senderAuthentication(headers);
        String body = bodyText(message);
        Instant receivedAt = parseDate(date);

        // Delivered-to: the connected mailbox address appears in To or Delivered-To.
        String toAddress = TrackAddress.addressOf(to);
        String connected = TrackAddress.addressOf(connectedAddress);
        boolean delivered = toAddress != null && toAddress.equalsIgnoreCase(connected)
                || (deliveredTo != null && connected != null && deliveredTo.equalsIgnoreCase(connected));

        String toForMessage = delivered ? connectedAddress : to;
        return new GmailMessage(messageId, threadId, historyId, from, toForMessage, subject,
                receivedAt, body, auth, connected);
    }

    /**
     * Parses Gmail's Authentication-Results header (added by Gmail at delivery
     * time and retrieved through the authenticated API): dkim=pass with the
     * signing domain, plus spf/dmarc outcomes for the receipt policy.
     */
    private static ReceiptVerificationPolicy.SenderAuthentication senderAuthentication(JsonNode headers) {
        String dkim = null, dkimDomain = null, spf = null, dmarc = null;
        if (headers.isArray()) {
            for (JsonNode header : headers) {
                if (!"Authentication-Results".equalsIgnoreCase(header.path("name").asText(""))) continue;
                String value = header.path("value").asText("");
                if (dkim == null) {
                    dkim = outcome(value, "dkim");
                    dkimDomain = headerDomain(value, "header.d=");
                    spf = outcome(value, "spf");
                    dmarc = outcome(value, "dmarc");
                }
            }
        }
        return new ReceiptVerificationPolicy.SenderAuthentication(dkim, dkimDomain, spf, dmarc);
    }

    /** The first "mechanism=result" outcome for a mechanism, e.g. dkim=pass. */
    private static String outcome(String authenticationResults, String mechanism) {
        for (String part : authenticationResults.split(";")) {
            String trimmed = part.trim().toLowerCase(Locale.ROOT);
            if (trimmed.startsWith(mechanism.toLowerCase(Locale.ROOT) + "=")) {
                int space = trimmed.indexOf(' ');
                return space == -1 ? trimmed.substring(mechanism.length() + 1)
                        : trimmed.substring(mechanism.length() + 1, space);
            }
        }
        return null;
    }

    /** The signing domain from header.d=<domain> in the DKIM mechanism. */
    private static String headerDomain(String authenticationResults, String prefix) {
        int at = authenticationResults.toLowerCase(Locale.ROOT).indexOf(prefix.toLowerCase(Locale.ROOT));
        if (at == -1) return null;
        String rest = authenticationResults.substring(at + prefix.length());
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_')) {
                end = i;
                break;
            }
        }
        return rest.substring(0, end);
    }

    /** Decodes the first text/plain part, falling back to the snippet. Never HTML. */
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

    private static Instant parseDate(String rfc2822) {
        if (rfc2822 == null || rfc2822.isBlank()) return null;
        try {
            return DateTimeFormatter.RFC_1123_DATE_TIME.parse(rfc2822.trim(), Instant::from);
        } catch (Exception ignored) {
            // fall through: real mail often uses single-digit days
        }
        try {
            return java.time.format.DateTimeFormatter
                    .ofPattern("EEE, d MMM yyyy HH:mm:ss Z", Locale.ROOT)
                    .withZone(java.time.ZoneOffset.UTC)
                    .parse(rfc2822.trim(), Instant::from);
        } catch (Exception e) {
            return null;
        }
    }

    /** Minimal address extraction: the part after the last angle bracket, if any. */
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
