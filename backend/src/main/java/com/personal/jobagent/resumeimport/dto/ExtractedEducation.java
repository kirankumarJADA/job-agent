package com.personal.jobagent.resumeimport.dto;

public record ExtractedEducation(
        String institution,
        String qualification,
        String field,
        Integer startYear,
        Integer endYear,
        String grade,
        boolean sourceVerified,
        boolean requiresReview
) {
}
