package com.personal.jobagent.discovery.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Parsing utilities shared by adapters. Malformed input raises {@link MalformedException}. */
public final class Parsing {

    static final ObjectMapper JSON = new ObjectMapper();

    private Parsing() {
    }

    public static JsonNode json(String body) {
        try {
            JsonNode node = JSON.readTree(body);
            if (node == null || node.isMissingNode()) throw new MalformedException("empty JSON body");
            return node;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new MalformedException("invalid JSON");
        }
    }

    public static String text(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || v.isMissingNode()) return null;
        if (v.isValueNode()) return Normalize.blankToNull(v.asText());
        return null;
    }

    public static JsonNode at(JsonNode node, String pointer) {
        if (node == null) return MissingNode.getInstance();
        return node.at(pointer);
    }

    public static String textAt(JsonNode node, String pointer) {
        JsonNode v = at(node, pointer);
        return v.isValueNode() ? Normalize.blankToNull(v.asText()) : null;
    }

    public static List<String> textList(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(item -> {
                if (item.isValueNode() && !item.asText().isBlank()) out.add(item.asText().strip());
            });
        }
        return out;
    }

    public static Document html(String body, String baseUri) {
        return Jsoup.parse(body == null ? "" : body, baseUri == null ? "" : baseUri);
    }

    /** All schema.org JobPosting objects embedded as JSON-LD (top level, arrays and @graph). */
    public static List<JsonNode> jsonLdJobPostings(Document doc) {
        List<JsonNode> postings = new ArrayList<>();
        for (Element script : doc.select("script[type=application/ld+json]")) {
            JsonNode node;
            try {
                node = JSON.readTree(script.data());
            } catch (Exception e) {
                continue; // one broken block must not hide the others
            }
            collectPostings(node, postings);
        }
        return postings;
    }

    private static void collectPostings(JsonNode node, List<JsonNode> out) {
        if (node == null) return;
        if (node.isArray()) {
            node.forEach(n -> collectPostings(n, out));
            return;
        }
        if (!node.isObject()) return;
        JsonNode type = node.get("@type");
        if (type != null && (("JobPosting".equals(type.asText()))
                || (type.isArray() && type.toString().contains("\"JobPosting\"")))) {
            out.add(node);
        }
        if (node.has("@graph")) collectPostings(node.get("@graph"), out);
    }

    /**
     * Builds a job from a schema.org JobPosting. Only fields the posting states
     * are set; the caller supplies identity and URLs.
     */
    public static NormalizedJob.Builder fromJsonLd(JsonNode posting, String externalId, Instant now) {
        String title = Normalize.htmlToText(text(posting, "title"));
        NormalizedJob.Builder b = NormalizedJob.builder(externalId, title)
                .description(Normalize.htmlToText(text(posting, "description")))
                .postedAt(Normalize.postedAt(text(posting, "datePosted"), now));
        JsonNode org = posting.get("hiringOrganization");
        if (org != null) b.employer(org.isTextual() ? org.asText() : text(org, "name"));
        JsonNode employment = posting.get("employmentType");
        if (employment != null) {
            b.employmentType(employment.isArray() ? String.join(", ", textList(employment)) : employment.asText());
        }
        List<String> places = new ArrayList<>();
        String city = null, country = null;
        JsonNode locations = posting.get("jobLocation");
        if (locations != null) {
            for (JsonNode loc : locations.isArray() ? iterable(locations) : List.of(locations)) {
                JsonNode addr = loc.get("address");
                if (addr == null) continue;
                if (addr.isTextual()) {
                    places.add(addr.asText());
                    continue;
                }
                String c = Normalize.placeholderToNull(text(addr, "addressLocality"));
                String r = Normalize.placeholderToNull(text(addr, "addressRegion"));
                JsonNode countryNode = addr.get("addressCountry");
                String co = Normalize.placeholderToNull(countryNode == null ? null
                        : countryNode.isTextual() ? countryNode.asText() : text(countryNode, "name"));
                String joined = Normalize.join(c, r, co);
                if (joined != null) places.add(joined);
                if (city == null) city = c;
                if (country == null) country = co;
            }
        }
        // A single city/country is only stated when the posting has exactly one place.
        boolean single = places.size() <= 1;
        b.location(Normalize.joinPlaces(places)).city(single ? city : null).country(single ? country : null);
        String locationType = text(posting, "jobLocationType");
        if (locationType != null && locationType.equalsIgnoreCase("TELECOMMUTE")) b.remote("REMOTE");
        JsonNode salary = posting.get("baseSalary");
        if (salary != null && salary.isObject()) {
            JsonNode value = salary.get("value");
            String currency = Normalize.first(text(salary, "currency"), text(posting, "salaryCurrency"));
            BigDecimal min = null, max = null;
            String unit = null;
            if (value != null && value.isObject()) {
                min = Normalize.decimalOrNull(text(value, "minValue"));
                max = Normalize.decimalOrNull(text(value, "maxValue"));
                if (min == null && max == null) min = max = Normalize.decimalOrNull(text(value, "value"));
                unit = text(value, "unitText");
            } else if (value != null && value.isValueNode()) {
                min = max = Normalize.decimalOrNull(value.asText());
            }
            b.salary(min, max, currency, unit);
        }
        String validThrough = text(posting, "validThrough");
        if (validThrough != null) {
            Instant until = Normalize.parseInstant(validThrough);
            if (until != null && until.isBefore(now)) b.active(false);
        }
        return b;
    }

    public static Iterable<JsonNode> iterable(JsonNode array) {
        List<JsonNode> list = new ArrayList<>();
        if (array != null && array.isArray()) array.forEach(list::add);
        return list;
    }

    /**
     * The balanced JSON object starting at the first '{' at or after
     * {@code start}, honouring string literals and escapes. Used for
     * script-embedded state objects.
     */
    public static Optional<String> balancedObject(String source, int start) {
        int open = source.indexOf('{', start);
        if (open < 0) return Optional.empty();
        int depth = 0;
        boolean inString = false, escaped = false;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
            } else if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) return Optional.of(source.substring(open, i + 1));
            }
        }
        return Optional.empty();
    }

    public static List<String> pathSegments(URI uri) {
        if (uri.getPath() == null) return List.of();
        return Arrays.stream(uri.getPath().split("/")).filter(s -> !s.isBlank()).toList();
    }

    public static String host(URI uri) {
        return uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
    }

    public static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public static URI uri(String value) {
        try {
            return URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new MalformedException("invalid URL built from source data");
        }
    }

    /** Slug-ish identifier check shared by subdomain/slug based platforms. */
    public static String requireSlug(String value, String what, String pattern) {
        String v = value == null ? "" : value.strip();
        if (!v.matches(pattern)) throw new BoardConfigException(what + " is not valid: expected " + pattern);
        return v;
    }

    public static class MalformedException extends RuntimeException {
        public MalformedException(String message) {
            super(message);
        }
    }
}
