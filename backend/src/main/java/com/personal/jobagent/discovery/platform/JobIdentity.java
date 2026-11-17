package com.personal.jobagent.discovery.platform;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cross-source job identity. A posting is recognised as the same job only on
 * STRONG evidence, strongest first:
 * <ol>
 *   <li>{@code src:} the same source's own external id;</li>
 *   <li>{@code ats:} the same platform job identifier embedded in a job or
 *       application URL (e.g. a Greenhouse job id, a Workday requisition
 *       path) — this is how a posting found through an aggregator is joined
 *       to the same posting fetched from the employer's own ATS;</li>
 *   <li>{@code req:} the same employer requisition id;</li>
 *   <li>{@code url:} the same normalised job or application URL (tracking
 *       parameters, fragments, case and trailing slashes ignored).</li>
 * </ol>
 * Employer + title (+ location) is NEVER an identity: two genuinely different
 * roles with the same title stay separate.
 */
public final class JobIdentity {

    public enum KeyType { SRC, ATS, REQ, URL }

    public record Key(KeyType type, String value) {
        public String asString() {
            return type.name().toLowerCase(Locale.ROOT) + ":" + value;
        }
    }

    /** Hosts whose URLs identify the aggregator, never the employer. */
    private static final Set<String> AGGREGATOR_HOSTS = Set.of("tsenta.com", "www.tsenta.com", "dashboard.tsenta.com");

    private JobIdentity() {
    }

