package com.personal.jobagent.discovery;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DiscoveryExtractionParser {
    private static final Pattern TITLE = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern OG_TITLE = Pattern.compile("<meta[^>]+(?:property|name)=[\\\"'](?:og:title|title)[\\\"'][^>]+content=[\\\"'](.*?)[\\\"']", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern DESCRIPTION = Pattern.compile("<meta[^>]+(?:name|property)=[\\\"'](?:description|og:description)[\\\"'][^>]+content=[\\\"'](.*?)[\\\"']", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CANONICAL = Pattern.compile("<link[^>]+rel=[\\\"']canonical[\\\"'][^>]+href=[\\\"'](.*?)[\\\"']", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern JSON_LD = Pattern.compile("<script[^>]+type=[\\\"']application/ld\\+json[\\\"'][^>]*>(.*?)</script>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern LINK = Pattern.compile("<a[^>]+href=[\\\"']([^\\\"']+)[\\\"'][^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private DiscoveryExtractionParser() {}

    static ScraperProvider.ExtractedJob fromHtml(String html, String requestedUrl, String provider, double confidence) {
        String title = first(OG_TITLE, html, 1);
        if (blank(title)) title = first(TITLE, html, 1);
        String description = first(DESCRIPTION, html, 1);
        JsonNode structured = firstJobPosting(html);
        if (structured != null) {
            title = text(structured, "title", title);
            description = text(structured, "description", description);
        }
        if (blank(title)) return null;
        String canonical = first(CANONICAL, html, 1);
        if (blank(canonical)) canonical = requestedUrl;
        String body = clean(blank(description) ? html : description);
        String company = structured == null ? null : company(structured);
        String location = structured == null ? null : location(structured);
        String application = structured == null ? canonical : text(structured, "url", canonical);
        String id = structured == null ? null : text(structured, "identifier", null);
        String hash = sha256(clean(title) + "\n" + body + "\n" + Objects.toString(company, "") + "\n" + Objects.toString(location, ""));
        return new ScraperProvider.ExtractedJob(clean(title), cleanNullable(company), cleanNullable(location), null,
                null, structured == null ? null : text(structured, "employmentType", null), body, skills(body),
                application, canonical, structured == null ? null : date(structured), cleanNullable(id), hash,
                confidence, Map.of("provider", provider, "canonicalUrl", canonical));
    }

    static ScraperProvider.ExtractedJob fromMarkdown(String markdown, String requestedUrl, String provider, double confidence, JsonNode data) {
        String title = data == null ? null : text(data, "title", null);
        if (blank(title)) title = markdown.lines().map(String::trim).filter(s -> s.startsWith("#")).findFirst().orElse(null);
        if (blank(title)) title = firstNonBlank(markdown.lines().map(String::trim).toList());
        if (blank(title)) return null;
        String description = data == null ? markdown : text(data, "description", markdown);
        String application = data == null ? requestedUrl : text(data, "application_url", requestedUrl);
        String source = data == null ? requestedUrl : text(data, "source_url", requestedUrl);
        String company = data == null ? null : text(data, "company", null);
        String location = data == null ? null : text(data, "location", null);
        String id = data == null ? null : text(data, "external_job_id", null);
        String cleanTitle = clean(title), body = clean(description);
        return new ScraperProvider.ExtractedJob(cleanTitle, cleanNullable(company), cleanNullable(location),
                data == null ? null : text(data, "remote_type", null), data == null ? null : text(data, "salary", null),
                data == null ? null : text(data, "employment_type", null), body, skills(body), application, source,
                data == null ? null : date(data), id, sha256(cleanTitle + "\n" + body), confidence,
                Map.of("provider", provider));
    }

    static List<String> links(String html) {
        List<String> result = new ArrayList<>(); Matcher matcher = LINK.matcher(html);
        while (matcher.find()) { String href = clean(matcher.group(1)); if (!href.isBlank()) result.add(href); }
        return result;
    }

    static String canonical(String html, String fallback) { String value = first(CANONICAL, html, 1); return blank(value) ? fallback : value.trim(); }
    static String clean(String value) { return value == null ? "" : value.replaceAll("<[^>]+>", " ").replaceAll("&(?:amp|lt|gt|quot|#39);", " ").replaceAll("\\s+", " ").trim(); }
    static String sha256(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception e) { throw new IllegalStateException(e); } }

    private static JsonNode firstJobPosting(String html) {
        // JSON-LD is parsed only when it is a valid object; malformed page scripts are ignored safely.
        for (String script : matches(JSON_LD, html)) try { JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(script.trim()); if (node.isArray()) for (JsonNode n : node) if ("JobPosting".equalsIgnoreCase(n.path("@type").asText())) return n; if ("JobPosting".equalsIgnoreCase(node.path("@type").asText())) return node; } catch (Exception ignored) {}
        return null;
    }
    private static String company(JsonNode n) { JsonNode c=n.path("hiringOrganization"); return c.isObject()?c.path("name").asText(null):c.asText(null); }
    private static String location(JsonNode n) { JsonNode l=n.path("jobLocation"); if(l.isArray()&&l.size()>0)l=l.get(0); JsonNode a=l.path("address"); return a.isObject()?String.join(", ", Arrays.asList(a.path("addressLocality").asText(""),a.path("addressRegion").asText(""),a.path("addressCountry").asText("" )).stream().filter(s->!s.isBlank()).toList()):l.asText(null); }
    private static Instant date(JsonNode n) { try { String s=text(n,"datePosted",null); return s==null?null:Instant.parse(s); } catch(Exception e){ return null; } }
    private static List<String> skills(String body) { List<String> found=new ArrayList<>(); for(String skill:List.of("Java","Python","JavaScript","TypeScript","React","Spring","SQL","AWS","Docker","Kubernetes")) if(body.toLowerCase(Locale.ROOT).contains(skill.toLowerCase(Locale.ROOT))) found.add(skill); return found; }
    private static String text(JsonNode n,String key,String fallback) { JsonNode v=n==null?null:n.path(key); return v==null||v.isMissingNode()||v.isNull()||v.asText().isBlank()?fallback:v.asText(); }
    private static String first(Pattern p,String text,int group) { Matcher m=p.matcher(text); return m.find()?m.group(group):null; }
    private static List<String> matches(Pattern p,String text) { List<String> out=new ArrayList<>(); Matcher m=p.matcher(text); while(m.find())out.add(m.group(1)); return out; }
    private static String firstNonBlank(List<String> lines) { return lines.stream().filter(s->!s.isBlank()).findFirst().orElse(null); }
    private static boolean blank(String s){return s==null||s.isBlank();}
    private static String cleanNullable(String s){String c=clean(s);return c.isBlank()?null:c;}
}
