package com.personal.jobagent.profile;

import java.util.Map;
import java.util.UUID;

public record ProfileRecord(
        UUID id,
        UUID userId,
        String headline,
        String phone,
        String location,
        Map<String, Object> workEligibility,
        Map<String, Object> careerGoals
) {
}