    public static boolean isAggregatorUrl(String url) {
        if (url == null) return false;
        try {
            String host = URI.create(url.strip()).getHost();
            if (host == null) return false;
            String h = host.toLowerCase(Locale.ROOT);
            return AGGREGATOR_HOSTS.contains(h) || h.endsWith(".tsenta.com");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Identifies aggregator and job-board hosts (Tsenta, Adzuna, Arbeitnow, FreeHire, SEEK, Indeed).
     * Used for ranking URLs so genuine employer and ATS URLs supersede job boards.
     */
    public static boolean isJobBoardUrl(String url) {
        if (url == null) return false;
        try {
            String host = URI.create(url.strip()).getHost();
            if (host == null) return false;
            String h = host.toLowerCase(Locale.ROOT);
            if (h.startsWith("www.")) h = h.substring(4);
            return isAggregatorUrl(url)
                    || h.equals("arbeitnow.com") || h.endsWith(".arbeitnow.com")
                    || h.equals("freehire.co.uk") || h.endsWith(".freehire.co.uk")
                    || h.equals("freehire.org") || h.endsWith(".freehire.org")
                    || h.equals("adzuna.com") || h.startsWith("adzuna.") || h.contains(".adzuna.")
                    || h.equals("seek.com.au") || h.startsWith("seek.") || h.contains(".seek.")
                    || h.equals("indeed.com") || h.startsWith("indeed.") || h.contains(".indeed.");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** All identity keys for one ingested posting, strongest first, without duplicates. */
    public static List<Key> keys(UUID sourceId, String externalId, String sourceUrl, String applicationUrl,
                                 String employer, String requisitionId) {
        Set<Key> keys = new LinkedHashSet<>();
        if (sourceId != null && externalId != null && !externalId.isBlank()) {
            keys.add(new Key(KeyType.SRC, sourceId + ":" + externalId.strip()));
        }
        for (String url : new String[]{applicationUrl, sourceUrl}) {
            String ats = atsKey(url);
            if (ats != null) keys.add(new Key(KeyType.ATS, ats));
        }
        String employerKey = employerKey(employer);
        if (employerKey != null && requisitionId != null && !requisitionId.isBlank()) {
            keys.add(new Key(KeyType.REQ, employerKey + ":" + requisitionId.strip().toLowerCase(Locale.ROOT)));
        }
        for (String url : new String[]{applicationUrl, sourceUrl}) {
            String normalized = normalizeUrl(url);
            if (normalized != null) keys.add(new Key(KeyType.URL, normalized));
        }
        return new ArrayList<>(keys);
    }

    /**
     * Identity keys including legacy URL normalisation forms for dual-read lookups.
     * Advisory-locked and queried during identity resolution, but never stored on new rows.
     */
    public static List<Key> lookupKeys(UUID sourceId, String externalId, String sourceUrl, String applicationUrl,
                                       String employer, String requisitionId) {
        List<Key> canonical = keys(sourceId, externalId, sourceUrl, applicationUrl, employer, requisitionId);
        Set<Key> all = new LinkedHashSet<>(canonical);
        for (String url : new String[]{applicationUrl, sourceUrl}) {
            String legacy = legacyNormalizeUrl(url);
            if (legacy != null) {
                all.add(new Key(KeyType.URL, legacy));
            }
        }
        return new ArrayList<>(all);
    }

    /**
     * Normalised employer key used in {@code req:} identity keys (legal suffixes and
     * punctuation removed). Public so TRACK can recognise a requisition number quoted
     * in an employer message against the same key the job was indexed under.
     */
    public static String employerKey(String employer) {
        if (employer == null) return null;
        String k = employer.toLowerCase(Locale.ROOT)
                .replaceAll("\\b(inc|incorporated|ltd|limited|llc|plc|gmbh|corp|corporation|co|company)\\b\\.?", "")
                .replaceAll("[^a-z0-9]", "");
        return k.isEmpty() ? null : k;
    }

    private static final Pattern WORKDAY_HOST = Pattern.compile("^([a-z0-9-]+)\\.wd\\d+\\.myworkdayjobs\\.com$");
    private static final Pattern WORKDAY_REQ = Pattern.compile("_([A-Za-z0-9-]+)(?:-\\d+)?$");
    private static final Pattern UUID_RE = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /**
     * Platform job identity embedded in a URL, or null. The greenhouse and
     * ashby forms ({@code greenhouse:{board}:{id}}, {@code ashby:{board}:{id}})
     * are the ones Phase 8.2 already stores in {@code applications.identity_key};
     * they are kept byte-for-byte.
     */
    public static String atsKey(String url) {
        if (url == null || url.isBlank()) return null;
        URI uri;
        try {
            uri = URI.create(url.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (uri.getHost() == null) return null;
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        List<String> s = Parsing.pathSegments(uri);
        String query = uri.getRawQuery() == null ? "" : uri.getRawQuery();
        int n = s.size();

        if ((host.equals("boards.greenhouse.io") || host.equals("job-boards.greenhouse.io")
                || host.equals("job-boards.eu.greenhouse.io") || host.equals("boards.eu.greenhouse.io"))) {
            if (n >= 3 && "jobs".equals(s.get(n - 2))) {
                return "greenhouse:" + s.get(n - 3) + ":" + s.get(n - 1);
            }
            Matcher mFor = Pattern.compile("(?:^|&)for=([^&]+)").matcher(query);
            Matcher mToken = Pattern.compile("(?:^|&)token=([^&]+)").matcher(query);
            if (mFor.find() && mToken.find()) {
                return "greenhouse:" + mFor.group(1).toLowerCase(Locale.ROOT) + ":" + mToken.group(1);
            }
        }
        if (host.equals("jobs.ashbyhq.com") && n >= 2) {
            String id = s.get(1);
            return "ashby:" + s.get(0) + ":" + id;
        }
        if ((host.equals("jobs.lever.co") || host.equals("jobs.eu.lever.co")) && n >= 2 && UUID_RE.matcher(s.get(1)).matches()) {
            return "lever:" + s.get(0).toLowerCase(Locale.ROOT) + ":" + s.get(1).toLowerCase(Locale.ROOT);
        }
        Matcher wd = WORKDAY_HOST.matcher(host);
        if (wd.matches()) {
            int job = s.indexOf("job");
            if (job >= 0 && n > job + 1) {
                Matcher req = WORKDAY_REQ.matcher(s.get(n - 1).equals("apply") && n >= 2 ? s.get(n - 2) : s.get(n - 1));
                if (req.find()) return "workday:" + wd.group(1) + ":" + req.group(1).toLowerCase(Locale.ROOT);
            }
            return null;
        }
        if (host.equals("jobs.smartrecruiters.com") && n >= 2) {
            Matcher m = Pattern.compile("^(\\d{6,})").matcher(s.get(1));
            if (m.find()) return "smartrecruiters:" + s.get(0).toLowerCase(Locale.ROOT) + ":" + m.group(1);
        }
        if (host.equals("apply.workable.com")) {
            int j = s.indexOf("j");
            if (j >= 0 && n > j + 1) return "workable:" + s.get(j + 1).toUpperCase(Locale.ROOT);
        }
        if (host.endsWith(".pinpointhq.com")) {
            int p = s.indexOf("postings");
            if (p >= 0 && n > p + 1 && UUID_RE.matcher(s.get(p + 1)).matches()) return "pinpoint:" + s.get(p + 1).toLowerCase(Locale.ROOT);
        }
        if (n >= 3 && s.get(0).equalsIgnoreCase("jobs") && s.get(1).equalsIgnoreCase("Careers") && s.get(2).matches("\\d{10,}")) {
            return "zoho_recruit:" + s.get(2); // Zoho serves the same record id on its own and on custom hosts
        }
        if (host.endsWith(".icims.com") && n >= 2 && s.get(0).equals("jobs") && s.get(1).matches("\\d+")) {
            return "icims:" + host + ":" + s.get(1);
        }
        if (host.endsWith(".applytojob.com") && n >= 2 && s.get(0).equals("apply") && !s.get(1).equals("jobs")) {
            return "jazzhr:" + host.substring(0, host.indexOf('.')) + ":" + s.get(1);
        }
        if (host.equals("jobs.jobvite.com") && n >= 3 && s.get(1).equals("job")) {
            return "jobvite:" + s.get(0).toLowerCase(Locale.ROOT) + ":" + s.get(2);
        }
        if (host.endsWith(".breezy.hr") && n >= 2 && s.get(0).equals("p")) {
            Matcher m = Pattern.compile("^([0-9a-f]{12})").matcher(s.get(1));
            if (m.find()) return "breezyhr:" + host.substring(0, host.indexOf('.')) + ":" + m.group(1);
        }
        if (host.endsWith(".oraclecloud.com")) {
            int j = s.indexOf("job");
            if (j >= 0 && n > j + 1 && s.get(j + 1).matches("\\d+")) return "oracle_cloud:" + host + ":" + s.get(j + 1);
        }
        if (host.equals("recruiting.paylocity.com") && n >= 4 && s.get(n - 2).equalsIgnoreCase("Details")) {
            return "paylocity:" + s.get(n - 1);
        }
        if (host.matches("recruiting\\d{0,2}\\.ultipro\\.(com|ca)")) {
            Matcher m = Pattern.compile("(?:^|&)opportunityId=(" + UUID_RE.pattern() + ")").matcher(query);
            if (m.find()) return "ultipro:" + m.group(1).toLowerCase(Locale.ROOT);
        }
        if (host.equals("ats.rippling.com")) {
            int j = s.indexOf("jobs");
            if (j >= 0 && n > j + 1 && UUID_RE.matcher(s.get(j + 1)).matches()) return "rippling:" + s.get(j + 1).toLowerCase(Locale.ROOT);
        }
        if (host.equals("jobs.gem.com") && n >= 2) return "gem:" + s.get(0).toLowerCase(Locale.ROOT) + ":" + s.get(1);
        if (host.equals("join.com") && n >= 3 && s.get(0).equals("companies")) {
            Matcher m = Pattern.compile("^(\\d+)").matcher(s.get(2));
            if (m.find()) return "join:" + m.group(1);
        }
        if (host.endsWith(".teamtailor.com") && n >= 2 && s.get(0).equals("jobs")) {
            Matcher m = Pattern.compile("^(\\d+)").matcher(s.get(1));
            if (m.find()) return "teamtailor:" + host + ":" + m.group(1);
        }
        if (host.endsWith(".recruitee.com")) {
            int o = s.indexOf("o");
            if (o >= 0 && n > o + 1) {
                return "recruitee:" + host.substring(0, host.indexOf('.')).toLowerCase(Locale.ROOT) + ":" + s.get(o + 1).toLowerCase(Locale.ROOT);
            }
        }
        if (host.endsWith(".phenom.com") || host.endsWith(".phenompeople.com")) {
            int j = s.indexOf("job");
            if (j >= 0 && n > j + 1) {
                return "phenom:" + host + ":" + s.get(j + 1).toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    /**
     * Canonical normalised URL:
     * - lowercase scheme and host;
     * - drop default port (80 for http, 443 for https);
     * - treat http and https as equivalent (normalized to https for identity keys);
     * - treat leading www. as equivalent (stripped);
     * - decode RFC 3986 §6.2.2.2 unreserved characters;
     * - drop fragment and trailing slash;
     * - filter tracking parameters (including mc_*);
     * - retain original case of query parameter values;
     * - sort query parameters by key name.
     */
    public static String normalizeUrl(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            URI uri = URI.create(url.strip());
            if (uri.getHost() == null) return null;
            String rawScheme = uri.getScheme() == null ? "https" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!rawScheme.equals("http") && !rawScheme.equals("https")) return null;
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            if (host.startsWith("www.")) {
                host = host.substring(4);
            }
            int port = uri.getPort();
            String authority = (port == -1 || ("https".equals(rawScheme) && port == 443) || ("http".equals(rawScheme) && port == 80))
                    ? host : host + ":" + port;
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            while (path.endsWith("/") && !path.isEmpty()) {
                path = path.substring(0, path.length() - 1);
            }
            path = decodeUnreserved(path);
            List<String> pairs = new ArrayList<>();
            if (uri.getRawQuery() != null) {
                for (String pair : uri.getRawQuery().split("&")) {
                    if (pair.isBlank()) continue;
                    String[] parts = pair.split("=", 2);
                    String name = decodeUnreserved(parts[0]).toLowerCase(Locale.ROOT);
                    if (isTrackingParam(name)) continue;
                    String val = parts.length > 1 ? decodeUnreserved(parts[1]) : "";
                    pairs.add(parts.length > 1 ? name + "=" + val : name);
                }
            }
            pairs.sort(String::compareTo);
            return "https://" + authority + path + (pairs.isEmpty() ? "" : "?" + String.join("&", pairs));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Pre-Wave-1 legacy URL normaliser retained for dual-read lookups. */
    public static String legacyNormalizeUrl(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            URI uri = URI.create(url.strip());
            if (uri.getHost() == null) return null;
            String scheme = uri.getScheme() == null ? "https" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) return null;
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            String authority = (port == -1 || ("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80))
                    ? host : host + ":" + port;
            String path = uri.getRawPath() == null ? "" : uri.getRawPath();
            while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            List<String> pairs = new ArrayList<>();
            if (uri.getRawQuery() != null) {
                for (String pair : uri.getRawQuery().split("&")) {
                    if (pair.isBlank()) continue;
                    String name = pair.split("=", 2)[0].toLowerCase(Locale.ROOT);
                    if (isTrackingParam(name)) continue;
                    pairs.add(pair.toLowerCase(Locale.ROOT));
                }
            }
            pairs.sort(String::compareTo);
            return scheme + "://" + authority + path + (pairs.isEmpty() ? "" : "?" + String.join("&", pairs));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static String decodeUnreserved(String s) {
        if (s == null || !s.contains("%")) return s;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); ) {
            if (s.charAt(i) == '%' && i + 2 < s.length()) {
                String hex = s.substring(i + 1, i + 3);
                try {
                    int val = Integer.parseInt(hex, 16);
                    if ((val >= 'A' && val <= 'Z') || (val >= 'a' && val <= 'z')
                            || (val >= '0' && val <= '9') || val == '-' || val == '.' || val == '_' || val == '~') {
                        sb.append((char) val);
                        i += 3;
                        continue;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            sb.append(s.charAt(i));
            i++;
        }
        return sb.toString();
    }

    public static boolean isTrackingParam(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.ROOT);
        return n.startsWith("utm_") || n.startsWith("mc_") || n.startsWith("lever-")
                || n.equals("gclid") || n.equals("fbclid") || n.equals("msclkid")
                || n.equals("ref") || n.equals("source") || n.equals("src") || n.equals("gh_src")
                || n.equals("tk") || n.equals("tracking") || n.equals("campaign")
                || n.equals("in_iframe") || n.equals("oga") || n.equals("trk");
    }

    /**
     * How good an application URL is:
     * - ATS job URL (3)
     * - Employer page (2)
     * - Job board / aggregator (1)
     * - Pure aggregator (0)
     */
    public static int applicationUrlRank(String url) {
        if (url == null || url.isBlank()) return 0;
        if (isAggregatorUrl(url)) return 0;
        if (isJobBoardUrl(url)) return 1;
        return atsKey(url) != null ? 3 : 2;
    }
}
