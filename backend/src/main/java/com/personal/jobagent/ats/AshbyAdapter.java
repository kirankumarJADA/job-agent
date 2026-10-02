package com.personal.jobagent.ats;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;
import java.util.Map;

/**
 * Ashby detection adapter — HONEST about its boundary.
 *
 * <p>Verified against the live service (Phase 8 audit): {@code
 * jobs.ashbyhq.com} serves a fully client-rendered React application — a
 * 340KB HTML shell with zero {@code <form>}/{@code <input>} elements — and
 * the public posting API ({@code api.ashbyhq.com/posting-api/...}) exposes
 * job metadata only, with no application-form schema. There is therefore NO
 * deterministic static inspection path for Ashby forms, and per the safety
 * doctrine (KNOWN + DETERMINISTIC → automate; UNKNOWN → human; never
 * simulate) this adapter:
 *
 * <ul>
 *   <li>detects Ashby URLs precisely (so jobs land on the screenshot-only
 *       inspection-plan path, where a human completes the application);</li>
 *   <li>refuses form inspection with a stable, honest 503 — it never returns
 *       the fabricated field list the old mock stub produced;</li>
 *   <li>refuses submission outright.</li>
 * </ul>
 */
@Component
public class AshbyAdapter extends BaseAtsAdapter {

    private static final String INSPECTION_UNAVAILABLE =
            "ASHBY_INSPECTION_UNAVAILABLE: Ashby apply forms are rendered client-side and expose no "
                    + "deterministic form schema to static inspection — the application continues on the "
                    + "human-assisted path";

    public AshbyAdapter() {
        super(AtsKind.ASHBY, java.util.regex.Pattern.compile("ashbyhq\\.com"), false, false);
    }

    @Override
    public boolean matchesUrl(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme())
                    && (uri.getPort() == -1 || uri.getPort() == 443)
                    && uri.getUserInfo() == null
                    && ("jobs.ashbyhq.com".equalsIgnoreCase(host)
                    || "api.ashbyhq.com".equalsIgnoreCase(host));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public FormDescriptor inspectForm(String url) {
        if (url == null || url.isBlank() || !matchesUrl(url)) {
            throw new IllegalArgumentException("URL is not an Ashby board URL");
        }
        throw new IllegalStateException(INSPECTION_UNAVAILABLE);
    }

    @Override
    public SubmissionResult submitApplication(String url, SubmissionPayload payload, boolean dryRun) {
        return new SubmissionResult(false, "REAL_SUBMIT_DISABLED", null,
                "Ashby submission is disabled — applications are completed by the candidate",
                Map.of("kind", AtsKind.ASHBY.name().toLowerCase(Locale.ROOT)));
    }
}
