package com.personal.jobagent.ats;

import java.net.URI;
import java.util.Map;

public interface AtsAdapter {

    AtsKind kind();

    boolean matchesUrl(String url);

    record FormDescriptor(
            AtsKind kind,
            String endpoint,
            boolean requiresAuth,
            boolean multiStep,
            java.util.List<String> supportedFields,
            boolean supportsFileUpload
    ) {}

    FormDescriptor inspectForm(String url);

    record SubmissionPayload(
            String fullName,
            String email,
            String phone,
            String location,
            String resumePdfBase64,
            String coverLetterMarkdown,
            Map<String, String> answers
    ) {}

    record SubmissionResult(
            boolean success,
            String status,
            String confirmationId,
            String message,
            Map<String, Object> details
    ) {}

    SubmissionResult submitApplication(String url, SubmissionPayload payload, boolean dryRun);
}