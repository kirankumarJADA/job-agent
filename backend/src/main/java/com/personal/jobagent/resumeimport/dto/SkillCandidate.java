package com.personal.jobagent.resumeimport.dto;

import java.util.UUID;

public record SkillCandidate(String clientId, ExtractedSkill data, MatchStatus matchStatus, UUID matchedExistingId) {
}
