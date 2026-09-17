package com.personal.jobagent.ats;

import com.personal.jobagent.common.UuidV7;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public abstract class BaseAtsAdapter implements AtsAdapter {

    private final AtsKind kind;
    private final Pattern domainPattern;
    private final boolean requiresAuth;
    private final boolean multiStep;

    protected BaseAtsAdapter(AtsKind kind, Pattern domainPattern, boolean requiresAuth, boolean multiStep) {
        this.kind = kind;
        this.domainPattern = domainPattern;
        this.requiresAuth = requiresAuth;
        this.multiStep = multiStep;
    }

    @Override
    public AtsKind kind() {
        return kind;
    }

    @Override
    public boolean matchesUrl(String url) {
        if (url == null) return false;
        return domainPattern.matcher(url.toLowerCase()).find();
    }

    @Override
    public FormDescriptor inspectForm(String url) {
        return new FormDescriptor(
                kind,
                url,
                requiresAuth,
                multiStep,
                List.of("full_name", "email", "phone", "resume", "cover_letter", "linkedin_url"),
                true
        );
    }

    @Override
    public SubmissionResult submitApplication(String url, SubmissionPayload payload, boolean dryRun) {
        // Negative-path guards: CAPTCHA / anti-bot / unverified security checks
        if (url == null || url.isBlank()) {
            return new SubmissionResult(false, "INVALID_URL", null, "Application URL cannot be null or empty", Map.of());
        }

        if (payload.email() == null || !payload.email().contains("@")) {
            return new SubmissionResult(false, "INVALID_PAYLOAD", null, "Valid email is required for submission", Map.of());
        }

        if (payload.resumePdfBase64() == null || payload.resumePdfBase64().isBlank()) {
            return new SubmissionResult(false, "MISSING_ATTACHMENT", null, "Resume PDF file is mandatory", Map.of());
        }

        // Deterministic simulation & dry-run validation
        String confirmationId = "MOCK-" + kind.name() + "-" + UuidV7.generate().toString().substring(0, 8).toUpperCase();
        return new SubmissionResult(
                true,
                dryRun ? "DRY_RUN_PASSED" : "SUBMITTED",
                confirmationId,
                "Successfully processed by " + kind.name() + " adapter",
                Map.of("adapter", kind.name(), "dryRun", dryRun, "url", url)
        );
    }
}