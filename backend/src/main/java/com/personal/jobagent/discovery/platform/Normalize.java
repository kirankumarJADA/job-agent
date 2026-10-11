package com.personal.jobagent.discovery.platform;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Conservative value conversions shared by every adapter. The rule throughout:
 * when a value is missing, ambiguous or implausible it becomes null (unknown),
 * never a guess.
 */
public final class Normalize {

    /** A posting date more than this far in the future is a source error, not a date. */
    static final Duration FUTURE_TOLERANCE = Duration.ofDays(1);

    private static final Set<String> ISO_CURRENCIES = Set.of(
            "USD", "GBP", "EUR", "CAD", "AUD", "NZD", "INR", "SGD", "CHF", "SEK", "NOK", "DKK", "PLN", "CZK",
            "HUF", "JPY", "CNY", "HKD", "BRL", "MXN", "ZAR", "AED", "SAR", "ILS", "TRY", "KRW", "PHP", "MYR",
            "IDR", "THB", "VND", "NGN", "KES", "EGP", "ARS", "CLP", "COP", "PEN", "RON", "BGN", "UAH", "TWD", "PKR");

    private Normalize() {
    }

    public static String blankToNull(String value) {
        if (value == null) return null;
        String trimmed = value.strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Absolute http(s) URL or null. Rejects javascript:, data:, relative and malformed values. */
    public static String httpUrlOrNull(String value) {
        String v = blankToNull(value);
        if (v == null) return null;
        try {
            URI uri = URI.create(v);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("https") && !scheme.equals("http")) return null;
            if (uri.getHost() == null || uri.getHost().isBlank()) return null;
            return uri.toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** ISO-4217 code or null. Currency symbols are ambiguous ($ is USD, CAD, AUD...) and are not mapped. */
    public static String currencyOrNull(String value) {
        String v = blankToNull(value);
        if (v == null) return null;
        String upper = v.toUpperCase(Locale.ROOT);
        return ISO_CURRENCIES.contains(upper) ? upper : null;
    }

    public static BigDecimal decimalOrNull(Object value) {
        if (value == null) return null;
        try {
            BigDecimal d = new BigDecimal(String.valueOf(value).strip());
            return d.signum() > 0 ? d : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Plain text from an HTML fragment, paragraphs preserved as line breaks. */
    public static String htmlToText(String html) {
        String once = htmlToTextOnce(html);
        // Some sources (Phenom JSON-LD) entity-encode their HTML, so one pass
        // yields markup as text; decode a second time only when that happened.
        return once.matches("(?s).*<(p|br|strong|b|em|i|div|li|ul|ol|span|h[1-6])(\\s[^>]*)?/?>.*") ? htmlToTextOnce(once) : once;
    }

    private static String htmlToTextOnce(String html) {
        if (html == null || html.isBlank()) return "";
        Document doc = Jsoup.parseBodyFragment(html);
        doc.outputSettings(new Document.OutputSettings().prettyPrint(false));
        doc.select("br").after("\\n");
        doc.select("p, div, li, h1, h2, h3, h4, h5, h6, tr").before("\\n");
        String text = doc.text().replace("\\n", "\n");
        return text.replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll(" *\n *", "\n")
                .replaceAll("\n{3,}", "\n\n")
                .strip();
    }

    /** REMOTE / HYBRID / ONSITE from the common vocabularies; null when not stated. */
    public static String remoteType(String value) {
        String v = blankToNull(value);
        if (v == null) return null;
        String s = v.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        if (s.contains("hybrid")) return "HYBRID";
        if (s.equals("remote") || s.equals("fullyremote") || s.equals("telecommute") || s.equals("remoteok")
                || s.equals("yes") || s.equals("true") || s.startsWith("remote")) return "REMOTE";
        if (s.equals("onsite") || s.equals("inoffice") || s.equals("office") || s.equals("onpremise")
                || s.equals("inperson")) return "ONSITE";
        return null;
    }

    /**
     * Parses an explicit posting timestamp: ISO instant/offset date-time, ISO
     * local date (taken as UTC midnight) or epoch milliseconds. Relative
     * phrases ("Posted Today") and locale-ambiguous dates ("02/06/2026") are
     * NOT parsed. Dates further than a day in the future are rejected.
     */
    public static java.time.Instant postedAt(Object value, Instant now) {
        Instant parsed = parseInstant(value);
        if (parsed == null) return null;
        Instant reference = now == null ? Instant.now() : now;
        if (parsed.isAfter(reference.plus(FUTURE_TOLERANCE))) return null;
        return parsed;
    }

    static Instant parseInstant(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) {
            long ms = n.longValue();
            return ms > 0 ? Instant.ofEpochMilli(ms) : null;
        }
        String v = blankToNull(String.valueOf(value));
        if (v == null) return null;
        if (v.matches("\\d{12,14}")) return Instant.ofEpochMilli(Long.parseLong(v));
        // "+0000"-style offsets (Phenom) are not ISO; add the colon.
        if (v.matches(".*T.*[+-]\\d{4}$")) v = v.substring(0, v.length() - 2) + ":" + v.substring(v.length() - 2);
        try {
            return OffsetDateTime.parse(v).toInstant();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return Instant.parse(v);
        } catch (DateTimeParseException ignored) {
        }
        try {
            // "2026-10-07 09:48:39 UTC" (Recruitee)
            if (v.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2} UTC")) {
                return java.time.LocalDateTime.parse(v.substring(0, 19), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                        .toInstant(ZoneOffset.UTC);
            }
            // RFC-1123 (RSS pubDate)
            if (v.matches("[A-Z][a-z]{2}, .*")) {
                return java.time.ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            }
            if (v.matches("\\d{4}-\\d{2}-\\d{2}")) {
                return LocalDate.parse(v).atStartOfDay(ZoneOffset.UTC).toInstant();
            }
            // ISO date-time without offset (e.g. Oracle "2026-10-08T00:00:00"): taken as UTC
            if (v.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?")) {
                return java.time.LocalDateTime.parse(v).toInstant(ZoneOffset.UTC);
            }
        } catch (DateTimeParseException ignored) {
        }
        return null;
    }

    /** Location placeholders some platforms emit instead of leaving a field empty. */
    public static String placeholderToNull(String value) {
        String v = blankToNull(value);
        if (v == null) return null;
        String l = v.toLowerCase(java.util.Locale.ROOT);
        return l.equals("unavailable") || l.equals("n/a") || l.equals("na") || l.equals("none") || l.equals("null")
                || l.equals("-") || l.equals("tbd") ? null : v;
    }

    /** Several distinct places, '; '-separated so "City, Region, Country" parts stay unambiguous. */
    public static String joinPlaces(List<String> places) {
        if (places == null) return null;
        Set<String> kept = new LinkedHashSet<>();
        places.stream().map(Normalize::blankToNull).filter(Objects::nonNull).forEach(kept::add);
        return kept.isEmpty() ? null : String.join("; ", kept);
    }

    /** Comma-joined distinct, non-blank parts. */
    public static String join(String... parts) {
        Set<String> kept = new LinkedHashSet<>();
        Arrays.stream(parts).map(Normalize::blankToNull).filter(Objects::nonNull).forEach(kept::add);
        return kept.isEmpty() ? null : String.join(", ", kept);
    }

    public static String join(List<String> parts) {
        return parts == null ? null : join(parts.toArray(String[]::new));
    }

    /** First non-blank value. */
    public static String first(String... values) {
        for (String v : values) {
            String b = blankToNull(v);
            if (b != null) return b;
        }
        return null;
    }
}
