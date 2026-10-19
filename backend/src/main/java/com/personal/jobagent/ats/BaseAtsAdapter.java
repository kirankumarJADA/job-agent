package com.personal.jobagent.ats;

import com.personal.jobagent.common.UuidV7;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public abstract class BaseAtsAdapter implements AtsAdapter {

    private final AtsKind kind;
    private final Pattern domainPattern;
    // Widened to protected (read-only) so subclasses can build richer
    // FormDescriptors from the same constructor constants.
    protected final boolean requiresAuth;
    protected final boolean multiStep;

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
                true,
                List.of()
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

        if (!dryRun) {
            // No adapter in this generic family ever performs a real submission:
            // this class has no browser, no session, and no way to know whether
            // a real submission would even succeed. Real execution exists only
            // for Greenhouse, through the separate, reviewed AutomationPlan /
            // worker pipeline (automation.GreenhouseExecutionPlanService), and
            // only after explicit human approval. This branch must never report
            // success for a non-dry-run call, in any environment, so a caller
            // can never mistake this adapter path for a genuine submission.
            return new SubmissionResult(false, "REAL_SUBMIT_NOT_IMPLEMENTED", null,
                    "No real submission path exists for " + kind.name() + " through this adapter; "
                            + "real execution is implemented only for Greenhouse, via the reviewed "
                            + "automation pipeline, and only after human approval.",
                    Map.of("kind", kind.name(), "simulated", false));
        }

        // Deterministic simulation & dry-run validation only — this branch
        // never performs or claims a real submission.
        String confirmationId = "MOCK-" + kind.name() + "-" + UuidV7.generate().toString().substring(0, 8).toUpperCase();
        return new SubmissionResult(
                true,
                "DRY_RUN_PASSED",
                confirmationId,
                "Successfully processed by " + kind.name() + " adapter (dry run — no real submission occurred)",
                Map.of("adapter", kind.name(), "dryRun", dryRun, "url", url, "simulated", true)
        );
    }
}