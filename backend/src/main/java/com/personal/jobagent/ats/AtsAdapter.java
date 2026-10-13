package com.personal.jobagent.ats;

import java.net.URI;
import java.util.Map;

public interface AtsAdapter {

    AtsKind kind();

    boolean matchesUrl(String url);

    /**
     * Whether {@link #inspectForm(String)} performs REAL, deterministic
     * inspection of a matching URL. Adapters that would return a static or
     * mock descriptor report false so listings never present a simulation as
     * an inspection capability. Default false — real inspectors opt in.
     */
    default boolean supportsLiveInspection() {
        return false;
    }

    record FormDescriptor(
            AtsKind kind,
            String endpoint,
            boolean requiresAuth,
            boolean multiStep,
            java.util.List<String> supportedFields,
            boolean supportsFileUpload,
            java.util.List<FormFieldDescriptor> fields
    ) {
        /** Null fields normalize to an empty list so adapters without real inspection stay safe. */
        public FormDescriptor {
            fields = fields == null ? java.util.List.of() : java.util.List.copyOf(fields);
        }
    }

    /**
     * What the employer's application form actually said about required-ness
     * (Phase 8.2). Tri-state on purpose: REQUIRED and OPTIONAL need positive
     * evidence on the form, while absent metadata stays UNKNOWN — a field is
     * never assumed optional because the form supplied nothing.
     */
    enum RequiredState { REQUIRED, OPTIONAL, UNKNOWN }

    /**
     * One inspected form control (Phase 3A, read-only). Deterministic metadata
     * only — never candidate values. {@code key} is the stable field identifier
     * (Greenhouse: the control's {@code id}, e.g. {@code first_name},
     * {@code resume}, {@code question_123}); {@code selector} is the
     * deterministic locator ({@code #id}); {@code options} holds select/radio
     * choices when statically present (empty when rendered by JavaScript —
     * uncertainty is reported, never guessed).
     */
    record FormFieldDescriptor(
            String key,
            String label,
            String htmlType,
            RequiredState requiredState,
            String selector,
            java.util.List<String> options
    ) {
        public FormFieldDescriptor {
            options = options == null ? java.util.List.of() : java.util.List.copyOf(options);
            requiredState = requiredState == null ? RequiredState.UNKNOWN : requiredState;
        }

        /** Whether the employer's form marks this control required. */
        public boolean required() {
            return requiredState == RequiredState.REQUIRED;
        }
    }

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