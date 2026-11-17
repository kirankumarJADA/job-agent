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
        String startMonth,
        String endMonth,
        List<String> responsibilities,
        List<String> achievements
) {
    public ExperienceDecision(ImportAction action, UUID matchedExistingId, String company, String title,
                              String location, LocalDate startMonth, LocalDate endMonth,
                              List<String> responsibilities, List<String> achievements) {
        this(action, matchedExistingId, company, title, location,
                startMonth != null ? startMonth.toString().substring(0, 7) : null,
                endMonth != null ? endMonth.toString().substring(0, 7) : null,
                responsibilities, achievements);
    }
}
