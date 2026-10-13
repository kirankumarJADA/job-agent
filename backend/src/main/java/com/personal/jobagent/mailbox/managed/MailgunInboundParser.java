package com.personal.jobagent.mailbox.managed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personal.jobagent.track.TrackText;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a Mailgun inbound-route POST ({@code forward()} action, form-encoded or
 * multipart) into an {@link InboundMailMessage}.
 *
 * <p>Field names are Mailgun's documented ones: {@code recipient} (SMTP
 * envelope recipient), {@code sender} (envelope sender), {@code from},
 * {@code subject}, {@code body-plain}, {@code body-html} and
 * {@code message-headers} (a JSON list of [name, value] pairs). Attachments are
 * ignored. Anything malformed is refused with a reason code, never repaired:
 * the controller answers 406 so Mailgun does not retry a request that can never
 * succeed.
 */
public final class MailgunInboundParser {

    /** Fields that must never arrive more than once (parameter pollution). */
    public static final List<String> SINGLE_VALUED = List.of("timestamp", "token", "signature", "recipient",
            "sender", "from", "subject", "body-plain", "body-html", "message-headers");

    static final int MAX_SUBJECT = 500;
    static final int MAX_TEXT = 10_000;
    static final int MAX_LINKS = 50;
    static final int MAX_LINK_LENGTH = 2_048;
    private static final int MAX_HEADERS_JSON = 256 * 1024;
    private static final int MAX_HEADERS = 500;
    private static final int MAX_HTML_SCAN = 200_000;

    private static final Pattern HREF = Pattern.compile("(?i)href[ \\t\\r\\n]{0,10}=[ \\t\\r\\n]{0,10}[\"']([^\"'\\s<>]{1,2048})[\"']");

    /** Parse result: exactly one of {@code message} and {@code rejection} is set. */
    public record Result(InboundMailMessage message, String rejection) {
        static Result ok(InboundMailMessage message) {
            return new Result(message, null);
        }

        static Result rejected(String reason) {
            return new Result(null, reason);
        }

        public boolean accepted() {
            return message != null;
        }
    }

    private MailgunInboundParser() {
    }

    /**
     * @param params multi-valued request parameters (as
     *               {@code HttpServletRequest::getParameterValues})
     */
    public static Result parse(Function<String, String[]> params, ObjectMapper json) {
        for (String name : SINGLE_VALUED) {
            String[] values = params.apply(name);
            if (values != null && values.length > 1) return Result.rejected("DUPLICATED_FIELD");
        }
        Optional<String> recipient = MailAddresses.bareAddress(first(params, "recipient"));
        if (recipient.isEmpty()) return Result.rejected("INVALID_RECIPIENT");

        List<String[]> headers;
        try {
            headers = headers(first(params, "message-headers"), json);
        } catch (IllegalArgumentException malformed) {
            return Result.rejected("MALFORMED_HEADERS");
        }

        String fromHeader = first(params, "from");
        if (fromHeader == null || fromHeader.isBlank()) {
            List<String> fromHeaders = headerValues(headers, "from");
            fromHeader = fromHeaders.size() == 1 ? fromHeaders.get(0) : null;
        }
        String fromAddress = MailAddresses.singleMailbox(fromHeader).orElse(null);

        String subject = first(params, "subject");
        if (subject == null) {
            List<String> subjects = headerValues(headers, "subject");
            subject = subjects.isEmpty() ? "" : subjects.get(0);
        }

        String plain = first(params, "body-plain");
        String html = first(params, "body-html");
        String text = plain != null && !plain.isBlank() ? plain : htmlToText(html);

        Set<String> links = new LinkedHashSet<>();
        for (String url : TrackText.urls(TrackText.cap(text, TrackText.MAX_SCAN_CHARS))) addLink(links, url);
        if (html != null) {
            Matcher href = HREF.matcher(TrackText.cap(html, MAX_HTML_SCAN));
            while (href.find() && links.size() < MAX_LINKS) {
                addLink(links, href.group(1).replace("&amp;", "&"));
            }
        }

        List<String> messageIds = headerValues(headers, "message-id");
        String messageId = messageIds.size() == 1 && !messageIds.get(0).isBlank()
                ? TrackText.cap(messageIds.get(0).trim().toLowerCase(Locale.ROOT), 998) : null;
        List<String> dates = headerValues(headers, "date");

        return Result.ok(new InboundMailMessage(ManagedMailSettings.PROVIDER_MAILGUN, recipient.get(),
                TrackText.cap(blankToNull(first(params, "sender")), 320),
                TrackText.cap(blankToNull(fromHeader), 320), fromAddress,
                TrackText.cap(subject, MAX_SUBJECT), TrackText.cap(text == null ? "" : text, MAX_TEXT),
                new ArrayList<>(links), messageId, dates.isEmpty() ? null : TrackText.cap(dates.get(0), 200),
                verdicts(headers, "x-mailgun-spf"), verdicts(headers, "x-mailgun-dkim-check-result"),
                verdicts(headers, "x-mailgun-sflag")));
    }

