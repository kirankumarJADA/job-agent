package com.personal.jobagent.resumeimport.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Only what the user explicitly approved. {@code personalFieldUpdates} carries
 * just the personal-info fields the user chose to apply, keyed by the names
 * returned on {@link PersonalFieldCandidate#field()}; any other key (such as
 * "email") is ignored, so the account's identity can never be changed here.
 */
public record ResumeImportConfirmRequest(
        UUID previewToken,
        Map<String, String> personalFieldUpdates,
        String professionalSummaryUpdate,
        List<ExperienceDecision> experiences,
        List<EducationDecision> education,
        List<ProjectDecision> projects,
        List<SkillDecision> skills,
        List<CertificationDecision> certifications
) {
}
