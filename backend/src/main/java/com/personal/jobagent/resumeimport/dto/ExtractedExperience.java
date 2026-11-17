package com.personal.jobagent.resumeimport.dto;

import java.time.LocalDate;
import java.util.List;

public record ExtractedExperience(
        String company,
        String title,
        String location,
        String startMonth,
        String endMonth,
        boolean current,
        List<String> responsibilities,
        List<String> achievements,
        List<String> technologies,
        boolean sourceVerified,
        boolean requiresReview
) {
    public ExtractedExperience(String company, String title, String location,
                               LocalDate startMonth, LocalDate endMonth,
                               boolean current, List<String> responsibilities,
                               List<String> achievements, List<String> technologies,
                               boolean sourceVerified, boolean requiresReview) {
        this(company, title, location,
                startMonth != null ? startMonth.toString().substring(0, 7) : null,
                endMonth != null ? endMonth.toString().substring(0, 7) : null,
                current, responsibilities, achievements, technologies,
                sourceVerified, requiresReview);
    }
}
