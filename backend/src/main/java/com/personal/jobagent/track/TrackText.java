package com.personal.jobagent.track;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text handling for untrusted message content. Everything here is a bounded,
 * linear scan: message text is data to be measured, never instructions to be
 * followed, and no link in a message is ever fetched.
 */
public final class TrackText {

    /** Upper bound on the text any TRACK rule scans (subject + body). */
    public static final int MAX_SCAN_CHARS = 20_000;
    /** Upper bound on a stored evidence excerpt. */
    public static final int MAX_EXCERPT_CHARS = 240;
    /** Upper bound on the full body kept for a sign-in verification message. */
    public static final int MAX_RETAINED_BODY_CHARS = 10_000;

    private static final int MAX_URLS = 50;
    private static final int MAX_TOKENS = 20;

    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"'()\\[\\]{}|\\\\^`]{3,2048}",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,253}\\.[A-Za-z]{2,24}");
    private static final Pattern LONG_NUMBER = Pattern.compile("\\d[\\d\\s-]{2,}\\d");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * Labelled requisition / job identifiers ("Requisition ID: R-12345", "Req #4471",
     * "Job ID 99812", "Job number: JR-2026-042"). Only labelled tokens count: a bare
     * number is never treated as an identifier.
     */
    private static final Pattern REQUISITION = Pattern.compile(
            "\\b(?:requisition|req|job|position|vacancy|posting)\\s*(?:id|no\\.?|number|#|ref(?:erence)?|code)?\\s*[:#]?\\s*"
                    + "([A-Za-z]{0,4}[-_]?\\d{3,12}(?:[-_][A-Za-z0-9]{1,8}){0,2})\\b",
            Pattern.CASE_INSENSITIVE);

    /**
     * An application/confirmation reference quoted by the employer
     * ("Application reference: APP-778812"). Must contain a digit, so ordinary
     * words after the label are never mistaken for a reference.
     */
    private static final Pattern REFERENCE = Pattern.compile(
            "\\b(?:application|confirmation|candidate|submission)\\s+(?:reference|ref\\.?|number|no\\.?|id|#)\\s*[:#]?\\s*"
                    + "((?=[A-Za-z0-9-]{0,40}\\d)[A-Za-z0-9][A-Za-z0-9-]{3,40})\\b",
            Pattern.CASE_INSENSITIVE);

    private TrackText() {
    }

    /** Subject and body joined and truncated to {@link #MAX_SCAN_CHARS}. */
    public static String scanText(String subject, String body) {
        String s = (subject == null ? "" : subject) + "\n" + (body == null ? "" : body);
        return s.length() > MAX_SCAN_CHARS ? s.substring(0, MAX_SCAN_CHARS) : s;
    }

    public static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    public static String collapse(String s) {
        return s == null ? "" : WHITESPACE.matcher(s).replaceAll(" ").strip();
    }

    /** Up to 50 distinct http(s) links, trailing punctuation removed. */
    public static List<String> urls(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) return List.of();
        Matcher m = URL.matcher(text);
        while (m.find() && out.size() < MAX_URLS) {
            String u = m.group();
            while (!u.isEmpty() && ".,;:!?".indexOf(u.charAt(u.length() - 1)) >= 0) {
                u = u.substring(0, u.length() - 1);
            }
            if (u.length() > 10) out.add(u);
        }
        return new ArrayList<>(out);
    }

    /** Labelled requisition tokens, lower-cased. */
    public static List<String> requisitionTokens(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) return List.of();
        // Whitespace is collapsed first so the optional-whitespace runs in the
        // pattern can never backtrack over a long run of blanks.
        Matcher m = REQUISITION.matcher(collapse(cap(text, MAX_SCAN_CHARS)));
        while (m.find() && out.size() < MAX_TOKENS) {
            out.add(m.group(1).toLowerCase(Locale.ROOT));
        }
        return new ArrayList<>(out);
    }

    /** Labelled application/confirmation references, as quoted (upper-cased). */
    public static List<String> references(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) return List.of();
        Matcher m = REFERENCE.matcher(collapse(cap(text, MAX_SCAN_CHARS)));
        while (m.find() && out.size() < MAX_TOKENS) {
            out.add(m.group(1).toUpperCase(Locale.ROOT));
        }
        return new ArrayList<>(out);
    }

    /**
     * A short, redacted excerpt around a match: links become "[link]", e-mail
     * addresses "[email address]", long numbers (codes, phone numbers) "[number]".
     */
    public static String excerpt(String text, int start, int end) {
        if (text == null || text.isEmpty()) return null;
        int from = Math.max(0, start - 90);
        int to = Math.min(text.length(), end + 110);
        String window = text.substring(from, to);
        String cleaned = collapse(redact(window));
        if (cleaned.isEmpty()) return null;
        if (cleaned.length() > MAX_EXCERPT_CHARS) cleaned = cleaned.substring(0, MAX_EXCERPT_CHARS).strip() + "…";
        return (from > 0 ? "…" : "") + cleaned;
    }

    public static String redact(String text) {
        if (text == null) return null;
        String out = URL.matcher(cap(text, 2_000)).replaceAll("[link]");
        out = EMAIL.matcher(out).replaceAll("[email address]");
        out = LONG_NUMBER.matcher(out).replaceAll("[number]");
        return out;
    }

    /** Lower-case domain of an e-mail address ("Name <a@b.example>" → "b.example"), or null. */
    public static String domainOf(String address) {
        if (address == null) return null;
        Matcher m = EMAIL.matcher(address);
        if (!m.find()) return null;
        String a = m.group();
        return a.substring(a.indexOf('@') + 1).toLowerCase(Locale.ROOT);
    }

    /** Lower-case bare address, or null. */
    public static String addressOf(String address) {
        if (address == null) return null;
        Matcher m = EMAIL.matcher(address);
        return m.find() ? m.group().toLowerCase(Locale.ROOT) : null;
    }

    /** Alphanumeric-only lower-case form, for loose name comparison. */
    public static String squash(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Truncate to a maximum length (null-safe). */
    public static String cap(String value, int max) {
        if (value == null) return null;
        return value.length() > max ? value.substring(0, max) : value;
    }
}
