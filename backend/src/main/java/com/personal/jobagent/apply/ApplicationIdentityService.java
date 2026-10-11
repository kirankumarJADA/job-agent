package com.personal.jobagent.apply;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Cross-source duplicate identity for applications (Phase 8.2).
 *
 * <p>Two postings are the SAME role only when the strongest identity signals
 * actually available agree: the normalised canonical/application URL, the
 * employer's requisition identifier as embedded in the board URL, or the
 * source's external job id. Titles are never used — genuinely different roles
 * that share a title are not combined.
 *
 * <p>Tracking-only query parameters (utm_*, gclid, fbclid, ref, source…) are
 * stripped before comparison, so the same posting seen through a tracked link
 * is still recognised. Detection is owner-scoped end to end: a duplicate is
 * only ever reported against the caller's own applications.
 */
@Service
public class ApplicationIdentityService {

    private final JdbcTemplate db;

    public ApplicationIdentityService(JdbcTemplate db) {
        this.db = db;
    }

    public record ExistingApplication(UUID applicationId, UUID jobId, String status,
                                      String identityKey, String matchReason) {}

    /** Identity key for one job row, or null when no reliable signal exists. */
    public String identityKeyForJob(UUID jobId) {
        return db.query("""
                select j.canonical_url, j.application_url, s.kind, j.external_id
                from jobs j join job_sources s on s.id = j.source_id
                where j.id = ?
                """, (rs, n) -> identityKey(rs.getString("canonical_url"), rs.getString("application_url"),
                rs.getString("kind"), rs.getString("external_id")), jobId)
                .stream().findFirst().orElse(null);
    }

    /**
     * Deterministic identity key from the available signals, strongest first.
     * Returns null when nothing reliable exists — an unknown identity never
     * matches anything (it is not "the same as" another unknown).
     */
    public String identityKey(String canonicalUrl, String applicationUrl, String sourceKind, String externalId) {
        String fromCanonical = boardIdentity(canonicalUrl);
        if (fromCanonical != null) return fromCanonical;
        String fromApplication = boardIdentity(applicationUrl);
        if (fromApplication != null) return fromApplication;
        String normalized = normalizeUrl(canonicalUrl != null && !canonicalUrl.isBlank() ? canonicalUrl : applicationUrl);
        if (normalized != null) return "url:" + normalized;
        if (sourceKind != null && externalId != null && !externalId.isBlank()) {
            return "src:" + sourceKind.toLowerCase(Locale.ROOT) + ":" + externalId.trim();
        }
        return null;
    }

