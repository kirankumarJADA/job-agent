package com.personal.jobagent.track.receipt;

import com.personal.jobagent.track.receipt.SubmissionEvidenceRepository.StoredEvidence;
import com.personal.jobagent.track.receipt.UniversalSubmissionEvidence.Hop;
import com.personal.jobagent.track.receipt.UniversalSubmissionEvidence.Parsed;

import java.net.URI;
import java.util.Locale;
import java.util.UUID;

/**
 * Decides whether a Universal submission is a clean browser observation
 * (Level B, {@code BROWSER_OBSERVED}) — shared by the receipt view and TRACK so
 * the two can never disagree. Pure.
 *
 * <p>A browser observation is never employer verification. This policy only
 * decides whether Robin's own record of what the browser saw is intact,
 * authorised and internally consistent:
 * <ol>
 *   <li>the candidate's strict submission approval exists;</li>
 *   <li>raw evidence was stored encrypted, decrypts, and matches the digest the
 *       server computed when the authenticated worker reported it;</li>
 *   <li>its provenance is the authenticated worker's completion call for a
 *       Universal plan, with at least one recorded confirmation signal;</li>
 *   <li>for {@value UniversalSubmissionEvidence#V2} evidence, additionally: its
 *       own canonical digest is intact, its correlation names this plan,
 *       application and job, and every redirect hop and the final page stayed
 *       on the plan's target host — the same exact-origin rule the worker
 *       enforces, re-checked on the server.</li>
 * </ol>
 * Evidence written before v2 passes on checks 1–3 alone, so historical records
 * are not invalidated by the schema change.
 */
public final class BrowserObservationPolicy {

    public static final String VERSION = "browser-observation-policy-v1";

    public record Assessment(boolean clean, String code, String message, Parsed parsed) {
        static Assessment fail(String code, String message, Parsed parsed) {
            return new Assessment(false, code, message, parsed);
        }
    }

    /** What the caller established before asking: integrity is checked by the evidence repository. */
    public record Inputs(StoredEvidence evidence, boolean approvalAttested, boolean integrityVerified,
                         String decryptedPayload, Parsed parsed, UUID planId, UUID applicationId, UUID jobId,
                         String targetUrl) {}

    private BrowserObservationPolicy() {
    }

    public static Assessment assess(Inputs in) {
        Parsed parsed = in.parsed();
        StoredEvidence e = in.evidence();
        if (e == null) {
            return Assessment.fail("NO_EVIDENCE", "no evidence was recorded by the authenticated worker for this attempt.", parsed);
        }
        if (!in.approvalAttested()) {
            return Assessment.fail("APPROVAL_MISSING", "explicit candidate submission approval attestation is missing.", parsed);
        }
        if (!SubmissionEvidenceRepository.RAW_STORED.equals(e.rawEvidenceStatus())) {
            return Assessment.fail("RAW_EVIDENCE_NOT_STORED",
                    "no raw DOM/screenshot evidence was stored for this attempt (" + e.rawEvidenceStatus() + ").", parsed);
        }
        if (!in.integrityVerified() || in.decryptedPayload() == null || in.decryptedPayload().isBlank()) {
            return Assessment.fail("EVIDENCE_INTEGRITY_FAILED",
                    "the stored evidence could not be decrypted or failed its integrity check.", parsed);
        }
        if (!"worker".equals(e.workerPrincipal())
                || !SubmissionEvidenceRepository.RECORDED_VIA_WORKER_COMPLETE.equals(e.recordedVia())
                || !"UNIVERSAL".equalsIgnoreCase(e.atsPlatform())) {
            return Assessment.fail("PROVENANCE_MISMATCH", "evidence provenance did not match an authenticated worker completion.", parsed);
        }
        if (e.observedSignals() == null || e.observedSignals().isEmpty()) {
            return Assessment.fail("NO_SIGNALS", "no browser confirmation signals were recorded for this submission.", parsed);
        }
        if (parsed != null && parsed.isV2()) {
            if (parsed.digest() != UniversalSubmissionEvidence.DigestStatus.VALID) {
                return Assessment.fail("EVIDENCE_DIGEST_MISMATCH",
                        "the browser evidence's own canonical digest does not match its contents.", parsed);
            }
            if (parsed.planId() == null || parsed.applicationId() == null
                    || !parsed.planId().equals(in.planId()) || !parsed.applicationId().equals(in.applicationId())
                    || (parsed.jobId() != null && in.jobId() != null && !parsed.jobId().equals(in.jobId()))) {
                return Assessment.fail("CORRELATION_MISMATCH",
                        "the browser evidence names a different attempt, application or job.", parsed);
            }
            String target = authority(in.targetUrl());
            if (target == null) {
                return Assessment.fail("TARGET_UNKNOWN", "the plan's target address could not be read.", parsed);
            }
            for (Hop hop : parsed.redirectChain()) {
                if (hop.host() == null || !target.equals(normalizeAuthority(hop.host(), scheme(in.targetUrl())))) {
                    return Assessment.fail("REDIRECT_OUTSIDE_TARGET_HOST",
                            "the submission was redirected through a host outside this employer's application address.", parsed);
                }
            }
            if (parsed.finalOrigin() != null && !target.equals(authority(parsed.finalOrigin()))) {
                return Assessment.fail("FINAL_ORIGIN_MISMATCH",
                        "the confirmation page was not on this employer's application address.", parsed);
            }
            return new Assessment(true, "BROWSER_OBSERVED_V2",
                    "Browser observed the submission; evidence v2 integrity, correlation and host policy checks passed.", parsed);
        }
        return new Assessment(true, "BROWSER_OBSERVED_LEGACY_SCHEMA",
                "Browser observed the submission (evidence recorded before schema v2).", parsed);
    }

    private static String scheme(String url) {
        try {
            String s = URI.create(url).getScheme();
            return s == null ? "https" : s.toLowerCase(Locale.ROOT);
        } catch (RuntimeException e) {
            return "https";
        }
    }

    /** host[:port] of an absolute URL or origin, lower-case, with the scheme's default port dropped. */
    static String authority(String url) {
        if (url == null) return null;
        try {
            URI u = URI.create(url.strip());
            if (u.getHost() == null) return null;
            String scheme = u.getScheme() == null ? "https" : u.getScheme().toLowerCase(Locale.ROOT);
            return withPort(u.getHost().toLowerCase(Locale.ROOT), u.getPort(), scheme);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String normalizeAuthority(String hostAndPort, String scheme) {
        String h = hostAndPort.strip().toLowerCase(Locale.ROOT);
        int colon = h.lastIndexOf(':');
        if (colon > 0 && h.indexOf(']') < colon) {
            try {
                return withPort(h.substring(0, colon), Integer.parseInt(h.substring(colon + 1)), scheme);
            } catch (NumberFormatException e) {
                return h;
            }
        }
        return h;
    }

    private static String withPort(String host, int port, String scheme) {
        boolean defaultPort = port == -1 || ("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80);
        return defaultPort ? host : host + ":" + port;
    }
}
