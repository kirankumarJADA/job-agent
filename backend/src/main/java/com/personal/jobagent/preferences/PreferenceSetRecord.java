package com.personal.jobagent.preferences;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record PreferenceSetRecord(
        UUID id,
        UUID profileId,
        List<String> titles,
        List<String> keywordsInclude,
        List<String> keywordsExclude,
        List<String> requiredSkills,
        List<String> locationsAllowed,
        List<String> remoteTypes,
        List<String> employmentTypes,
        List<String> experienceLevels,
        Long salaryMinGbp,
        String sponsorshipPolicy,
        List<String> companySizePref,
        List<String> industryPref,
        String applicationMode,
        Map<String, Object> scoringWeights,
        Map<String, Object> extraFilters,
        boolean isActive
) {
}