    /**
     * SHA-256 over every field this service reads, in a fixed order. A provider
     * retry of an accepted request carries the same digest; a captured token
     * re-sent with different content does not.
     */
    public static String payloadDigest(Function<String, String[]> params) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String name : SINGLE_VALUED) {
                String value = first(params, name);
                byte[] bytes = (value == null ? "\u0000null" : value).getBytes(StandardCharsets.UTF_8);
                digest.update(name.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Plain text of an HTML part, for messages without a text part. A single
     * linear pass (no backtracking patterns over sender-controlled markup):
     * script and style content is dropped, block-level tags become line breaks,
     * every other tag becomes a space.
     */
    static String htmlToText(String html) {
        if (html == null || html.isBlank()) return "";
        String s = TrackText.cap(html, MAX_HTML_SCAN);
        StringBuilder out = new StringBuilder(Math.min(s.length(), MAX_TEXT * 2));
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c != '<') {
                out.append(c);
                i++;
                continue;
            }
            int close = s.indexOf('>', i);
            if (close < 0) break;                       // unterminated tag: nothing more is markup-safe
            String tag = s.substring(i + 1, Math.min(close, i + 12)).toLowerCase(Locale.ROOT);
            if (tag.startsWith("script") || tag.startsWith("style")) {
                int endAt = closingTag(s, close, tag.startsWith("script") ? "</script" : "</style");
                if (endAt < 0) break;
                int endClose = s.indexOf('>', endAt);
                if (endClose < 0) break;
                out.append(' ');
                i = endClose + 1;
                continue;
            }
            boolean block = tag.startsWith("br") || tag.startsWith("/p") || tag.startsWith("/div")
                    || tag.startsWith("/tr") || tag.startsWith("/li") || tag.matches("^/h[1-6].*");
            out.append(block ? '\n' : ' ');
            i = close + 1;
        }
        String text = out.toString().replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&");
        return text.replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll("\\n[ ]*(\\n[ ]*)+", "\n\n").strip();
    }

    /** Index of the next case-insensitive {@code end} (e.g. "</script") at or after {@code from}, or -1. */
    private static int closingTag(String s, int from, String end) {
        int at = s.indexOf("</", from);
        while (at >= 0) {
            if (s.regionMatches(true, at, end, 0, end.length())) return at;
            at = s.indexOf("</", at + 2);
        }
        return -1;
    }

    private static void addLink(Set<String> links, String url) {
        if (links.size() >= MAX_LINKS || url == null) return;
        String trimmed = url.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (trimmed.length() > MAX_LINK_LENGTH || !(lower.startsWith("https://") || lower.startsWith("http://"))) return;
        links.add(trimmed);
    }

    private static List<String[]> headers(String raw, ObjectMapper json) {
        List<String[]> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        if (raw.length() > MAX_HEADERS_JSON) throw new IllegalArgumentException("headers too large");
        JsonNode root;
        try {
            root = json.readTree(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException("headers are not JSON");
        }
        if (root == null || !root.isArray() || root.size() > MAX_HEADERS) {
            throw new IllegalArgumentException("headers must be a bounded JSON array");
        }
        for (JsonNode pair : root) {
            if (!pair.isArray() || pair.size() != 2 || !pair.get(0).isTextual() || !pair.get(1).isTextual()) {
                throw new IllegalArgumentException("each header must be a [name, value] pair");
            }
            out.add(new String[]{pair.get(0).asText().trim().toLowerCase(Locale.ROOT), pair.get(1).asText()});
        }
        return out;
    }

    private static List<String> headerValues(List<String[]> headers, String lowerName) {
        List<String> values = new ArrayList<>();
        for (String[] header : headers) if (header[0].equals(lowerName)) values.add(header[1]);
        return values;
    }

    private static List<String> verdicts(List<String[]> headers, String lowerName) {
        List<String> values = new ArrayList<>();
        for (String value : headerValues(headers, lowerName)) {
            values.add(TrackText.cap(value.trim().toLowerCase(Locale.ROOT), 40));
        }
        return values;
    }

    private static String first(Function<String, String[]> params, String name) {
        String[] values = params.apply(name);
        return values == null || values.length == 0 ? null : values[0];
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
