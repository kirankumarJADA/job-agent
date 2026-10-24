package com.personal.jobagent.automation;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Phase 10: read-only access to the honest ATS capability model
 * ({@link AtsCapabilityRegistry}). Static product facts, not per-candidate
 * data — every authenticated user sees the same rows. The frontend uses this
 * instead of guessing a job's apply capability from its URL on its own, so
 * user-facing capability labels agree with the same verified facts the
 * backend itself enforces (one declared set of host patterns, not two).
 */
@RestController
@RequestMapping("/api/v1/ats-capabilities")
public class AtsCapabilityController {

    public record CapabilityRow(String kind, String displayName, List<String> hostPatterns, String summary,
                                Map<String, String> dimensions, String verificationNote) {
        static CapabilityRow from(AtsCapabilities c) {
            Map<String, String> dims = new java.util.LinkedHashMap<>();
            dims.put("jobDiscovery", c.jobDiscovery().name());
            dims.put("applicationUrlResolution", c.applicationUrlResolution().name());
            dims.put("formInspection", c.formInspection().name());
            dims.put("accountCreationReadiness", c.accountCreationReadiness().name());
            dims.put("signInReadiness", c.signInReadiness().name());
            dims.put("requiredFieldValidation", c.requiredFieldValidation().name());
            dims.put("questionAnswering", c.questionAnswering().name());
            dims.put("documentAttachment", c.documentAttachment().name());
            dims.put("formFilling", c.formFilling().name());
            dims.put("finalSubmission", c.finalSubmission().name());
            dims.put("submissionConfirmation", c.submissionConfirmation().name());
            dims.put("recoveryAndDuplicatePrevention", c.recoveryAndDuplicatePrevention().name());
            return new CapabilityRow(c.kind(), c.displayName(), c.hostPatterns(), c.summary().name(), dims, c.verificationNote());
        }
    }

    @GetMapping
    public List<CapabilityRow> all() {
        return AtsCapabilityRegistry.all().stream().map(CapabilityRow::from).toList();
    }

    @GetMapping("/{kind}")
    public CapabilityRow byKind(@PathVariable String kind) {
        return CapabilityRow.from(AtsCapabilityRegistry.byPlanType(kind));
    }
}
