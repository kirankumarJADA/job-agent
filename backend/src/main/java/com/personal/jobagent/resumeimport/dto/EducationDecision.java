package com.personal.jobagent.resumeimport.dto;

import java.util.UUID;

public record EducationDecision(
        ImportAction action,
        UUID matchedExistingId,
        String institution,
        String qualification,
        String field,
        Integer startYear,
        Integer endYear,
        String grade
) {
}
