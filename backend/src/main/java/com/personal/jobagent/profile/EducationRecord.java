package com.personal.jobagent.profile;

import java.util.UUID;

public record EducationRecord(
        UUID id, UUID profileId, String institution, String qualification,
        String field, Integer startYear, Integer endYear, String grade
) {
}
