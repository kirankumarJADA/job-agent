package com.personal.jobagent.resumeimport.dto;

import java.util.List;

public record ExtractedProject(
        String name,
        String description,
        String url,
        List<String> technologies,
        List<String> achievements,
        boolean sourceVerified,
        boolean requiresReview
) {
}
