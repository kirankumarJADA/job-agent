package com.personal.jobagent.resumeimport.dto;

import java.time.LocalDate;
import java.util.List;

public record ExtractedExperience(
        String company,
        String title,
        String location,
        LocalDate startMonth,
        LocalDate endMonth,
        boolean current,
        List<String> responsibilities,
        List<String> achievements,
        List<String> technologies,
        boolean sourceVerified,
        boolean requiresReview
) {
}
