package com.personal.jobagent.resumeimport.dto;

import java.util.List;

/**
 * The raw structured output of one resume extraction, before comparison against the existing Master Profile.
 *
 * @param offlineFallback true when no real AI provider answered and the router fell back to the
 *                        built-in offline parser; every item is then marked for review
 */
public record ExtractedProfile(
        ExtractedPersonal personal,
        ExtractedSummary summary,
        List<ExtractedExperience> experiences,
        List<ExtractedEducation> education,
        List<ExtractedProject> projects,
        List<ExtractedSkill> skills,
        List<ExtractedCertification> certifications,
        boolean offlineFallback
) {
}