    /** Employer requisition identity when the board URL embeds one (Greenhouse, Ashby). */
    static String boardIdentity(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            URI uri = URI.create(url.trim());
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            String[] segments = uri.getPath() == null ? new String[0]
                    : java.util.Arrays.stream(uri.getPath().split("/")).filter(s -> !s.isBlank()).toArray(String[]::new);
            if (("boards.greenhouse.io".equals(host) || "job-boards.greenhouse.io".equals(host))
                    && segments.length >= 3 && "jobs".equals(segments[segments.length - 2])) {
                return "greenhouse:" + segments[segments.length - 3] + ":" + segments[segments.length - 1];
            }
            if ("jobs.ashbyhq.com".equals(host) && segments.length >= 2) {
                return "ashby:" + segments[0] + ":" + segments[segments.length - 1];
            }
        } catch (IllegalArgumentException ignored) {
            // not a parsable URL: fall through to the generic signals
        }
        return null;
    }

    /** Normalised URL: lowercase host, no fragment, no tracking parameters, no trailing slash. */
    static String normalizeUrl(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            URI uri = URI.create(url.trim());
            if (uri.getHost() == null) return null;
            String scheme = uri.getScheme() == null ? "https" : uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            String authority = (port == -1 || ("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80))
                    ? host : host + ":" + port;
            String path = uri.getPath() == null ? "" : uri.getPath();
            while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            String query = uri.getRawQuery();
            StringBuilder kept = new StringBuilder();
            if (query != null) {
                java.util.List<String> pairs = new ArrayList<>();
                for (String pair : query.split("&")) {
                    if (pair.isBlank()) continue;
                    String name = pair.split("=", 2)[0].toLowerCase(Locale.ROOT);
                    if (isTrackingParam(name)) continue;
                    pairs.add(pair.toLowerCase(Locale.ROOT));
                }
                pairs.sort(String::compareTo);
                for (String pair : pairs) kept.append(kept.isEmpty() ? "" : "&").append(pair);
            }
            return scheme + "://" + authority + path + (kept.isEmpty() ? "" : "?" + kept);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isTrackingParam(String name) {
        return name.startsWith("utm_") || name.equals("gclid") || name.equals("fbclid") || name.equals("msclkid")
                || name.equals("ref") || name.equals("source") || name.equals("src") || name.equals("gh_src")
                || name.startsWith("lever-") || name.equals("tk") || name.equals("tracking") || name.equals("campaign");
    }

    /**
     * The caller's own live applications that share this job's identity —
     * including legacy rows created before V036 (their key is derived on the
     * fly). Empty when there is no duplicate or when no identity can be
     * established.
     */
    public List<ExistingApplication> findDuplicates(UUID profileId, UUID jobId) {
        String target = identityKeyForJob(jobId);
        if (target == null || profileId == null) return List.of();
        List<Map<String, Object>> rows = db.queryForList("""
                select a.id, a.job_id, a.status, a.identity_key,
                       j.canonical_url, j.application_url, s.kind as source_kind, j.external_id
                from applications a
                join jobs j on j.id = a.job_id
                join job_sources s on s.id = j.source_id
                where a.profile_id = ? and a.status not in ('FAILED','WITHDRAWN')
                """, profileId);
        List<ExistingApplication> duplicates = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            UUID existingJob = (UUID) row.get("job_id");
            if (jobId.equals(existingJob)) continue; // same posting: the one-per-job rule applies
            String key = row.get("identity_key") instanceof String stored && !stored.isBlank() ? stored
                    : identityKey((String) row.get("canonical_url"), (String) row.get("application_url"),
                    (String) row.get("source_kind"), (String) row.get("external_id"));
            if (target.equals(key)) {
                duplicates.add(new ExistingApplication((UUID) row.get("id"), existingJob,
                        (String) row.get("status"), key, describeMatch(target)));
            }
        }
        return duplicates;
    }

    public List<ExistingApplication> findByIdentity(UUID profileId, String identityKey) {
        if (profileId == null || identityKey == null) return List.of();
        return db.query("""
                select id, job_id, status, identity_key from applications
                where profile_id = ? and identity_key = ? and status not in ('FAILED','WITHDRAWN')
                """, (rs, n) -> new ExistingApplication((UUID) rs.getObject("id"), (UUID) rs.getObject("job_id"),
                rs.getString("status"), rs.getString("identity_key"), describeMatch(identityKey)),
                profileId, identityKey);
    }

    /** Owner-scoped: only ever reveals the caller's own duplicate. */
    public ExistingApplication findById(UUID profileId, UUID applicationId) {
        return db.query("""
                select a.id, a.job_id, a.status, a.identity_key from applications a
                where a.id = ? and a.profile_id = ?
                """, (rs, n) -> new ExistingApplication((UUID) rs.getObject("id"), (UUID) rs.getObject("job_id"),
                rs.getString("status"), rs.getString("identity_key"), ""), applicationId, profileId)
                .stream().findFirst().orElse(null);
    }

    static String describeMatch(String identityKey) {
        if (identityKey.startsWith("greenhouse:")) return "same Greenhouse requisition identifier";
        if (identityKey.startsWith("ashby:")) return "same Ashby job identifier";
        if (identityKey.startsWith("url:")) return "same normalised application URL";
        return "same source external job id";
    }
}
