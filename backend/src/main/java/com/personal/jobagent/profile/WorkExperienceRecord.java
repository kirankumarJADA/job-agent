package com.personal.jobagent.profile;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record WorkExperienceRecord(
        UUID id, UUID profileId, String company, String title,
        LocalDate startMonth, LocalDate endMonth, String location,
        List<Map<String, Object>> bullets, int sortOrder
) {
}
