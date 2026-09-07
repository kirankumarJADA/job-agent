package com.personal.jobagent.jobs;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record JobRecord(
        UUID id,
        UUID sourceId,
        String externalId,
        UUID companyId,
        String companyNameRaw,
        String title,
        String locationRaw,
        String city,
        String country,
        String remoteType,
        String employmentType,
        String experienceLevel,
        BigDecimal salaryMin,
        BigDecimal salaryMax,
        String salaryCurrency,
        String descriptionText,
        List<String> skillsExtracted,
        String applicationUrl,
        String canonicalUrl,
        Instant postedAt,
        String status
) {
}
