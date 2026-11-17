package com.personal.jobagent.resumeimport.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ExperienceDecision(
        ImportAction action,
        UUID matchedExistingId,
        String company,
        String title,
        String location,
        LocalDate startMonth,
        LocalDate endMonth,
        List<String> responsibilities,
        List<String> achievements
) {
}
