package com.personal.jobagent.mailbox.managed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.track.TrackText;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses JSON inbound messages delivered by the Cloudflare Email Worker into {@link InboundMailMessage}.
 */
public final class CloudflareInboundParser {

    static final int MAX_SUBJECT = 500;
    static final int MAX_TEXT = 10_000;
    static final int MAX_LINKS = 50;

    private static final Pattern HREF = Pattern.compile("(?i)href[ \\t\\r\\n]{0,10}=[ \\t\\r\\n]{0,10}[\"']([^\"'\\s<>]{1,2048})[\"']");
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");

    public record Result(InboundMailMessage message, String rejection) {
        public static Result ok(InboundMailMessage message) {
            return new Result(message, null);
        }

        public static Result rejected(String reason) {
            return new Result(null, reason);
        }

        public boolean accepted() {
            return message != null;
        }
    }

    private CloudflareInboundParser() {
    }

    public static Result parse(String jsonString, ObjectMapper mapper) {
        if (jsonString == null || jsonString.isBlank()) {
            return Result.rejected("empty_payload");
        }
        JsonNode root;
        try {
            root = mapper.readTree(jsonString);
        } catch (Exception e) {
            return Result.rejected("malformed_json");
        }
        if (!root.isObject()) {
            return Result.rejected("payload_not_object");
        }

        String rawRecipient = text(root, "envelopeRecipient");
        if (rawRecipient == null) rawRecipient = text(root, "to");
        Optional<String> envelopeRecipient = MailAddresses.bareAddress(rawRecipient);
        if (envelopeRecipient.isEmpty()) {
            return Result.rejected("missing_or_invalid_recipient");
        }

        String rawSender = text(root, "envelopeSender");
        if (rawSender == null) rawSender = text(root, "fromAddress");
        String envelopeSender = MailAddresses.bareAddress(rawSender).orElse(null);

        String from = text(root, "from");
        String fromHeader = from == null ? "" : TrackText.cap(from.trim(), 998);
        String fromAddress = MailAddresses.singleMailbox(fromHeader).orElse(envelopeSender);

        String subjectRaw = text(root, "subject");
        String subject = TrackText.cap(subjectRaw == null ? "" : subjectRaw.trim(), MAX_SUBJECT);

        String plainText = text(root, "text");
        String html = text(root, "html");
        String text;
        if (plainText != null && !plainText.isBlank()) {
            text = TrackText.cap(plainText.trim(), MAX_TEXT);
        } else if (html != null && !html.isBlank()) {
            text = TrackText.cap(HTML_TAG.matcher(html).replaceAll(" ").trim(), MAX_TEXT);
        } else {
            text = "";
        }

        List<String> links = extractLinks(text, html);
        String messageId = cleanMessageId(text(root, "messageId"));
        String date = text(root, "date");

        List<String> spfResults = collectResult(text(root, "spf"));
        List<String> dkimResults = collectResult(text(root, "dkim"));
        List<String> spamFlags = collectResult(text(root, "spam"));

        InboundMailMessage message = new InboundMailMessage(
                ManagedMailSettings.PROVIDER_CLOUDFLARE,
                envelopeRecipient.get(),
                envelopeSender,
                fromHeader,
                fromAddress,
                subject,
                text,
                links,
                messageId,
                date,
                spfResults,
                dkimResults,
                spamFlags
        );
        return Result.ok(message);
    }

    private static String text(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return (child != null && child.isTextual()) ? child.asText() : null;
    }

    private static List<String> collectResult(String val) {
        if (val == null || val.isBlank()) return List.of();
        String lower = val.trim().toLowerCase(Locale.ROOT);
        return List.of(lower);
    }

    private static String cleanMessageId(String id) {
        if (id == null) return null;
        String trimmed = id.trim();
        if (trimmed.startsWith("<") && trimmed.endsWith(">") && trimmed.length() > 2) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed.isEmpty() || trimmed.length() > 255 ? null : trimmed;
    }

    private static List<String> extractLinks(String text, String html) {
        Set<String> links = new LinkedHashSet<>();
        if (text != null) {
            Matcher m = Pattern.compile("https?://[^\\s<>\"'()\\[\\]{}|\\\\^`]{3,2048}").matcher(text);
            while (m.find() && links.size() < MAX_LINKS) {
                links.add(m.group());
            }
        }
        if (html != null && links.size() < MAX_LINKS) {
            Matcher m = HREF.matcher(html);
            while (m.find() && links.size() < MAX_LINKS) {
                String href = m.group(1);
                if (href.startsWith("http://") || href.startsWith("https://")) {
                    links.add(href);
                }
            }
        }
        return List.copyOf(links);
    }
}
