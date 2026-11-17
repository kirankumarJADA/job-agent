package com.personal.jobagent.resumeimport.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ResumeImportPreviewResponse(
        UUID previewToken,
        Instant expiresAt,
        boolean offlineFallback,
        List<PersonalFieldCandidate> personal,
        String extractedProfessionalSummary,
        String extractedCareerFocus,
        List<String> extractedSpecializations,
        boolean summarySourceVerified,
        List<ExperienceCandidate> experiences,
        List<EducationCandidate> education,
        List<ProjectCandidate> projects,
        List<SkillCandidate> skills,
        List<CertificationCandidate> certifications,
        Counts counts
) {
    public record Counts(int newCount, int existingCount, int possibleDuplicateCount, int conflictCount, int needsReviewCount) {
    }
}
