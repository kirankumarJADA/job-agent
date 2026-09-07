package com.personal.jobagent.profile;

import java.math.BigDecimal;
import java.util.UUID;

public record SkillRecord(
        UUID id, UUID profileId, String name, String category,
        Integer mastery, BigDecimal years, UUID evidenceExperienceId
) {
}
